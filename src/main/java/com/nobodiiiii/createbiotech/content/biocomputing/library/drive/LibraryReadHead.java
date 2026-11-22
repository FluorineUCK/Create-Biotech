package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.google.common.io.CountingOutputStream;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.io.*;
import java.util.*;
import net.minecraft.nbt.NbtIo;

/** Immutable local device transitions. The host owns publication, authorization and the activity clock.
 * No wall clock, volume search, instruction scheduling, message buffering or world object is retained. */
public final class LibraryReadHead {
    private LibraryReadHead() {}
    public interface Source {
        /** Geometry/availability only: must not inspect book contents or load chunks. */
        void validateTrack(LibraryTrackAddress track);
        /** Only the positioned physical slot; the source must enforce the live volume grant. */
        Sample read(LibraryTrackAddress track, int slot, String entry);
        /** Same slow, positioned read, including a version token for an absent entry. */
        default Sample recipePage(LibraryTrackAddress track, int slot, int page) { throw failure("unsupported_operation"); }
        default void verifyRecipePage(LibraryTrackAddress track, int slot, int page, DataValue.RecordValue fence) {
            throw failure("unsupported_operation");
        }
        default Sample stat(LibraryTrackAddress track, int slot, String entry) { throw failure("unsupported_operation"); }
        default Sample list(LibraryTrackAddress track, int slot, LibraryDirectoryQuery query) { throw failure("unsupported_operation"); }
        default LibraryBookAccess.PreparedWrite prepareWrite(LibraryTrackAddress track, int slot, LibraryDeviceWrite operation) { throw failure("unsupported_operation"); }
        default void releaseWrite(LibraryTrackAddress track, int slot, LibraryDeviceWrite operation) { throw failure("unsupported_operation"); }
        default void verifyTransfer(LibraryTrackAddress track, int slot, LibraryWriteTransfer transfer) { throw failure("unsupported_operation"); }
        /** Recheck the same physical medium and grant before completing an in-flight read. */
        void verify(LibraryTrackAddress track, int slot, String entry, DataValue.RecordValue fence);
    }
    public record Sample(DataValue.RecordValue fence, DataValue.RecordValue value) {
        public Sample { Objects.requireNonNull(fence); Objects.requireNonNull(value); }
        DataValue.RecordValue valueRecord() { return object("fence", fence, "value", value); }
    }
    public enum Action { SEEK, SELECT, READ, STAT, LIST, RECIPE_PAGE;
        boolean queriesMedium() { return this == READ || this == STAT || this == LIST || this == RECIPE_PAGE; }
    }
    public record Pending(Action action, LibraryTrackAddress target, int slot, String entry,
        long remainingTicks, long totalTicks, Optional<Sample> sample) {
        public Pending {
            Objects.requireNonNull(action); Objects.requireNonNull(target); Objects.requireNonNull(entry); Objects.requireNonNull(sample);
            if (remainingTicks < 1 || remainingTicks > totalTicks) throw new IllegalArgumentException("Invalid remaining mechanical time");
            if (action == Action.SEEK ? slot != -1 : slot < 0 || slot >= 6) throw new IllegalArgumentException("Invalid physical slot");
            if (action == Action.RECIPE_PAGE) {
                int page;
                try { page = Integer.parseInt(entry); } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid physical page"); }
                if (page < 0 || page >= net.minecraft.world.item.component.WritableBookContent.MAX_PAGES || !entry.equals(Integer.toString(page)))
                    throw new IllegalArgumentException("Invalid physical page");
            } else if (action == Action.READ || action == Action.STAT) name(entry); else if (!entry.isEmpty()) throw new IllegalArgumentException("Unexpected entry");
            if (action.queriesMedium() != sample.isPresent()) throw new IllegalArgumentException("Unexpected read snapshot");
        }
        Pending elapsed() { return new Pending(action, target, slot, entry, remainingTicks - 1, totalTicks, sample); }
    }
    public record Result(String code, DataValue value) {
        public Result {
            Objects.requireNonNull(code); Objects.requireNonNull(value);
            if (code.isEmpty() || code.length() > 64 || !code.matches("[a-z_]+")) throw new IllegalArgumentException("Invalid result code");
            if (Set.of("read", "stat", "list", "written", "recipe_page").contains(code) ? !(value instanceof DataValue.RecordValue) : value != DataValue.NullValue.INSTANCE)
                throw new IllegalArgumentException("Only a completed query/write may contain data");
        }
    }
    public record State(long sequence, Optional<LibraryTrackAddress> track, int slot, Optional<Pending> pending, Optional<Result> result,
        Optional<LibraryWriteWork> writing, Optional<LibraryWriteTransfer> transfer, Optional<LibraryReadTransfer> reading) {
        public State(long sequence, Optional<LibraryTrackAddress> track, int slot, Optional<Pending> pending, Optional<Result> result,
            Optional<LibraryWriteWork> writing, Optional<LibraryWriteTransfer> transfer) {
            this(sequence,track,slot,pending,result,writing,transfer,Optional.empty());
        }
        public State(long sequence, Optional<LibraryTrackAddress> track, int slot, Optional<Pending> pending, Optional<Result> result,
            Optional<LibraryWriteWork> writing) {
            this(sequence, track, slot, pending, result, writing, Optional.empty());
        }
        public State(long sequence, Optional<LibraryTrackAddress> track, int slot, Optional<Pending> pending, Optional<Result> result) {
            this(sequence, track, slot, pending, result, Optional.empty());
        }
        public State {
            Objects.requireNonNull(track); Objects.requireNonNull(pending); Objects.requireNonNull(result); Objects.requireNonNull(writing);
            Objects.requireNonNull(transfer); Objects.requireNonNull(reading);
            if (sequence < 0 || slot < -1 || slot >= 6 || track.isEmpty() && slot != -1 || pending.isPresent() && result.isPresent())
                throw new IllegalArgumentException("Invalid read head state");
            if (sequence == 0 && (track.isPresent() || pending.isPresent() || result.isPresent())) throw new IllegalArgumentException("Unstarted head has activity");
            pending.ifPresent(operation -> {
                if (operation.action() != Action.SEEK && !track.filter(operation.target()::equals).isPresent()
                    || operation.action().queriesMedium() && slot != operation.slot()) throw new IllegalArgumentException("Operation is not at the current position");
            });
            if (writing.isPresent()) {
                var work = writing.orElseThrow();
                if (track.isEmpty() || slot < 0 || pending.isPresent() || sequence != work.operation().sequence()
                    || result.isPresent() && (!result.orElseThrow().code().equals("written") || !work.committing()))
                    throw new IllegalArgumentException("Write is not at the selected position");
                result.ifPresent(completed -> LibraryWriteReceipt.validate(work.operation().mutation(),
                    object("code", new DataValue.TextValue("ok"), "data", completed.value())));
            } else if (result.filter(r -> r.code().equals("written")).isPresent()) throw new IllegalArgumentException("Missing write receipt context");
            if (transfer.isPresent() && (track.isEmpty() || slot < 0 || pending.isPresent() || result.isPresent()
                || writing.isPresent() || sequence != transfer.orElseThrow().sequence()))
                throw new IllegalArgumentException("Upload is not at an idle selected position");
            if(reading.isPresent() && (sequence<1 || track.isEmpty() || slot<0 || pending.isPresent() || result.isPresent()
                || writing.isPresent() || transfer.isPresent())) throw new IllegalArgumentException("Read snapshot is not at an idle selected position");
        }
        public static State empty() { return new State(0, Optional.empty(), -1, Optional.empty(), Optional.empty()); }
    }
    public record Taken(State state, Result result) {}

    public static State beginTransfer(State before, DataValue header, UUID device, Source source, LibraryDriveLimits limits) {
        ready(before); var track = positioned(before);
        if (before.slot() < 0) throw failure("not_selected");
        if (before.sequence() == Long.MAX_VALUE) throw failure("sequence_exhausted");
        var transfer = LibraryWriteTransfer.begin(device, before.sequence() + 1, header);
        var next = new State(transfer.sequence(), before.track(), before.slot(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(transfer));
        validateBuffer(next, limits);
        source.validateTrack(track);
        source.verifyTransfer(track, before.slot(), transfer);
        return next;
    }
    /** Cancels only staging. The host still checks controller authority; missing media must not trap the buffer. */
    public static State abortTransfer(State before, UUID device) {
        var transfer = before.transfer().orElseThrow(() -> failure("empty"));
        if (!device.equals(transfer.device())) throw failure("device_changed");
        return new State(before.sequence(), before.track(), before.slot(), Optional.empty(), Optional.empty());
    }
    public static State appendTransfer(State before, DataValue data, UUID device, Source source, LibraryDriveLimits limits) {
        var transfer = before.transfer().orElseThrow(() -> failure("empty"));
        if (!device.equals(transfer.device())) throw failure("device_changed");
        var candidate = transfer.append(data, limits);
        var next = withTransfer(before, candidate);
        validateBuffer(next, limits);
        source.validateTrack(before.track().orElseThrow());
        source.verifyTransfer(before.track().orElseThrow(), before.slot(), candidate);
        return next;
    }
    public static State commitTransfer(State before, UUID device, LibraryWriteBatch batch, Source source, LibraryDriveLimits limits) {
        var transfer=before.transfer().orElseThrow(() -> failure("empty"));
        if(!device.equals(transfer.device())) throw failure("device_changed");
        if(transfer.remaining()>0 || transfer.commit().isPresent()) throw failure("busy");
        if(transfer.received()!=transfer.length()) throw failure("length_mismatch");
        source.validateTrack(before.track().orElseThrow());
        source.verifyTransfer(before.track().orElseThrow(),before.slot(),transfer);
        var probe=LibraryUploadCommit.start(batch,Math.toIntExact(transfer.length()),1,limits.archive());
        // Reserve worst-case verdict metadata and one pool charging BOTH parser and live values.
        long free=limits.bufferBytes()-encodedBytes(transfer.withCommit(probe.seal(true)).value(),limits);
        if(free<1) throw failure("buffer_full");
        var work=LibraryUploadCommit.start(batch,Math.toIntExact(transfer.length()),free,limits.archive());
        var next=withTransfer(before,transfer.withCommit(work)); validateBuffer(next,limits); return next;
    }
    public static State sealTransfer(State before, boolean conflict) {
        var transfer=before.transfer().orElseThrow();
        return withTransfer(before,transfer.withCommit(transfer.commit().orElseThrow().seal(conflict)));
    }
    private static State withTransfer(State before, LibraryWriteTransfer transfer) {
        return new State(before.sequence(), before.track(), before.slot(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(transfer));
    }
    public static State write(State before, LibraryMutation mutation, UUID device, LibraryWriteBatch batch, Source source, LibraryDriveLimits limits) {
        ready(before); var track = positioned(before);
        if (before.slot() < 0) throw failure("not_selected");
        if (before.sequence() == Long.MAX_VALUE) throw failure("sequence_exhausted");
        source.validateTrack(track);
        var operation = new LibraryDeviceWrite(device, before.sequence() + 1, mutation);
        source.prepareWrite(track, before.slot(), operation); // Admission validation only; never commit this candidate.
        long ticks = limits.readTicks(encodedBytes(mutation.value(), limits));
        var work = new LibraryWriteWork(operation, new LibraryWriteBatch.Admission(batch, ""), ticks, ticks, false);
        var next = withWrite(before, work); validateBuffer(next, limits); return next;
    }
    public static State withWrite(State before, LibraryWriteWork work) {
        return new State(work.operation().sequence(), before.track(), before.slot(), Optional.empty(), Optional.empty(), Optional.of(work));
    }
    public static boolean busy(State state) { return state.reading().isPresent() || state.transfer().isPresent() || state.pending().isPresent() || state.writing().isPresent() && state.result().isEmpty(); }
    /** Encode next AND recovery first; publish recovery before commit, next only after commit succeeds. */
    public record PreparedTick(State next, Optional<State> recovery, Runnable commit) {
        public PreparedTick { Objects.requireNonNull(next); Objects.requireNonNull(recovery); Objects.requireNonNull(commit); }
    }
    public static PreparedTick prepareTick(State before, Source source, LibraryDriveLimits limits) {
        validateBuffer(before, limits);
        if(before.reading().isPresent()) {
            var reading=before.reading().orElseThrow(); if(reading.ready()) return unchanged(before);
            try {
                var next=reading.advance(limits.codecWorkPerTick());
                if(reading.remaining()==1 || next.ready()) verifyRead(before,source);
                var after=withReading(before,next); validateBuffer(after,limits); return unchanged(after);
            } catch(RuntimeException invalid) {return unchanged(writeFailed(before,LibraryWriteService.failure(invalid)));}
        }
        if (before.transfer().isPresent()) {
            var transfer = before.transfer().orElseThrow();
            if(transfer.commit().isPresent()) return unchanged(decodeTransfer(before,source,limits));
            return unchanged(transfer.remaining() == 0 ? before : withTransfer(before, transfer.elapsed()));
        }
        if (before.writing().isEmpty() || before.result().isPresent()) return unchanged(advance(before, source, limits));
        var work = before.writing().orElseThrow().elapsed();
        var waiting = withWrite(before, work);
        if (work.remaining() > 0 || work.admission().verdict().isEmpty()) return unchanged(waiting);
        if (work.admission().verdict().equals("conflict")) return unchanged(writeFailed(before, "conflict"));
        LibraryBookAccess.PreparedWrite prepared;
        try {
            source.validateTrack(before.track().orElseThrow());
            prepared = source.prepareWrite(before.track().orElseThrow(), before.slot(), work.operation());
        } catch (RuntimeException failure) {
            String code = LibraryWriteService.failure(failure);
            // A previous callback may have failed AFTER replacing the book. Never turn uncertainty into a false failure reply.
            if (work.committing()) throw failure("commit_recovery_" + code);
            return unchanged(writeFailed(before, code));
        }
        var recovery = withWrite(before, work.commitStarted());
        var next = new State(before.sequence(), before.track(), before.slot(), Optional.empty(),
            Optional.of(new Result("written", prepared.reply().field("data"))), recovery.writing());
        validateBuffer(recovery, limits); validateBuffer(next, limits);
        return new PreparedTick(next, Optional.of(recovery), prepared.commit());
    }
    private static PreparedTick unchanged(State state) { return new PreparedTick(state, Optional.empty(), () -> {}); }
    private static State decodeTransfer(State before, Source source, LibraryDriveLimits limits) {
        var transfer=before.transfer().orElseThrow(); var commit=transfer.commit().orElseThrow();
        if(commit.admission().verdict().equals("conflict")) return writeFailed(before,"conflict");
        try {
            var advanced=commit.advance(transfer.input(),limits.codecWorkPerTick());
            if(!advanced.done()) return withTransfer(before,transfer.withCommit(advanced));
            var decoded=advanced.decoded();
            if(decoded.error()!=null) return writeFailed(before,decoded.error());
            if(!(decoded.value() instanceof DataValue.RecordValue envelope) || !envelope.fields().keySet().equals(Set.of("value")))
                return writeFailed(before,"invalid_contents");
            LibraryEntry entry;
            try { entry=new LibraryEntry(transfer.kind(),envelope.field("value")); }
            catch(IllegalArgumentException invalid) { return writeFailed(before,"invalid_contents"); }
            var mutation=new LibraryMutation(transfer.volume(),transfer.entry(),transfer.expected(),Optional.of(entry));
            var operation=new LibraryDeviceWrite(transfer.device(),transfer.sequence(),mutation);
            source.validateTrack(before.track().orElseThrow());
            source.prepareWrite(before.track().orElseThrow(),before.slot(),operation);
            long ticks=limits.readTicks(encodedBytes(mutation.value(),limits));
            var next=withWrite(before,new LibraryWriteWork(operation,advanced.admission(),ticks,ticks,false));
            validateBuffer(next,limits); return next;
        } catch(RuntimeException invalid) { return writeFailed(before,LibraryWriteService.failure(invalid)); }
    }
    private static State writeFailed(State before, String code) {
        return new State(before.sequence(), before.track(), before.slot(), Optional.empty(), Optional.of(emptyResult(code)));
    }

    public static State seek(State before, LibraryTrackAddress target, Source source, LibraryDriveLimits limits) {
        ready(before); source.validateTrack(target);
        long ticks = limits.seekTicks(before.track().orElse(new LibraryTrackAddress(0, 0, 0)), target);
        return start(before, new Pending(Action.SEEK, target, -1, "", ticks, ticks, Optional.empty()), limits);
    }
    public static State select(State before, int slot, Source source, LibraryDriveLimits limits) {
        ready(before); var track = positioned(before);
        if (slot < 0 || slot >= 6) throw failure("invalid_slot");
        source.validateTrack(track);
        return start(before, new Pending(Action.SELECT, track, slot, "", limits.selectTicks(), limits.selectTicks(), Optional.empty()), limits);
    }
    public static State read(State before, String entry, Source source, LibraryDriveLimits limits) {
        return query(before, entry, source, limits, Action.READ);
    }
    public static State beginReadTransfer(State before,String entry,Source source,LibraryDriveLimits limits) {
        ready(before); var track=positioned(before); name(entry);
        if(before.slot()<0) throw failure("not_selected");
        if(before.sequence()==Long.MAX_VALUE) throw failure("sequence_exhausted");
        source.validateTrack(track);
        var read=LibraryReadTransfer.begin(entry,source.read(track,before.slot(),entry),limits);
        return startReading(before,read,limits);
    }
    /** Same physical head and bounded encoder as entry reads; ROM pages have separate authority and no writable version. */
    public static State beginRecipePageTransfer(State before,int page,Source source,LibraryDriveLimits limits) {
        ready(before); var track=positioned(before); var target=new LibraryReadTransfer.Page(page);
        if(before.slot()<0) throw failure("not_selected");
        if(before.sequence()==Long.MAX_VALUE) throw failure("sequence_exhausted");
        source.validateTrack(track);
        return startReading(before,LibraryReadTransfer.begin(target,source.recipePage(track,before.slot(),page),limits),limits);
    }
    private static State startReading(State before,LibraryReadTransfer read,LibraryDriveLimits limits) {
        var next=withReading(new State(before.sequence()+1,before.track(),before.slot(),Optional.empty(),Optional.empty()),read);
        validateBuffer(next,limits); return next;
    }
    private static State withReading(State before,LibraryReadTransfer reading) {
        return new State(before.sequence(),before.track(),before.slot(),Optional.empty(),Optional.empty(),
            Optional.empty(),Optional.empty(),Optional.of(reading));
    }
    private static LibraryReadTransfer verifyRead(State before,Source source) {
        var reading=before.reading().orElseThrow(() -> failure("empty"));
        source.validateTrack(before.track().orElseThrow());
        switch(reading.target()) {
            case LibraryReadTransfer.Entry e -> source.verify(before.track().orElseThrow(),before.slot(),e.name(),reading.sample().fence());
            case LibraryReadTransfer.Page p -> source.verifyRecipePage(before.track().orElseThrow(),before.slot(),p.index(),reading.sample().fence());
        }
        return reading;
    }
    public static DataValue.RecordValue readInfo(State before,Source source) {
        var reading=before.reading().orElseThrow(() -> failure("empty"));
        if(!reading.ready()) throw failure("busy");
        return verifyRead(before,source).metadata();
    }
    public static DataValue.RecordValue readChunk(State before,long offset,long count,Source source) {
        var reading=before.reading().orElseThrow(() -> failure("empty"));
        if(!reading.ready()) throw failure("busy");
        return verifyRead(before,source).chunk(offset,count);
    }
    /** Trusted output adapter only. Observe the same completed, live-fenced snapshot without acknowledging it. */
    static DataValue readyData(State before, Source source, long sequence, LibraryMutation.Version expected) {
        if (sequence != before.sequence()) throw failure("stale_operation");
        var reading = before.reading().orElseThrow(() -> failure("empty"));
        if (!reading.ready()) throw failure("busy");
        if (!(reading.target() instanceof LibraryReadTransfer.Entry named)) throw failure("invalid_entry_kind");
        if (!named.name().equals(expected.entry())) throw failure("stale_version");
        var entry = verifyRead(before, source).sample().value();
        if (!expected.value().equals(entry.field("version"))) throw failure("stale_version");
        if (!new DataValue.TextValue(LibraryEntry.Kind.DATA.name()).equals(entry.field("kind"))) throw failure("invalid_entry_kind");
        return entry.field("value");
    }
    /** Release only this device's read snapshot; missing media or revoked volume grants cannot trap it. */
    public static State closeReadTransfer(State before) {
        if(before.reading().isEmpty()) throw failure("empty");
        return new State(before.sequence(),before.track(),before.slot(),Optional.empty(),Optional.empty());
    }
    public static State stat(State before, String entry, Source source, LibraryDriveLimits limits) {
        return query(before, entry, source, limits, Action.STAT);
    }
    public static State recipePage(State before, int page, Source source, LibraryDriveLimits limits) {
        ready(before); var track = positioned(before);
        if (page < 0 || page >= net.minecraft.world.item.component.WritableBookContent.MAX_PAGES) throw failure("invalid_page");
        if (before.slot() < 0) throw failure("not_selected");
        source.validateTrack(track);
        return sampled(before, Integer.toString(page), source.recipePage(track, before.slot(), page), limits, Action.RECIPE_PAGE);
    }
    public static State list(State before, LibraryDirectoryQuery query, Source source, LibraryDriveLimits limits) {
        ready(before); var track = positioned(before);
        if (before.slot() < 0) throw failure("not_selected");
        source.validateTrack(track);
        return sampled(before, "", source.list(track, before.slot(), query), limits, Action.LIST);
    }
    private static State query(State before, String entry, Source source, LibraryDriveLimits limits, Action action) {
        ready(before); var track = positioned(before); name(entry);
        if (before.slot() < 0) throw failure("not_selected");
        source.validateTrack(track);
        // Nothing is sampled until both positioning operations have actually completed.
        var sample = action == Action.STAT ? source.stat(track, before.slot(), entry) : source.read(track, before.slot(), entry);
        return sampled(before, entry, sample, limits, action);
    }
    private static State sampled(State before, String entry, Sample sample, LibraryDriveLimits limits, Action action) {
        if (encodedBytes(sample.valueRecord(), limits) > limits.bufferBytes()) throw failure("buffer_full");
        long ticks = limits.readTicks(encodedBytes(sample.value(), limits));
        return start(before, new Pending(action, before.track().orElseThrow(), before.slot(), entry, ticks, ticks, Optional.of(sample)), limits);
    }
    /** Exactly one device activity tick. Not calling this preserves all remaining work without catch-up. */
    public static State advance(State before, Source source, LibraryDriveLimits limits) {
        validateBuffer(before, limits);
        if (before.writing().isPresent() && before.result().isEmpty()) throw failure("write_requires_commit_host");
        if (before.pending().isEmpty()) return before;
        var work = before.pending().orElseThrow();
        if (work.remainingTicks() > 1) return new State(before.sequence(), before.track(), before.slot(), Optional.of(work.elapsed()), Optional.empty());
        var track = before.track(); int slot = before.slot(); Result result;
        try {
            source.validateTrack(work.target());
            switch (work.action()) {
                case SEEK -> { track = Optional.of(work.target()); slot = -1; result = emptyResult("positioned"); }
                case SELECT -> { slot = work.slot(); result = emptyResult("selected"); }
                case RECIPE_PAGE -> {
                    var sample = work.sample().orElseThrow();
                    source.verifyRecipePage(work.target(), work.slot(), Integer.parseInt(work.entry()), sample.fence());
                    result = new Result("recipe_page", sample.value());
                }
                case READ, STAT, LIST -> {
                    var sample = work.sample().orElseThrow();
                    source.verify(work.target(), work.slot(), work.entry(), sample.fence());
                    result = new Result(work.action().name().toLowerCase(Locale.ROOT), sample.value());
                }
                default -> throw new IllegalStateException();
            }
        } catch (LibraryBookAccess.Failure error) { result = emptyResult(error.code()); }
        catch (ArchiveException invalid) { result = emptyResult("invalid_contents"); }
        catch (RuntimeException provider) { result = emptyResult("provider_failure"); }
        return new State(before.sequence(), track, slot, Optional.empty(), Optional.of(result));
    }
    /** The host pairs this immutable candidate with the caller's durable continuation before publication. */
    public static Taken take(State before, long sequence, LibraryDriveLimits limits) {
        validateBuffer(before, limits);
        if (sequence != before.sequence()) throw failure("stale_operation");
        if (busy(before)) throw failure("busy");
        var result = before.result().orElseThrow(() -> failure("empty"));
        return new Taken(new State(before.sequence(), before.track(), before.slot(), Optional.empty(), Optional.empty()), result);
    }
    private static State start(State before, Pending work, LibraryDriveLimits limits) {
        if (before.sequence() == Long.MAX_VALUE) throw failure("sequence_exhausted");
        var after = new State(before.sequence() + 1, before.track(), before.slot(), Optional.of(work), Optional.empty());
        validateBuffer(after, limits); return after;
    }
    private static void ready(State state) {
        if (busy(state)) throw failure("busy");
        if (state.result().isPresent()) throw failure("result_pending");
    }
    private static LibraryTrackAddress positioned(State state) { return state.track().orElseThrow(() -> failure("not_positioned")); }
    static void name(String name) {
        if (name == null || name.isBlank() || name.length() > LibraryBookVolumes.MAX_NAME) throw failure("invalid_entry");
    }
    static void validateBuffer(State state, LibraryDriveLimits limits) {
        state.reading().ifPresent(read -> {
            if(read.reservedBytes()>limits.bufferBytes() || encodedBytes(read.value(),limits)>limits.bufferBytes()-read.reservedBytes())
                throw failure("buffer_full");
        });
        state.transfer().ifPresent(transfer -> {
            long reserved=Math.addExact(transfer.unfilledArchiveBytes(),transfer.commit().map(LibraryUploadCommit::reservedBytes).orElse(0L));
            if (reserved > limits.bufferBytes()
                || encodedBytes(transfer.value(), limits) > limits.bufferBytes() - reserved) throw failure("buffer_full");
        });
        state.pending().flatMap(Pending::sample).ifPresent(sample -> {
            if (encodedBytes(sample.valueRecord(), limits) > limits.bufferBytes()) throw failure("buffer_full");
        });
        state.result().filter(result -> result.value() instanceof DataValue.RecordValue).ifPresent(result -> {
            if (encodedBytes(result.value(), limits) > limits.bufferBytes()) throw failure("buffer_full");
        });
        state.writing().ifPresent(work -> {
            long bytes = encodedBytes(work.value(), limits) + state.result().map(r -> encodedBytes(r.value(), limits)).orElse(0L);
            if (bytes > limits.bufferBytes()) throw failure("buffer_full");
        });
    }
    static long encodedBytes(DataValue value, LibraryDriveLimits limits) {
        var output = new CountingOutputStream(OutputStream.nullOutputStream());
        try { NbtIo.write(ValueNbtCodec.encode(value, limits.archive()), new DataOutputStream(output)); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
        return output.getCount();
    }
    static DataValue.RecordValue object(String first, DataValue one, String second, DataValue two) {
        return new DataValue.RecordValue(Map.of(first, one, second, two));
    }
    private static Result emptyResult(String code) { return new Result(code, DataValue.NullValue.INSTANCE); }
    private static LibraryBookAccess.Failure failure(String code) { return new LibraryBookAccess.Failure(code); }
}
