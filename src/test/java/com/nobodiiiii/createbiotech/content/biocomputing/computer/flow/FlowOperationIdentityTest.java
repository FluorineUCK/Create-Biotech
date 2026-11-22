package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowOperationIdentityTest {
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(100_000, 1_000_000, 1_000_000);
    static final ResourceLimits LIMITS = new ResourceLimits(1000, 100_000, 1);
    static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());

    @Test void independentOperationSurvivesArchivePauseAndSleepWithoutBecomingACursor() {
        var program = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Sleep("sleep", "ticks"), new FlowProgram.AwaitReadable("await", "x0")));
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program,
            new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(2))), LIMITS);
        long operation = (1L << 32) + 17;
        // Protocol fixture for future re-entry, not an already supported player loop.
        var decoded = withOperation(runtime, operation);
        var restored = FlowRuntime.restore(decoded.program(), decoded.state(), LIMITS);
        assertEquals(0, restored.advance(1), "An archive does not auto-load or run");
        restored.load(); restored.advance(1); restored.setPaused(true); restored.elapseTick();
        assertEquals(2, restored.checkpoint().sleepTicks());
        restored = roundTrip(restored);
        assertEquals(operation, encodedOperation(restored));
        restored.setPaused(false); restored.elapseTick();
        assertEquals(operation, encodedOperation(restored));
        restored.elapseTick();
        assertEquals(1, restored.checkpoint().cursor());
        assertEquals(operation + 1, encodedOperation(restored));
        restored.advance(1);
        assertEquals(FlowRuntime.Status.WAITING_READABLE, restored.checkpoint().status());
        assertEquals(operation + 1, encodedOperation(roundTrip(restored)));
    }

    @Test void independentOperationDoesNotAllowSavingPhantomSignalOutputs() {
        var runtime = XBusFlowTest.reader();
        var snapshot = withOperation(runtime, 17); var s = snapshot.state();
        var forged = new FlowRuntime.Checkpoint(s.computer(), s.flow(), s.cursor(), s.status(), s.locals(), s.work(),
            s.paused(), s.expression(), s.diagnostic(), s.sleepTicks(), new SimpleIoState(Map.of("p0", 5)), s.operation());
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class, () -> FlowNbtCodec.encode(
            new FlowNbtCodec.Snapshot(snapshot.program(), forged), ARCHIVE)).code());
    }

    @Test void pureWorkRetainsOperationUntilItsResultIsCommitted() {
        var sender = XBusFlowTest.writer(); var calculator = XBusFlowTest.runtime(XBusFlowTest.calculator(), EMPTY);
        sender.advance(1); calculator.advance(1);
        assertEquals(XBusExchange.Status.EXCHANGED, XBusFlowTest.exchange(sender, calculator).commit());
        var snapshot = withOperation(calculator, 41);
        calculator = FlowRuntime.restore(snapshot.program(), snapshot.state(), LIMITS); calculator.load();
        int slices = 0;
        while (calculator.checkpoint().cursor() == 1) {
            assertTrue(slices++ < 30); calculator.advance(1); calculator = roundTrip(calculator);
            assertEquals(calculator.checkpoint().cursor() == 1 ? 41 : 42, calculator.checkpoint().operation());
        }
        assertTrue(slices > 2);
        assertEquals(XBusFlowTest.packet(19), calculator.checkpoint().locals().field("out"));
    }

    @Test void signalsAndForwardBranchesPreserveTheOffsetAcrossEverySavedBoundary() {
        var program = new FlowProgram(Map.of("ok", DataType.Scalar.BOOLEAN, "value", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.If("if", "ok"), new FlowProgram.SignalWrite("write", "p0", "value"),
            new FlowProgram.Otherwise("else"), new FlowProgram.SignalRead("read", "p0", "sample"), new FlowProgram.EndIf("end")));
        for (boolean flag : List.of(false, true)) {
            var inputs = new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(flag), "value", new DataValue.IntegerValue(7)));
            var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, inputs, LIMITS);
            var snapshot = withOperation(flow, 19);
            flow = FlowRuntime.restore(snapshot.program(), snapshot.state(), LIMITS); flow.load();
            int rounds = 0;
            while (flow.checkpoint().status() != FlowRuntime.Status.COMPLETE) {
                assertTrue(rounds++ < 10); flow.advanceRound(Map.of("p0", 3)); flow = roundTrip(flow);
                assertEquals(19 + flow.checkpoint().cursor(), flow.checkpoint().operation());
            }
            assertEquals(flag ? Map.of("p0", 7) : Map.of(), flow.checkpoint().signals().outputs());
        }
    }

    @Test void sleepCompletionOverflowReportsFailureWithoutPublishingItsSuccessor() {
        var program = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("sleep", "ticks")));
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program,
            new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(1))), LIMITS);
        runtime.advance(1); var s = runtime.checkpoint();
        var checkpoint = new FlowRuntime.Checkpoint(s.computer(), s.flow(), s.cursor(), s.status(), s.locals(), s.work(), false,
            s.expression(), s.diagnostic(), s.sleepTicks(), s.signals(), Long.MAX_VALUE);
        var restored = FlowRuntime.restore(program, checkpoint, LIMITS); restored.load(); restored.elapseTick();
        assertEquals(FlowRuntime.Status.FAILED, restored.checkpoint().status());
        assertEquals(ComputationException.Code.ARITHMETIC_OVERFLOW, restored.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(0, restored.checkpoint().cursor()); assertEquals(Long.MAX_VALUE, restored.checkpoint().operation());
        assertEquals(s.locals(), roundTrip(restored).checkpoint().locals());
    }

    @Test void negativeOrBackdatedOperationAndLegacySchemaSmugglingAreRejected() {
        var runtime = XBusFlowTest.reader();
        for (long invalid : List.of(-1L, Long.MIN_VALUE))
            assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class, () -> withOperation(runtime, invalid)).code());
        var ready = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Sleep("skip", "ticks"))), new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(0))), LIMITS);
        ready.advance(1);
        assertThrows(ArchiveException.class, () -> withOperation(ready, 0));
        var snapshot = withOperation(runtime, 17);
        var encoded = FlowNbtCodec.encode(snapshot, ARCHIVE);
        var root = new LinkedHashMap<>(((DataValue.RecordValue)ValueNbtCodec.decode(encoded, ARCHIVE)).fields());
        root.put("version", new DataValue.IntegerValue(4));
        assertThrows(ArchiveException.class, () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(root), ARCHIVE), ARCHIVE));
    }

    static FlowNbtCodec.Snapshot withOperation(FlowRuntime runtime, long operation) {
        var root = new LinkedHashMap<>(((DataValue.RecordValue)ValueNbtCodec.decode(
            FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(runtime.program(), runtime.checkpoint()), ARCHIVE), ARCHIVE)).fields());
        var state = new LinkedHashMap<>(((DataValue.RecordValue)root.get("state")).fields());
        state.put("operation", new DataValue.IntegerValue(operation));
        state.put("work", new DataValue.IntegerValue(Math.max(operation, runtime.checkpoint().work())));
        state.putIfAbsent("sleep_ticks", new DataValue.IntegerValue(0));
        state.putIfAbsent("signal_outputs", EMPTY);
        root.put("version", new DataValue.IntegerValue(FlowProgram.hasSignals(runtime.program().steps()) ? 20 : 5)); root.put("state", new DataValue.RecordValue(state));
        return FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(root), ARCHIVE), ARCHIVE);
    }
    static long encodedOperation(FlowRuntime runtime) {
        var root = (DataValue.RecordValue)ValueNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(
            runtime.program(), runtime.checkpoint()), ARCHIVE), ARCHIVE);
        return ((DataValue.IntegerValue)((DataValue.RecordValue)root.field("state")).field("operation")).value();
    }
    static FlowRuntime roundTrip(FlowRuntime runtime) {
        var snapshot = new FlowNbtCodec.Snapshot(runtime.program(), runtime.checkpoint());
        var decoded = FlowNbtCodec.decode(FlowNbtCodec.encode(snapshot, ARCHIVE), ARCHIVE);
        assertEquals(snapshot, decoded);
        var result = FlowRuntime.restore(decoded.program(), decoded.state(), LIMITS); result.load(); return result;
    }
}
