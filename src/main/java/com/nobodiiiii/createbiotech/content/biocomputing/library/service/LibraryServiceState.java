package com.nobodiiiii.createbiotech.content.biocomputing.library.service;

import static com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.MailboxArchiveCodec;
import java.util.*;

/** One durable request FIFO and at most one materialized head reply. Entries are never stored here. */
public record LibraryServiceState(UUID id, long revision, long nextSequence, LibraryServiceConfiguration configuration,
    MailboxState mailbox, Optional<DataValue.RecordValue> reply, Map<String, LibraryWriteBatch.Admission> writes) {
    public LibraryServiceState {
        Objects.requireNonNull(id); Objects.requireNonNull(configuration); Objects.requireNonNull(mailbox); Objects.requireNonNull(reply);
        writes = Map.copyOf(writes);
        var mutationKeys = new HashSet<String>();
        for (var request : mailbox.queued()) if (LibraryWriteService.isMutation(request.payload())) mutationKeys.add(LibraryMutation.Receipt.key(request));
        if (!mutationKeys.containsAll(writes.keySet())) throw new IllegalArgumentException("Admission without queued mutation");
        if (revision < 0 || nextSequence < 0) throw new IllegalArgumentException("Negative service counter");
        if (mailbox.queued().stream().anyMatch(e -> !(e.protocol() instanceof MessageProtocol.Request)))
            throw new IllegalArgumentException("Library accepts only requests");
        if (reply.isPresent() && (mailbox.queued().isEmpty() || nextSequence == 0))
            throw new IllegalArgumentException("Reply requires an admitted request and dispatch identity");
    }
    public static LibraryServiceState empty() {
        return new LibraryServiceState(UUID.randomUUID(), 0, 0, LibraryServiceConfiguration.closed(), MailboxState.empty(), Optional.empty(), Map.of());
    }
    public boolean busy() { return !mailbox.queued().isEmpty(); }
    public LibraryServiceState configure(LibraryServiceConfiguration config) {
        return new LibraryServiceState(id, Math.addExact(revision, 1), nextSequence, config, mailbox, reply, writes);
    }
    public LibraryServiceState inbox(MailboxState mailbox) { return new LibraryServiceState(id, revision, nextSequence, configuration, mailbox, reply, writes); }
    public LibraryServiceState writes(Map<String, LibraryWriteBatch.Admission> writes) {
        return new LibraryServiceState(id, revision, nextSequence, configuration, mailbox, reply, writes);
    }
    public LibraryServiceState answer(DataValue.RecordValue value) {
        return new LibraryServiceState(id, revision, Math.addExact(nextSequence, 1), configuration, mailbox, Optional.of(value), writes);
    }
    public LibraryServiceState acknowledged() {
        var remaining = new HashMap<>(writes); remaining.remove(LibraryMutation.Receipt.key(mailbox.queued().getFirst()));
        return new LibraryServiceState(id, revision, nextSequence, configuration, mailbox.withoutHead(), Optional.empty(), remaining);
    }
    public record Outbound(String endpoint, MessageEnvelope envelope) {}
    public Optional<Outbound> outbound() {
        if (!configuration.enabled() || reply.isEmpty()) return Optional.empty();
        var head = mailbox.queued().getFirst(); var context = ((MessageProtocol.Request)head.protocol()).context();
        return Optional.of(new Outbound(context.returnEndpoint(), new MessageEnvelope(id, nextSequence - 1,
            head.eventId(), head.ordinal(), reply.orElseThrow(), new MessageProtocol.Reply(context.correlation()))));
    }
    public DataValue.RecordValue value() {
        var admissions = new TreeMap<String, DataValue>(); writes.forEach((key, value) -> admissions.put(key, value.value()));
        return obj("version", 2, "id", id.toString(), "revision", revision, "sequence", nextSequence,
            "configuration", configuration.value(), "mailbox", MailboxArchiveCodec.encode(mailbox),
            "reply", reply.<DataValue>map(v -> v).orElse(DataValue.NullValue.INSTANCE), "writes", new DataValue.RecordValue(admissions));
    }
    public static LibraryServiceState decode(DataValue value) {
        if (!(value instanceof DataValue.RecordValue record)) throw new IllegalArgumentException("Expected service record");
        long version = number(record.field("version"));
        if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported Library service version");
        var root = version == 1 ? fields(value, "version", "id", "revision", "sequence", "configuration", "mailbox", "reply")
            : fields(value, "version", "id", "revision", "sequence", "configuration", "mailbox", "reply", "writes");
        var writes = new HashMap<String, LibraryWriteBatch.Admission>();
        if (version == 2) {
            if (!(root.field("writes") instanceof DataValue.RecordValue admissions)) throw new IllegalArgumentException("Expected write admissions");
            admissions.fields().forEach((key, admission) -> writes.put(key, LibraryWriteBatch.Admission.decode(admission)));
        }
        UUID id = UUID.fromString(text(root.field("id")));
        if (!id.toString().equalsIgnoreCase(text(root.field("id")))) throw new IllegalArgumentException("Noncanonical identity");
        var reply = root.field("reply");
        if (reply != DataValue.NullValue.INSTANCE && !(reply instanceof DataValue.RecordValue)) throw new IllegalArgumentException("Expected reply record");
        return new LibraryServiceState(id, number(root.field("revision")), number(root.field("sequence")),
            LibraryServiceConfiguration.decode(root.field("configuration")), MailboxArchiveCodec.decode(root.field("mailbox")),
            reply == DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of((DataValue.RecordValue)reply), writes);
    }
}
