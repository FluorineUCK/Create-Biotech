package com.nobodiiiii.createbiotech.content.biocomputing.library.service;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookAccess;
import java.util.*;
import net.minecraft.nbt.*;

/** Server-thread confined. Encode before publication/acknowledgement; no transport inside transitions. */
public final class LibraryServiceStore {
    private final Runnable markChanged;
    private Tag durable;
    private LibraryServiceState published;
    private LibraryServiceLimits validated;
    private String diagnostic;
    public LibraryServiceStore(Runnable markChanged) { this.markChanged = Objects.requireNonNull(markChanged); }
    public void read(Tag raw) { durable = raw == null ? null : raw.copy(); unload(); diagnostic = null; }
    public Tag write() { return durable == null ? null : durable.copy(); }
    public void unload() { published = null; validated = null; }
    public Optional<String> diagnostic() { return Optional.ofNullable(diagnostic); }
    public Optional<LibraryServiceState> snapshot(LibraryServiceLimits limits) { return prepare(limits) ? Optional.of(published) : Optional.empty(); }
    public String configure(long expectedRevision, LibraryServiceConfiguration config, LibraryServiceLimits limits) {
        if (!prepare(limits)) return "unavailable";
        if (published.revision() != expectedRevision) return "changed";
        if (published.configuration().equals(config)) return "configured";
        // While busy, only toggling this same endpoint is allowed. Grants and destination names stay pinned.
        if (published.busy() && (!published.configuration().grants().equals(config.grants())
            || !published.configuration().endpoint().map(e -> e.name()).equals(config.endpoint().map(e -> e.name())))) return "busy";
        if (published.revision() == Long.MAX_VALUE) return "revision_exhausted";
        String failure = publish(published.configure(config), limits);
        return failure == null ? "configured" : failure;
    }
    public AdmissionStatus receive(MessageEnvelope message, LibraryServiceLimits limits) {
        return receive(message, limits, null);
    }
    public AdmissionStatus receive(MessageEnvelope message, LibraryServiceLimits limits, LibraryWriteBatch batch) {
        if (!prepare(limits)) return AdmissionStatus.UNAVAILABLE;
        if (!published.configuration().enabled()) return AdmissionStatus.CLOSED;
        if (!(message.protocol() instanceof MessageProtocol.Request)) return AdmissionStatus.UNSUPPORTED;
        var admitted = published.mailbox().admit(message, limits.mailbox());
        if (admitted.status() != AdmissionStatus.ACCEPTED) return admitted.status();
        var candidate = published.inbox(admitted.state());
        if (batch != null && LibraryWriteService.isMutation(message.payload())) {
            var writes = new HashMap<>(candidate.writes());
            writes.put(LibraryMutation.Receipt.key(message), new LibraryWriteBatch.Admission(batch, ""));
            candidate = candidate.writes(writes);
        }
        String failure = publish(candidate, limits);
        if (failure == null) return AdmissionStatus.ACCEPTED;
        return switch (failure) {
            case "memory_limit" -> AdmissionStatus.MEMORY_LIMIT;
            case "archive_limit" -> AdmissionStatus.ARCHIVE_LIMIT;
            default -> AdmissionStatus.UNAVAILABLE;
        };
    }
    public String tick(LibraryReadService.Source source, LibraryServiceLimits limits) {
        if (!prepare(limits)) return "unavailable";
        if (!published.configuration().enabled()) return "closed";
        if (published.reply().isPresent()) return "ready";
        if (!published.busy()) return "idle";
        if (published.nextSequence() == Long.MAX_VALUE) return "sequence_exhausted";
        if (published.writes().containsKey(LibraryMutation.Receipt.key(published.mailbox().queued().getFirst()))) return "batch_pending";
        var result = LibraryReadService.execute(published.configuration(), published.mailbox().queued().getFirst().payload(), source);
        String failure = publish(published.answer(result), limits);
        if ("memory_limit".equals(failure) || "archive_limit".equals(failure))
            failure = publish(published.answer(LibraryReadService.failure("response_too_large")), limits);
        return failure == null ? "ready" : failure;
    }
    /** A complete admission tick is sealed atomically, including writes behind an unacknowledged head. */
    public String tick(LibraryWriteService.Source source, LibraryServiceLimits limits, LibraryWriteBatch now) {
        if (!prepare(limits)) return "unavailable";
        if (!published.configuration().enabled()) return "closed";
        String failure = seal(source, limits, now);
        if (failure != null) return failure;
        if (published.reply().isPresent() || !published.busy()) return tick(source, limits);
        var head = published.mailbox().queued().getFirst();
        var admission = published.writes().get(LibraryMutation.Receipt.key(head));
        if (admission == null) return tick(source, limits);
        if (admission.verdict().isEmpty()) return "batch_pending";
        if (published.nextSequence() == Long.MAX_VALUE) return "sequence_exhausted";
        if (admission.verdict().equals("eligible")) return commit(head, source, limits);
        failure = publish(published.answer(LibraryReadService.failure(admission.verdict())), limits);
        return failure == null ? "ready" : failure;
    }
    private String seal(LibraryWriteService.Source source, LibraryServiceLimits limits, LibraryWriteBatch now) {
        try {
            var prepared = prepareBatch(source, limits, now, Optional.empty());
            prepared.publishWithoutCallback();
            if (prepared.changed()) markChanged.run();
            return null;
        } catch (LibraryBookAccess.Failure failure) { return failure.code(); }
    }
    /** Preflight both owners before installing either verdict; publication has no callbacks or world effects. */
    public final class PreparedBatch {
        private final LibraryServiceState before;
        private final LibraryServiceLimits limits;
        private final Publication next;
        private final Set<LibraryWriteBatch.Target> contenders;
        private PreparedBatch(LibraryServiceState before, LibraryServiceLimits limits, Publication next, Set<LibraryWriteBatch.Target> contenders) {
            this.before = before; this.limits = limits; this.next = next; this.contenders = Set.copyOf(contenders);
        }
        public boolean current() { return published == before && limits.equals(validated); }
        public boolean changed() { return next != null; }
        public boolean competesWith(LibraryWriteBatch.Target target) { return contenders.contains(target); }
        public void publishWithoutCallback() {
            if (!current()) throw new LibraryBookAccess.Failure("changed");
            if (next != null) install(next, limits);
        }
    }
    public PreparedBatch prepareBatch(LibraryWriteService.Source source, LibraryServiceLimits limits, LibraryWriteBatch now,
        Optional<LibraryWriteBatch.Target> external) {
        if (!prepare(limits)) throw new LibraryBookAccess.Failure(diagnostic == null ? "unavailable" : diagnostic);
        var before = published;
        var writes = new HashMap<>(published.writes());
        var contenders = new HashMap<LibraryWriteBatch.Target, List<String>>(); boolean changed = false;
        for (var request : published.mailbox().queued()) {
            String key = LibraryMutation.Receipt.key(request); var admission = writes.get(key);
            if (admission == null || !admission.verdict().isEmpty() || !admission.batch().closedAt(now)) continue;
            changed = true; String verdict;
            try {
                var prepared = LibraryWriteService.prepareAdmitted(published.configuration(), request, source); verdict = "eligible";
                if (!prepared.replay()) {
                    var mutation = LibraryMutation.decode(request.payload());
                    contenders.computeIfAbsent(new LibraryWriteBatch.Target(admission.batch(), mutation.volume(), mutation.entry()), ignored -> new ArrayList<>()).add(key);
                }
            } catch (RuntimeException invalid) { verdict = LibraryWriteService.failure(invalid); }
            writes.put(key, new LibraryWriteBatch.Admission(admission.batch(), verdict));
        }
        for (var target : contenders.keySet()) {
            var group = contenders.get(target);
            if (group.size() + (external.filter(target::equals).isPresent() ? 1 : 0) > 1)
            for (String key : group) writes.put(key, new LibraryWriteBatch.Admission(writes.get(key).batch(), "conflict"));
        }
        return new PreparedBatch(before, limits, changed ? encode(published.writes(writes), limits) : null, contenders.keySet());
    }
    private String commit(MessageEnvelope head, LibraryWriteService.Source source, LibraryServiceLimits limits) {
        LibraryBookAccess.PreparedWrite prepared;
        try { prepared = LibraryWriteService.prepare(published.configuration(), head, source); }
        catch (RuntimeException invalid) {
            String failure = publish(published.answer(LibraryReadService.failure(LibraryWriteService.failure(invalid))), limits);
            return failure == null ? "ready" : failure;
        }
        Publication response;
        try { response = encode(published.answer(prepared.reply()), limits); }
        catch (LibraryBookAccess.Failure tooLarge) { return tooLarge.code(); }
        // No mutation until the exact durable reply fits. An exception may mean the book was already replaced.
        try { prepared.commit().run(); }
        catch (RuntimeException uncertain) { return "commit_retry"; }
        publish(response, limits); return "ready";
    }
    public Optional<LibraryServiceState.Outbound> outbound(LibraryServiceLimits limits) {
        return prepare(limits) ? published.outbound() : Optional.empty();
    }
    public boolean acknowledge(LibraryServiceState.Outbound expected, LibraryServiceLimits limits) {
        if (!prepare(limits) || !published.outbound().filter(expected::equals).isPresent()) return false;
        return publish(published.acknowledged(), limits) == null;
    }
    public boolean acknowledge(LibraryServiceState.Outbound expected, LibraryServiceLimits limits, LibraryWriteService.Source source) {
        if (!prepare(limits) || !published.outbound().filter(expected::equals).isPresent()) return false;
        var head = published.mailbox().queued().getFirst();
        boolean cleanup = published.writes().containsKey(LibraryMutation.Receipt.key(head))
            && expected.envelope().payload().field("code").equals(new DataValue.TextValue("ok"));
        if (!acknowledge(expected, limits)) return false;
        // Queue ACK is durable first. A moved/unavailable book retains its exact receipt and capacity charge.
        if (cleanup) try { source.snapshot().release(head); } catch (RuntimeException unavailable) { /* best effort */ }
        return true;
    }
    private boolean prepare(LibraryServiceLimits limits) {
        Objects.requireNonNull(limits);
        if (limits.equals(validated)) return published != null;
        validated = limits; published = null; diagnostic = null;
        try {
            if (durable != null && !(durable instanceof CompoundTag)) throw new IllegalArgumentException("Expected service compound");
            var candidate = durable == null ? LibraryServiceState.empty()
                : LibraryServiceState.decode(ValueNbtCodec.decode((CompoundTag)durable, limits.archive()));
            String failure = limitFailure(candidate, limits);
            if (failure != null) { diagnostic = failure; return false; }
            published = candidate; return true;
        } catch (IllegalArgumentException invalid) { diagnostic = "invalid_save"; return false; }
    }
    private String publish(LibraryServiceState candidate, LibraryServiceLimits limits) {
        try { publish(encode(candidate, limits), limits); return null; }
        catch (LibraryBookAccess.Failure failure) { return failure.code(); }
    }
    private record Publication(LibraryServiceState state, CompoundTag encoded) {}
    private Publication encode(LibraryServiceState candidate, LibraryServiceLimits limits) {
        String failure = limitFailure(candidate, limits);
        if (failure != null) throw new LibraryBookAccess.Failure(failure);
        CompoundTag encoded;
        try { encoded = ValueNbtCodec.encode(candidate.value(), limits.archive()); }
        catch (ArchiveException invalid) { throw new LibraryBookAccess.Failure(invalid.code() == ArchiveException.Code.LIMIT_EXCEEDED ? "archive_limit" : "unavailable"); }
        return new Publication(candidate, encoded);
    }
    private void publish(Publication prepared, LibraryServiceLimits limits) {
        install(prepared, limits); markChanged.run();
    }
    private void install(Publication prepared, LibraryServiceLimits limits) {
        durable = prepared.encoded(); published = prepared.state(); validated = limits; diagnostic = null;
    }
    private static String limitFailure(LibraryServiceState state, LibraryServiceLimits limits) {
        if (state.configuration().grants().size() > limits.grants()) return "configuration_limit";
        if (state.mailbox().queued().size() > limits.mailbox().messages()) return "queue_full";
        if (state.mailbox().receipts().size() > limits.mailbox().sources()) return "source_limit";
        return state.value().storageUnits() > limits.stateUnits() ? "memory_limit" : null;
    }
}
