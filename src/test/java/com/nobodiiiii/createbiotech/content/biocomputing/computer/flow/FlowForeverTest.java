package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowForeverTest {
    static final ResourceLimits LIMITS = new ResourceLimits(1000, 100000, 1);
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(100000, 1000000, 1000000);

    @Test void continuousCounterUsesFiniteSlicesAndRestoresWithoutResettingOrRepeatingAnIteration() {
        var graph = new ComputationGraph(Map.of("count", DataType.Scalar.INTEGER), Map.of(
            "input", new ComputationGraph.Input("count"), "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "input", "one")), Map.of("count", "next"));
        var template = new FlowProgram(Map.of("count", DataType.Scalar.INTEGER), List.of(new FlowProgram.Evaluate("increment", "increment")),
            new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
                Map.of("increment", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of()));
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)FlowProgramCodec.encode(template)).fields());
        fields.put("steps", new DataValue.ListValue(List.of(delimiter("forever", "loop"),
            ((DataValue.ListValue)fields.get("steps")).values().getFirst(), delimiter("end_forever", "end"))));
        var program = FlowProgramCodec.decode(new DataValue.RecordValue(fields));
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program,
            new DataValue.RecordValue(Map.of("count", new DataValue.IntegerValue(0))), LIMITS);
        boolean sawReentry = false;
        for (int tick = 0; tick < 100; tick++) {
            assertEquals(1, runtime.advance(1));
            assertEquals(tick + 1, runtime.checkpoint().work());
            sawReentry |= runtime.checkpoint().operation() > runtime.checkpoint().cursor();
            runtime.setPaused(true); var paused = runtime.checkpoint();
            assertEquals(0, runtime.advance(1)); assertEquals(paused, runtime.checkpoint());
            var snapshot = FlowNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(program, paused), ARCHIVE), ARCHIVE);
            runtime = FlowRuntime.restore(snapshot.program(), snapshot.state(), LIMITS);
            assertEquals(0, runtime.advance(1)); runtime.load(); runtime.setPaused(false);
        }
        assertTrue(sawReentry); assertTrue(((DataValue.IntegerValue)runtime.checkpoint().locals().field("count")).value() > 3);
        assertNotEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        assertNotEquals(FlowRuntime.Status.FAILED, runtime.checkpoint().status());
    }
    @Test void emptyLoopAndNestedConditionStillConsumeExactlyTheGrantedWork() {
        var empty = new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"), new FlowProgram.EndForever("end")));
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), empty, XBusFlowTest.EMPTY, LIMITS);
        for (int i = 0; i < 50; i++) { assertEquals(1, runtime.advance(1)); assertEquals(i + 1, runtime.checkpoint().work()); }
        assertEquals(50, runtime.checkpoint().operation()); assertEquals(0, runtime.checkpoint().cursor());
        assertEquals(0, runtime.advance(0)); runtime.unload(); assertEquals(0, runtime.advance(1));
        var conditional = new FlowProgram(Map.of("run", DataType.Scalar.BOOLEAN), List.of(new FlowProgram.If("if", "run"),
            new FlowProgram.Forever("loop"), new FlowProgram.EndForever("end"), new FlowProgram.Otherwise("else"), new FlowProgram.EndIf("end_if")));
        for (boolean flag : List.of(false, true)) {
            runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), conditional,
                new DataValue.RecordValue(Map.of("run", new DataValue.BooleanValue(flag))), LIMITS);
            for (int i = 0; i < 15; i++) runtime.advance(1);
            assertEquals(flag ? FlowRuntime.Status.READY : FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        }
    }

    @Test void delimitersCannotCrossGroupsAndLoopCarriedTypesMustMatchAtTheBackEdge() {
        var malformed = List.of(
            List.<FlowProgram.Step>of(new FlowProgram.Forever("loop")),
            List.<FlowProgram.Step>of(new FlowProgram.EndForever("end")),
            List.<FlowProgram.Step>of(new FlowProgram.Forever("loop"), new FlowProgram.Otherwise("else"), new FlowProgram.EndForever("end")),
            List.<FlowProgram.Step>of(new FlowProgram.Forever("loop"), new FlowProgram.If("if", "ok"), new FlowProgram.EndForever("end"), new FlowProgram.EndIf("close")));
        for (var steps : malformed) assertEquals(ComputationException.Code.INVALID_GRAPH, assertThrows(ComputationException.class,
            () -> FlowBranches.compile(steps)).code());
        var wrongType = new FlowProgram(Map.of("data", DataType.Scalar.INTEGER), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.Read("read", "x0", "data", XBusFlowTest.PACKET), new FlowProgram.EndForever("end")));
        assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> wrongType.validate(LIMITS)).code());
    }

    @Test void receivedBodyLocalsAreDroppedOnReentryInsteadOfLeakingIntoTheNextIteration() {
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.Read("read", "x1", "input", XBusFlowTest.PACKET), new FlowProgram.EndForever("end")));
        var receiver = XBusFlowTest.runtime(program, XBusFlowTest.EMPTY); var sender = XBusFlowTest.writer();
        receiver.advance(1); receiver.advance(1); sender.advance(1);
        assertEquals(XBusExchange.Status.EXCHANGED, XBusFlowTest.exchange(sender, receiver).commit());
        assertEquals(XBusFlowTest.packet(12), receiver.checkpoint().locals().field("input"));
        long operation = receiver.checkpoint().operation(); receiver.advance(1);
        assertEquals(XBusFlowTest.EMPTY, receiver.checkpoint().locals());
        assertEquals(0, receiver.checkpoint().cursor()); assertEquals(operation + 1, receiver.checkpoint().operation());
        receiver = roundTrip(receiver); receiver.advance(1); receiver.advance(1);
        assertEquals(FlowRuntime.Status.WAITING_READ, receiver.checkpoint().status());
        assertEquals(XBusFlowTest.EMPTY, receiver.checkpoint().locals());
    }

    @Test void loopSignalModesPreserveLaterDrivesButRejectPhantomFirstPassAndMissingRequiredDrives() {
        var program = new FlowProgram(Map.of("level", DataType.Scalar.INTEGER), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.SignalRead("read", "p0", "sample"), new FlowProgram.SignalWrite("write", "p0", "level"), new FlowProgram.EndForever("end")));
        var runtime = XBusFlowTest.runtime(program, new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(7))));
        var initial = runtime.checkpoint();
        assertThrows(ComputationException.class, () -> FlowRuntime.restore(program, signals(initial, new SimpleIoState(Map.of("p0", 7))), LIMITS));
        for (int i = 0; i < 12; i++) {
            runtime.advanceRound(Map.of("p0", 3)); runtime = roundTrip(runtime);
            int cursor = runtime.checkpoint().cursor();
            assertEquals(i < 2 || cursor == 2 ? Map.of() : Map.of("p0", 7), runtime.checkpoint().signals().outputs(),
                "Read clears own drive, write restores it, loop edges retain it");
            if (cursor == 3) {
                var required = runtime.checkpoint();
                assertThrows(ComputationException.class, () -> FlowRuntime.restore(program, signals(required, SimpleIoState.EMPTY), LIMITS));
            }
        }
    }

    @Test void inventedReentryCannotGrantSignalDriveBeforeEnoughControlWorkAndOperationDistance() {
        var program = new FlowProgram(Map.of("level", DataType.Scalar.INTEGER), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.SignalRead("read", "p0", "sample"), new FlowProgram.SignalWrite("write", "p0", "level"), new FlowProgram.EndForever("end")));
        var runtime = XBusFlowTest.runtime(program, new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(7))));
        var s = runtime.checkpoint();
        for (var fake : new long[][] {{0, 1}, {0, 4}, {3, 4}, {4, 1}}) {
            var state = new FlowRuntime.Checkpoint(s.computer(), s.flow(), 0, FlowRuntime.Status.READY, s.locals(), fake[0], false,
                Optional.empty(), Optional.empty(), 0, new SimpleIoState(Map.of("p0", 7)), fake[1]);
            var saved = FlowNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(program, state), ARCHIVE), ARCHIVE);
            assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
                () -> FlowRuntime.restore(saved.program(), saved.state(), LIMITS)).code());
        }
        for (int i = 0; i < 4; i++) runtime.advanceRound(Map.of("p0", 3));
        assertEquals(Map.of("p0", 7), roundTrip(runtime).checkpoint().signals().outputs());
    }

    @Test void unreachableContinuationAfterForeverIsRejectedEvenAtTheLargestWorkCounter() {
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"), new FlowProgram.EndForever("end")));
        var bad = new FlowRuntime.Checkpoint(UUID.randomUUID(), UUID.randomUUID(), 2, FlowRuntime.Status.COMPLETE,
            XBusFlowTest.EMPTY, Long.MAX_VALUE, false);
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
            () -> FlowRuntime.restore(program, bad, LIMITS)).code());
    }
    static FlowRuntime.Checkpoint signals(FlowRuntime.Checkpoint s, SimpleIoState outputs) {
        return new FlowRuntime.Checkpoint(s.computer(), s.flow(), s.cursor(), s.status(), s.locals(), s.work(), s.paused(),
            s.expression(), s.diagnostic(), s.sleepTicks(), outputs, s.operation());
    }
    static FlowRuntime roundTrip(FlowRuntime runtime) {
        var saved = FlowNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(runtime.program(), runtime.checkpoint()), ARCHIVE), ARCHIVE);
        assertEquals(runtime.checkpoint(), saved.state());
        var result = FlowRuntime.restore(saved.program(), saved.state(), LIMITS); result.load(); return result;
    }
    static DataValue.RecordValue delimiter(String kind, String source) {
        return new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue(kind), "source", new DataValue.TextValue(source)));
    }
}
