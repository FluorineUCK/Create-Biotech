package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryWriteBatch;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LibraryReadHeadTest {
    private static final LibraryDriveLimits LIMITS = new LibraryDriveLimits(2, 3, 2, 2, 32, 32768,
        new ArchiveLimits(4096, 65536, 65536));
    private static final LibraryTrackAddress TRACK = new LibraryTrackAddress(2, 0, 1);
    private static DataValue.RecordValue obj(String name, DataValue value) { return new DataValue.RecordValue(Map.of(name, value)); }
    private static final class Source implements LibraryReadHead.Source {
        int reads, checks; boolean allowed = true; int medium = 1;
        LibraryMutation.Version entryVersion;
        DataValue value = new DataValue.IntegerValue(43);
        public void validateTrack(LibraryTrackAddress track) {
            if (!track.equals(TRACK)) throw new LibraryBookAccess.Failure("missing_track");
        }
        public LibraryReadHead.Sample read(LibraryTrackAddress track, int slot, String entry) {
            reads++; validateTrack(track); assertEquals(2, slot); assertEquals("reading", entry);
            if (!allowed) throw new LibraryBookAccess.Failure("denied");
            var content = entryVersion == null ? obj("value", value) : new DataValue.RecordValue(Map.of(
                "kind", new DataValue.TextValue("DATA"), "value", value, "version", entryVersion.value()));
            return new LibraryReadHead.Sample(obj("medium", new DataValue.IntegerValue(medium)), content);
        }
        public void verify(LibraryTrackAddress track, int slot, String entry, DataValue.RecordValue fence) {
            checks++; if (!allowed) throw new LibraryBookAccess.Failure("denied");
            if (!fence.equals(obj("medium", new DataValue.IntegerValue(medium)))) throw new LibraryBookAccess.Failure("changed");
        }
    }
    @Test void explicitPositioningCostsTicksAndBusyCommandsNeverReadOrMoveAnotherTrack() {
        var source = new Source(); var idle = LibraryReadHead.State.empty();
        assertEquals("not_positioned", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.read(idle, "reading", source, LIMITS)).code());
        var seeking = LibraryReadHead.seek(idle, TRACK, source, LIMITS);
        assertEquals(11, seeking.pending().orElseThrow().remainingTicks());
        assertEquals("busy", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.seek(seeking, TRACK, source, LIMITS)).code());
        var state = seeking;
        for (int tick = 0; tick < 10; tick++) state = LibraryReadHead.advance(state, source, LIMITS);
        assertTrue(state.track().isEmpty()); assertEquals(0, source.reads);
        state = LibraryReadHead.advance(state, source, LIMITS);
        assertEquals(TRACK, state.track().orElseThrow()); assertEquals(-1, state.slot());
        var positioned = LibraryReadHead.take(state, state.sequence(), LIMITS).state();
        assertEquals("not_selected", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.read(positioned, "reading", source, LIMITS)).code());
        state = LibraryReadHead.select(positioned, 2, source, LIMITS);
        state = LibraryReadHead.advance(LibraryReadHead.advance(state, source, LIMITS), source, LIMITS);
        state = LibraryReadHead.take(state, state.sequence(), LIMITS).state();
        state = LibraryReadHead.read(state, "reading", source, LIMITS);
        assertEquals(1, source.reads); assertTrue(state.result().isEmpty());
        long ticks = state.pending().orElseThrow().remainingTicks(); assertTrue(ticks > 2);
        for (long tick = 0; tick < ticks; tick++) state = LibraryReadHead.advance(state, source, LIMITS);
        var completed = LibraryReadHead.take(state, state.sequence(), LIMITS);
        assertEquals("read", completed.result().code());
        assertEquals(obj("value", new DataValue.IntegerValue(43)), completed.result().value());
        assertEquals(1, source.checks); assertTrue(completed.state().result().isEmpty());
    }

    @Test void checkpointKeepsRemainingWorkAndChangedMediaNeverReturnsTheFrozenValue() {
        var source = new Source();
        var state = LibraryReadHead.seek(LibraryReadHead.State.empty(), TRACK, source, LIMITS);
        state = LibraryReadHead.advance(state, source, LIMITS);
        state = LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(state, LIMITS), LIMITS);
        assertEquals(10, state.pending().orElseThrow().remainingTicks()); assertEquals(0, source.reads);
        state = finish(state, source); state = LibraryReadHead.take(state, state.sequence(), LIMITS).state();
        state = finish(LibraryReadHead.select(state, 2, source, LIMITS), source);
        state = LibraryReadHead.take(state, state.sequence(), LIMITS).state();
        state = LibraryReadHead.advance(LibraryReadHead.read(state, "reading", source, LIMITS), source, LIMITS);
        var saved = LibraryReadHeadCodec.encode(state, LIMITS);
        assertEquals(state, LibraryReadHeadCodec.decode(saved, LIMITS));
        source.medium++;
        state = finish(LibraryReadHeadCodec.decode(saved, LIMITS), source);
        assertEquals("changed", state.result().orElseThrow().code());
        assertSame(DataValue.NullValue.INSTANCE, state.result().orElseThrow().value());
        assertEquals(1, source.reads, "Resume does not resample another book");
        assertEquals(state, LibraryReadHead.advance(state, source, LIMITS), "Unclaimed completion is stable");
        var old = state.sequence(); state = LibraryReadHead.take(state, old, LIMITS).state();
        var next = LibraryReadHead.seek(state, TRACK, source, LIMITS);
        assertEquals("stale_operation", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.take(next, old, LIMITS)).code());
    }

    @Test void limitsAndRevokedGrantsFailWithoutDroppingTheOriginalCheckpoint() {
        var source = new Source();
        var state = finish(LibraryReadHead.seek(LibraryReadHead.State.empty(), TRACK, source, LIMITS), source);
        state = LibraryReadHead.take(state, state.sequence(), LIMITS).state();
        state = finish(LibraryReadHead.select(state, 2, source, LIMITS), source);
        var selected = LibraryReadHead.take(state, state.sequence(), LIMITS).state();
        var tiny = new LibraryDriveLimits(2, 3, 2, 2, 32, 1, LIMITS.archive());
        assertEquals("buffer_full", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.read(selected, "reading", source, tiny)).code());
        var reading = LibraryReadHead.read(selected, "reading", source, LIMITS);
        var saved = LibraryReadHeadCodec.encode(reading, LIMITS);
        assertThrows(LibraryBookAccess.Failure.class, () -> LibraryReadHeadCodec.decode(saved, tiny));
        assertEquals(reading, LibraryReadHeadCodec.decode(saved, LIMITS), "Restoring capacity recovers the same work");
        source.allowed = false;
        var failed = finish(reading, source);
        assertEquals("denied", failed.result().orElseThrow().code());
        assertSame(DataValue.NullValue.INSTANCE, failed.result().orElseThrow().value());
        assertThrows(IllegalArgumentException.class, () -> new LibraryReadHead.Pending(LibraryReadHead.Action.SEEK, TRACK, -1, "", 3, 2, java.util.Optional.empty()));
        var corrupt = saved.copy(); corrupt.putInt("Version", 1000);
        assertThrows(IllegalArgumentException.class, () -> LibraryReadHeadCodec.decode(corrupt, LIMITS));
    }

    @Test void encodedDataSizeAndDistanceCostTimeAndRateChangesDoNotRewritePendingWork() {
        var source = new Source();
        var state = finish(LibraryReadHead.seek(LibraryReadHead.State.empty(), TRACK, source, LIMITS), source);
        var positioned = LibraryReadHead.take(state, state.sequence(), LIMITS).state();
        assertEquals(2, LibraryReadHead.seek(positioned, TRACK, source, LIMITS).pending().orElseThrow().remainingTicks());
        state = finish(LibraryReadHead.select(positioned, 2, source, LIMITS), source);
        var selected = LibraryReadHead.take(state, state.sequence(), LIMITS).state();
        var small = LibraryReadHead.read(selected, "reading", source, LIMITS);
        source.value = new DataValue.TextValue("大记录".repeat(200));
        var large = LibraryReadHead.read(selected, "reading", source, LIMITS);
        assertTrue(large.pending().orElseThrow().totalTicks() > small.pending().orElseThrow().totalTicks());
        var fast = new LibraryDriveLimits(1, 0, 1, 1, Long.MAX_VALUE, LIMITS.bufferBytes(), LIMITS.archive());
        var restored = LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(large, LIMITS), fast);
        assertEquals(large, restored);
        assertEquals(large.pending().orElseThrow().remainingTicks() - 1,
            LibraryReadHead.advance(restored, source, fast).pending().orElseThrow().remainingTicks());
        var complete = finish(small, source);
        var checkpoint = LibraryReadHeadCodec.encode(complete, LIMITS);
        var restoredResult = LibraryReadHeadCodec.decode(checkpoint, LIMITS);
        assertEquals(complete, restoredResult);
        assertEquals("result_pending", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.read(restoredResult, "reading", source, LIMITS)).code());
        var taken = LibraryReadHead.take(restoredResult, restoredResult.sequence(), LIMITS);
        assertEquals("empty", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.take(taken.state(), taken.state().sequence(), LIMITS)).code());
        var overflow = new LibraryDriveLimits(Long.MAX_VALUE, Long.MAX_VALUE, 1, Long.MAX_VALUE, 1, 1024, LIMITS.archive());
        assertEquals("timing_overflow", assertThrows(LibraryBookAccess.Failure.class,
            () -> overflow.seekTicks(new LibraryTrackAddress(Integer.MIN_VALUE, 0, 0), new LibraryTrackAddress(Integer.MAX_VALUE, 0, 0))).code());
        assertEquals("timing_overflow", assertThrows(LibraryBookAccess.Failure.class, () -> overflow.readTicks(1)).code());
    }

    @Test void outputCanUseOnlyTheCompletedAndStillAuthorizedVersionOfItsAddressedRead() {
        var source = new Source();
        var version = new LibraryMutation.Version(UUID.randomUUID(), "reading", 1);
        source.entryVersion = version;
        var state = finish(LibraryReadHead.seek(LibraryReadHead.State.empty(), TRACK, source, LIMITS), source);
        state = LibraryReadHead.take(state, state.sequence(), LIMITS).state();
        state = finish(LibraryReadHead.select(state, 2, source, LIMITS), source);
        var selected = LibraryReadHead.take(state, state.sequence(), LIMITS).state();
        var pending = LibraryReadHead.beginReadTransfer(selected, version.entry(), source, LIMITS);
        assertEquals("busy", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.readyData(pending, source, pending.sequence(), version)).code());
        state = pending;
        for (int i = 0; i < 1000 && state.reading().filter(LibraryReadTransfer::ready).isEmpty(); i++)
            state = LibraryReadHead.prepareTick(state, source, LIMITS).next();
        var ready = state;
        assertTrue(ready.reading().orElseThrow().ready());
        assertEquals("stale_operation", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.readyData(ready, source, selected.sequence(), version)).code());
        var oldVersion = new LibraryMutation.Version(version.medium(), version.entry(), 0);
        assertEquals("stale_version", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.readyData(ready, source, ready.sequence(), oldVersion)).code());
        assertSame(source.value, LibraryReadHead.readyData(ready, source, ready.sequence(), version));
        assertEquals(version.value(), LibraryReadHead.readInfo(ready, source).field("version"),
            "Output observes the original result without consuming it");
        source.allowed = false;
        assertEquals("denied", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.readyData(ready, source, ready.sequence(), version)).code());
        source.allowed = true;
        source.medium++;
        assertEquals("changed", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.readyData(ready, source, ready.sequence(), version)).code());
        assertEquals(1, source.reads, "No output path performs a new read or chooses a replacement book");
    }

    @Test void addressedReadReferenceReportsIdentityAndVersionWithoutReturningOrConsumingTheData() {
        var source = new Source();
        var version = new LibraryMutation.Version(UUID.randomUUID(), "reading", 3);
        source.entryVersion = version;
        var target = new LibraryTrackAddress(-128, 64, 256).value();
        var device = UUID.randomUUID();
        var request = new DataValue.TextValue("speaker-read");
        var admission = new LibraryDriveCommand.Admission(device, new LibraryWriteBatch(UUID.randomUUID(), 1));
        var state = finish(LibraryReadHead.seek(LibraryReadHead.State.empty(), TRACK, source, LIMITS), source);
        state = LibraryReadHead.take(state, state.sequence(), LIMITS).state();
        state = finish(LibraryReadHead.select(state, 2, source, LIMITS), source);
        state = LibraryReadHead.take(state, state.sequence(), LIMITS).state();
        var pending = LibraryReadHead.beginReadTransfer(state, version.entry(), source, LIMITS);
        var command = LibraryDriveCommand.decode(new DataValue.RecordValue(Map.of(
            "kind", new DataValue.TextValue("library_command"), "target", target, "request", request,
            "operation", new DataValue.TextValue("read_ref"), "sequence", new DataValue.IntegerValue(pending.sequence()),
            "data", DataValue.NullValue.INSTANCE)));

        assertEquals("busy", assertThrows(LibraryBookAccess.Failure.class,
            () -> command.apply(pending, source, LIMITS, admission)).code());
        var ready = pending;
        for (int i = 0; i < 1000 && ready.reading().filter(LibraryReadTransfer::ready).isEmpty(); i++)
            ready = LibraryReadHead.prepareTick(ready, source, LIMITS).next();
        var completed = ready;
        var reply = command.apply(completed, source, LIMITS, admission);
        assertSame(completed, reply.state(), "A reference must not consume or reposition the current read");
        assertEquals(request, reply.reply().field("request"));
        assertEquals(new DataValue.TextValue("read_ref"), reply.reply().field("code"));
        assertEquals(new DataValue.RecordValue(Map.of("library", target, "device", new DataValue.TextValue(device.toString()),
            "sequence", new DataValue.IntegerValue(completed.sequence()), "version", version.value())), reply.reply().field("value"));
        assertEquals("unsupported_operation", assertThrows(LibraryBookAccess.Failure.class,
            () -> command.apply(completed, source, LIMITS)).code());
        assertEquals("empty", assertThrows(LibraryBookAccess.Failure.class,
            () -> command.apply(LibraryReadHead.closeReadTransfer(completed), source, LIMITS, admission)).code());
        source.allowed = false;
        assertEquals("denied", assertThrows(LibraryBookAccess.Failure.class,
            () -> command.apply(completed, source, LIMITS, admission)).code());
        assertEquals(1, source.reads);
    }

    private static LibraryReadHead.State finish(LibraryReadHead.State state, Source source) {
        while (state.pending().isPresent()) state = LibraryReadHead.advance(state, source, LIMITS);
        return state;
    }
}
