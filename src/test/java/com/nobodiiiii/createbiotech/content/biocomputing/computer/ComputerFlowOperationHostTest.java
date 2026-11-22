package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerXBusExchangeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ComputerFlowOperationHostTest {
    static final long OPERATION = (1L << 32) + 17;

    @Test void independentOperationReachesBothReceiptsAndRepairsOnlyTheMatchingOldWait() {
        for (boolean savedWriter : List.of(true, false)) {
            var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
            start(writer, writer(), INPUTS); start(reader, reader(), EMPTY);
            writer = atOperation(writer, OPERATION); reader = atOperation(reader, OPERATION + 7);
            var oldWriter = writer.write(); var oldReader = reader.write();
            assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
            var receipt = writer.flowSnapshot().orElseThrow().receipts().getFirst();
            assertEquals(new DataValue.IntegerValue(OPERATION), receipt.writer().value().field("operation"));
            assertEquals(new DataValue.IntegerValue(OPERATION + 7), receipt.reader().value().field("operation"));
            var left = restore(savedWriter ? writer.write() : oldWriter);
            var right = restore(savedWriter ? oldReader : reader.write());
            var advanced = savedWriter ? left.write() : right.write();
            assertEquals(XBusExchange.Status.EXCHANGED, exchange(left, right));
            assertEquals(advanced, savedWriter ? left.write() : right.write());
            assertEquals(writer.flowSnapshot(), left.flowSnapshot()); assertEquals(reader.flowSnapshot(), right.flowSnapshot());
            assertEquals(XBusExchange.Status.NOT_READY, exchange(left, right));
        }
    }

    @Test void repeatedSourcePositionKeepsTwoReceiptsAndRepairsOnlyTheSecondOperation() {
        for (boolean savedWriter : List.of(true, false)) {
            var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
            start(writer, writer(), INPUTS); start(reader, reader(), EMPTY);
            writer = atOperation(writer, OPERATION); reader = atOperation(reader, OPERATION + 7);
            assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
            var secondInput = new DataValue.RecordValue(Map.of("packet", new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(34)))));
            writer = reenter(writer, OPERATION + 2, FlowRuntime.Status.WAITING_WRITE, secondInput);
            reader = reenter(reader, OPERATION + 9, FlowRuntime.Status.WAITING_READ, EMPTY);
            var oldWriter = writer.write(); var oldReader = reader.write();
            assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
            var receipts = writer.flowSnapshot().orElseThrow().receipts();
            assertEquals(2, receipts.size());
            assertEquals(receipts.getFirst().writer().cursor(), receipts.getLast().writer().cursor());
            assertEquals(OPERATION, receipts.getFirst().writer().operation());
            assertEquals(OPERATION + 2, receipts.getLast().writer().operation());
            var left = restore(savedWriter ? writer.write() : oldWriter);
            var right = restore(savedWriter ? oldReader : reader.write());
            var advanced = savedWriter ? left.write() : right.write();
            assertEquals(XBusExchange.Status.EXCHANGED, exchange(left, right));
            assertEquals(advanced, savedWriter ? left.write() : right.write());
            assertEquals(writer.flowSnapshot(), left.flowSnapshot()); assertEquals(reader.flowSnapshot(), right.flowSnapshot());
            assertEquals(secondInput, right.flowSnapshot().orElseThrow().state().locals(), "The old first payload must not replace the second result");
            assertEquals(XBusExchange.Status.NOT_READY, exchange(left, right));
        }
    }

    @Test void wideRetirementSurvivesEventModeAndCannotBeDowngradedOrTruncated() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS); start(reader, reader(), EMPTY);
        writer = atOperation(writer, OPERATION); reader = atOperation(reader, OPERATION + 7);
        var oldReader = reader.write();
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
        writer.reset(LIMITS, true);
        var control = writer.flowSnapshot().orElseThrow().control();
        assertEquals(OPERATION + 7, control.peers().values().iterator().next().retiredThrough());
        var raw = (CompoundTag)writer.write(); assertEquals(4, raw.getInt(FlowBlockNbtCodec.MARKER));
        raw.putInt(FlowBlockNbtCodec.MARKER, 3); assertOpaque(raw);
        writer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        raw = (CompoundTag)writer.write(); assertEquals(2, raw.getInt(EventHostNbtCodec.MARKER));
        assertEquals(control, EventHostNbtCodec.decode(raw, LIMITS.archive()).control());
        var restored = restore(raw);
        raw.putInt(EventHostNbtCodec.MARKER, 1); assertOpaque(raw);
        restored.installFlow(writer(), INPUTS, LIMITS, true);
        restored.setPaused(false, LIMITS, true); restored.tick(LIMITS, true);
        var before = restored.write(); var stale = restore(oldReader);
        assertEquals(XBusExchange.Status.RETIRED, exchange(restored, stale));
        assertEquals(before, restored.write()); assertEquals(oldReader, stale.write());
    }

    @Test void oldCapturedAccessCannotBindToANewerOperationAtTheSameSourcePosition() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS); start(reader, reader(), EMPTY);
        writer = atOperation(writer, OPERATION); reader = atOperation(reader, OPERATION);
        var captured = access(writer, "x0"); var receiving = access(reader, "x1");
        writer.read(atOperation(writer, OPERATION + 1).write()); writer.tick(LIMITS, true);
        var left = writer.write(); var right = reader.write();
        assertEquals(XBusExchange.Status.STALE, captured.exchange(receiving));
        assertEquals(left, writer.write()); assertEquals(right, reader.write());
        assertEquals(XBusExchange.Status.EXCHANGED, access(writer, "x0").exchange(receiving));
    }

    @Test void sameOperationAtWrongSourcePositionIsHistoryConflictRatherThanAReplay() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("one", "x1", "first", PACKET),
            new FlowProgram.Read("two", "x1", "second", PACKET)));
        start(writer, writer(), INPUTS); start(reader, program, EMPTY);
        writer = atOperation(writer, OPERATION); reader = atOperation(reader, OPERATION);
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
        reader.tick(LIMITS, true);
        var saved = reader.flowSnapshot().orElseThrow(); var s = saved.state();
        var corrupt = new FlowRuntime.Checkpoint(s.computer(), s.flow(), s.cursor(), s.status(), s.locals(), s.work(),
            s.paused(), s.expression(), s.diagnostic(), s.sleepTicks(), s.signals(), OPERATION);
        reader = restore(FlowBlockNbtCodec.encode(new FlowBlockHost.Saved(saved.program(), saved.initialInputs(), corrupt,
            List.of(), saved.control()), LIMITS.archive()));
        assertTrue(reader.loaded()); var left = writer.write(); var right = reader.write();
        assertEquals(XBusExchange.Status.HISTORY_CONFLICT, exchange(writer, reader));
        assertEquals(left, writer.write()); assertEquals(right, reader.write());
    }

    @Test void operationExhaustionRejectsTheWholePair() {
        for (boolean exhaustedWriter : List.of(true, false)) {
            var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
            start(writer, writer(), INPUTS); start(reader, reader(), EMPTY);
            writer = atOperation(writer, exhaustedWriter ? Long.MAX_VALUE : OPERATION);
            reader = atOperation(reader, exhaustedWriter ? OPERATION : Long.MAX_VALUE);
            var left = writer.write(); var right = reader.write();
            assertEquals(XBusExchange.Status.ARITHMETIC_OVERFLOW, exchange(writer, reader));
            assertEquals(left, writer.write()); assertEquals(right, reader.write());
            assertTrue(writer.loaded()); assertTrue(reader.loaded());
        }
    }

    @Test void readinessAdvancesOnlyTheReaderAndDoesNotCreateAReceipt() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS);
        start(reader, new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("ready", "x1"))), EMPTY);
        writer = atOperation(writer, OPERATION); reader = atOperation(reader, OPERATION + 3);
        var left = writer.write();
        assertEquals(XBusExchange.Status.READABLE, exchange(writer, reader));
        assertEquals(left, writer.write());
        assertEquals(OPERATION + 4, reader.flowSnapshot().orElseThrow().state().operation());
        assertTrue(reader.flowSnapshot().orElseThrow().receipts().isEmpty());
    }

    @Test void operationMetadataAndReceiptsRemainSubjectToMemoryAndArchiveBudgets() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS); start(reader, reader(), EMPTY);
        var legacy = writer.flowSnapshot().orElseThrow();
        writer = atOperation(writer, OPERATION); reader = atOperation(reader, OPERATION + 3);
        var saved = writer.flowSnapshot().orElseThrow();
        assertTrue(saved.state().controlUnits() > legacy.state().controlUnits());
        long retained = saved.initialInputs().storageUnits() + saved.state().controlUnits() + 2;
        var tight = new ComputerHostLimits(new ResourceLimits(1000, retained, 1), LIMITS.archive());
        var left = writer.write(); var right = reader.write();
        assertEquals(XBusExchange.Status.MEMORY_LIMIT, writer.exchange("x0", tight, true, reader, "x1", LIMITS, true));
        assertEquals(left, writer.write()); assertEquals(right, reader.write());
        var raw = (CompoundTag)left;
        long units = ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive()).storageUnits();
        var smallArchive = new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(100000, 1000000, units));
        assertEquals(XBusExchange.Status.ARCHIVE_LIMIT, writer.exchange("x0", smallArchive, true, reader, "x1", LIMITS, true));
        assertEquals(left, writer.write()); assertEquals(right, reader.write());
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
    }

    @Test void legacyEncodingStaysUnchangedAndIndependentOperationCannotHideInAnOldOwner() {
        var host = new ComputerBlockHost(() -> {}); start(host, writer(), INPUTS);
        var raw = (CompoundTag)host.write(); assertEquals(3, raw.getInt(FlowBlockNbtCodec.MARKER));
        var root = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
        var flow = (DataValue.RecordValue)root.field("flow");
        assertEquals(new DataValue.IntegerValue(1), flow.field("version"));
        assertFalse(((DataValue.RecordValue)flow.field("state")).fields().containsKey("operation"));
        assertEquals(host.flowSnapshot(), restore(raw).flowSnapshot());
        host = atOperation(host, OPERATION); raw = (CompoundTag)host.write();
        assertEquals(4, raw.getInt(FlowBlockNbtCodec.MARKER));
        raw.putInt(FlowBlockNbtCodec.MARKER, 3); assertOpaque(raw);
        raw.putInt(FlowBlockNbtCodec.MARKER, Integer.MAX_VALUE); assertOpaque(raw); // Unknown future schema, not a newly supported version.
    }

    static XBusAccess access(ComputerBlockHost host, String pin) {
        return new XBusAccess(host, XBusPortState.of(host.flowSnapshot().orElseThrow(), LIMITS.pins(), pin).orElseThrow(),
            () -> LIMITS, Optional::empty);
    }
    static void assertOpaque(CompoundTag raw) {
        var rejected = restore(raw); assertFalse(rejected.loaded());
        assertEquals(ComputerBlockHost.Code.INVALID_SAVE, rejected.diagnostic().orElseThrow().code());
        assertEquals(raw, rejected.write());
    }

    // Protocol fixtures for repeated source positions; no loop instruction is claimed by these tests.
    static ComputerBlockHost reenter(ComputerBlockHost host, long operation, FlowRuntime.Status status, DataValue.RecordValue locals) {
        var saved = host.flowSnapshot().orElseThrow(); var s = saved.state();
        var state = new FlowRuntime.Checkpoint(s.computer(), s.flow(), 0, status, locals,
            s.work() + 1, false, Optional.empty(), Optional.empty(), 0, s.signals(), operation);
        return restore(FlowBlockNbtCodec.encode(new FlowBlockHost.Saved(saved.program(), saved.initialInputs(), state,
            saved.receipts(), saved.control()), LIMITS.archive()));
    }

    static ComputerBlockHost atOperation(ComputerBlockHost host, long operation) {
        var saved = host.flowSnapshot().orElseThrow(); var s = saved.state();
        var state = new FlowRuntime.Checkpoint(s.computer(), s.flow(), s.cursor(), s.status(), s.locals(),
            s.work(), s.paused(), s.expression(), s.diagnostic(), s.sleepTicks(), s.signals(), operation);
        var result = restore(FlowBlockNbtCodec.encode(new FlowBlockHost.Saved(saved.program(), saved.initialInputs(), state,
            saved.receipts(), saved.control()), LIMITS.archive()));
        assertTrue(result.loaded(), () -> result.diagnostic().toString()); return result;
    }
}
