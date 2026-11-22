package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowProgramCodec;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowRepeatTest {
    static final ResourceLimits LIMITS = new ResourceLimits(1000, 100000, 1);

    @Test void publicCountedLoopRunsExactlyThreeTimesAndContinuesAfterTheGroup() {
        var program = counter();
        var runtime = runtime(program, 3);
        for (int i = 0; i < 100 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++)
            assertEquals(1, runtime.advance(1));
        assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(4), runtime.checkpoint().locals().field("count"),
            "Three iterations, followed by the independent increment after the group");
    }

    @Test void everyInstructionCanPauseUnloadAndRestoreWithoutLosingTheCapturedCount() {
        var runtime = runtime(counter(), 3);
        for (int i = 0; i < 100 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) {
            assertEquals(1, runtime.advance(1));
            runtime.setPaused(true); var saved = runtime.checkpoint();
            assertEquals(0, runtime.advance(1)); runtime.unload(); runtime.elapseTick(); assertEquals(saved, runtime.checkpoint());
            runtime = FlowForeverTest.roundTrip(runtime); assertEquals(saved, runtime.checkpoint());
            runtime.setPaused(false);
        }
        assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(4), runtime.checkpoint().locals().field("count"));
    }

    @Test void restoreRejectsMissingForeignDuplicateAndImpossibleCountFrames() {
        var runtime = runtime(counter(), 3); runtime.advance(1); var valid = runtime.checkpoint();
        var frame = valid.repeats().frames().getFirst();
        for (var frames : List.of(List.<FlowRepeatState.Frame>of(), List.of(frame, frame),
            List.of(new FlowRepeatState.Frame(1, 3, 3)), List.of(new FlowRepeatState.Frame(0, 0, 0)),
            List.of(new FlowRepeatState.Frame(0, 3, 4)), List.of(new FlowRepeatState.Frame(0, 3, -1)),
            List.of(new FlowRepeatState.Frame(0, 3, 0)), List.of(new FlowRepeatState.Frame(0, 3, 2)))) {
            var bad = withFrames(valid, frames);
            assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
                () -> FlowRuntime.restore(runtime.program(), bad, LIMITS), frames.toString()).code());
        }
    }

    @Test void deletingAReenteredLoopFrameCannotRecaptureTheOriginalCount() {
        var program = new FlowProgram(Map.of("n", DataType.Scalar.INTEGER, "count", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Repeat("loop", "n"), new FlowProgram.EndRepeat("end")));
        var runtime = runtime(program, 2); runtime.advance(1); runtime.advance(1);
        assertEquals(1, runtime.checkpoint().repeats().frames().getFirst().remaining());
        var missing = withFrames(runtime.checkpoint(), List.of());
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
            () -> FlowRuntime.restore(program, missing, LIMITS)).code());
    }

    static FlowRuntime.Checkpoint withFrames(FlowRuntime.Checkpoint s, List<FlowRepeatState.Frame> frames) {
        return new FlowRuntime.Checkpoint(s.computer(), s.flow(), s.cursor(), s.status(), s.locals(), s.work(), s.paused(),
            s.expression(), s.diagnostic(), s.sleepTicks(), s.signals(), s.operation(), new FlowRepeatState(frames));
    }

    @Test void zeroSkipsNegativeFailsAndHugeCountsUseOnlyGrantedBudget() {
        var zero = runtime(counter(), 0);
        zero.advance(1); assertEquals(3, zero.checkpoint().cursor());
        for (int i = 0; i < 20; i++) zero.advance(1);
        assertEquals(FlowRuntime.Status.COMPLETE, zero.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(1), zero.checkpoint().locals().field("count"));
        var negative = runtime(counter(), -1); negative.advance(1);
        assertEquals(FlowRuntime.Status.FAILED, negative.checkpoint().status());
        assertEquals(ComputationException.Code.INVALID_ARGUMENT, negative.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(0, negative.checkpoint().cursor()); assertEquals(1, negative.checkpoint().work());
        FlowForeverTest.roundTrip(negative);
        var template = counter();
        var empty = new FlowProgram(template.inputs(), List.of(new FlowProgram.Repeat("loop", "n"), new FlowProgram.EndRepeat("end")));
        var huge = runtime(empty, Long.MAX_VALUE);
        for (int i = 0; i < 100; i++) { assertEquals(1, huge.advance(1)); huge = FlowForeverTest.roundTrip(huge); }
        assertEquals(100, huge.checkpoint().work()); assertEquals(FlowRuntime.Status.READY, huge.checkpoint().status());
        assertEquals(Long.MAX_VALUE - 99, huge.checkpoint().repeats().frames().getFirst().remaining());
    }

    @Test void nestedLoopsCaptureIndependentCountsAndRecaptureOnlyOnFreshEntry() {
        var template = counter(); var old = template.expressions().program().entries().get("increment").graph();
        var nodes = new LinkedHashMap<>(old.nodes());
        nodes.put("n", new ComputationGraph.Input("n"));
        nodes.put("less", new ComputationGraph.Scalar(ScalarOperation.SUBTRACT_INTEGER, Map.of("left", "n", "right", "one")));
        var graph = new ComputationGraph(template.inputs(), nodes, Map.of("count", "next", "n", "less"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
            Map.of("decrease", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(template.inputs(), List.of(new FlowProgram.Repeat("outer", "n"),
            new FlowProgram.Repeat("inner", "n"), new FlowProgram.Evaluate("step", "decrease"),
            new FlowProgram.EndRepeat("end_inner"), new FlowProgram.EndRepeat("end_outer")), archive);
        var runtime = runtime(program, 3); boolean nested = false;
        for (int i = 0; i < 100 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) {
            assertEquals(1, runtime.advance(1)); nested |= runtime.checkpoint().repeats().frames().size() == 2;
            runtime = FlowForeverTest.roundTrip(runtime);
        }
        assertTrue(nested); assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(3), runtime.checkpoint().locals().field("count"));
        assertEquals(new DataValue.IntegerValue(0), runtime.checkpoint().locals().field("n"));
        assertTrue(runtime.checkpoint().repeats().frames().isEmpty());
    }

    @Test void framesAreChargedAndMemoryFailureDoesNotPartiallyEnterTheBody() {
        var program = new FlowProgram(Map.of("n", DataType.Scalar.INTEGER, "count", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Repeat("loop", "n"), new FlowProgram.EndRepeat("end")));
        var probe = runtime(program, 2); long baseline = probe.checkpoint().controlUnits();
        probe.advance(1); assertTrue(probe.checkpoint().controlUnits() > baseline + 10);
        var limits = new ResourceLimits(100, baseline + 10, 1);
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, runtime(program, 2).checkpoint().locals(), limits);
        runtime.advance(1);
        assertEquals(FlowRuntime.Status.FAILED, runtime.checkpoint().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, runtime.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(0, runtime.checkpoint().cursor()); assertTrue(runtime.checkpoint().repeats().frames().isEmpty());
        var restored = FlowRuntime.restore(program, runtime.checkpoint(), limits); restored.load(); assertEquals(0, restored.advance(1));
    }

    @Test void boundariesRejectWrongTypesCrossedGroupsAndBodyOnlyNamesAfterTheLoop() {
        for (var type : List.of(DataType.Scalar.DECIMAL, DataType.Scalar.BOOLEAN, DataType.Scalar.TEXT)) {
            var wrong = new FlowProgram(Map.of("n", type), List.of(new FlowProgram.Repeat("loop", "n"), new FlowProgram.EndRepeat("end")));
            assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> wrong.validate(LIMITS)).code());
        }
        for (var steps : List.of(List.<FlowProgram.Step>of(new FlowProgram.Repeat("loop", "n"), new FlowProgram.EndForever("end")),
            List.<FlowProgram.Step>of(new FlowProgram.Forever("loop"), new FlowProgram.EndRepeat("end")),
            List.<FlowProgram.Step>of(new FlowProgram.Repeat("loop", "n"), new FlowProgram.Otherwise("else"), new FlowProgram.EndRepeat("end"))))
            assertThrows(ComputationException.class, () -> FlowBranches.compile(steps));
        var bodyOnly = new FlowProgram(Map.of("n", DataType.Scalar.INTEGER), List.of(new FlowProgram.Repeat("loop", "n"),
            new FlowProgram.Read("read", "x1", "packet", XBusFlowTest.PACKET), new FlowProgram.EndRepeat("end"), new FlowProgram.Write("write", "x1", "packet")));
        assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> bodyOnly.validate(LIMITS)).code());
    }

    @Test void sleepAndXBusKeepFramesAndEachIterationHasANewOperationIdentity() {
        var inputs = new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(2), "ticks", new DataValue.IntegerValue(1)));
        var program = new FlowProgram(Map.of("n", DataType.Scalar.INTEGER, "ticks", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Repeat("loop", "n"), new FlowProgram.Sleep("pace", "ticks"),
            new FlowProgram.Read("receive", "x1", "packet", XBusFlowTest.PACKET), new FlowProgram.EndRepeat("end")));
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, inputs, LIMITS); runtime.advance(1);
        long previousRead = -1;
        for (int round = 0; round < 2; round++) {
            assertEquals(inputs, runtime.checkpoint().locals(), "Body-only packet is absent on every entry");
            runtime.advance(1); assertEquals(FlowRuntime.Status.SLEEPING, runtime.checkpoint().status());
            runtime = FlowForeverTest.roundTrip(runtime); runtime.setPaused(true); var paused = runtime.checkpoint();
            runtime.elapseTick(); assertEquals(paused, runtime.checkpoint()); runtime.unload(); runtime.setPaused(false);
            runtime.elapseTick(); assertEquals(1, runtime.checkpoint().sleepTicks()); runtime.load(); runtime.elapseTick();
            runtime.advance(1); assertEquals(FlowRuntime.Status.WAITING_READ, runtime.checkpoint().status());
            runtime = FlowForeverTest.roundTrip(runtime); assertTrue(runtime.checkpoint().operation() > previousRead);
            previousRead = runtime.checkpoint().operation();
            var sender = XBusFlowTest.writer(); sender.advance(1);
            assertEquals(XBusExchange.Status.EXCHANGED, XBusFlowTest.exchange(sender, runtime).commit());
            assertEquals(XBusFlowTest.packet(12), runtime.checkpoint().locals().field("packet"));
            runtime = FlowForeverTest.roundTrip(runtime); runtime.advance(1); runtime = FlowForeverTest.roundTrip(runtime);
        }
        assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status()); assertEquals(inputs, runtime.checkpoint().locals());
        assertEquals(7, runtime.checkpoint().work(), "One capture and three instructions per iteration; sleep ticks are separate");
    }

    @Test void finiteSignalDriveAndReadSurviveEverySaveAndExitPath() {
        for (long n : List.of(0L, 1L, 3L)) {
            var inputs = new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(n), "level", new DataValue.IntegerValue(7)));
            var program = new FlowProgram(Map.of("n", DataType.Scalar.INTEGER, "level", DataType.Scalar.INTEGER), List.of(
                new FlowProgram.Repeat("loop", "n"), new FlowProgram.SignalRead("read", "p0", "sample"),
                new FlowProgram.SignalWrite("write", "p0", "level"), new FlowProgram.EndRepeat("end")));
            var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, inputs, LIMITS);
            for (int i = 0; i < 20 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) {
                runtime.advanceRound(Map.of("p0", 12)); runtime = FlowForeverTest.roundTrip(runtime);
            }
            assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
            assertEquals(n == 0 ? Map.of() : Map.of("p0", 7), runtime.checkpoint().signals().outputs());
            assertEquals(inputs, runtime.checkpoint().locals(), "Body-only sample cannot escape even after nonzero iterations");
        }
    }

    static FlowProgram counter() {
        var graph = new ComputationGraph(Map.of("count", DataType.Scalar.INTEGER), Map.of(
            "input", new ComputationGraph.Input("count"), "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "input", "one")), Map.of("count", "next"));
        var template = new FlowProgram(Map.of("count", DataType.Scalar.INTEGER, "n", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Evaluate("increment", "increment"), new FlowProgram.Evaluate("after", "increment")),
            new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
                Map.of("increment", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of()));
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)FlowProgramCodec.encode(template)).fields());
        var steps = ((DataValue.ListValue)fields.get("steps")).values();
        fields.put("steps", new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of(
            "kind", new DataValue.TextValue("repeat"), "source", new DataValue.TextValue("repeat"), "count", new DataValue.TextValue("n"))),
            steps.getFirst(), FlowForeverTest.delimiter("end_repeat", "end"), steps.getLast())));
        return FlowProgramCodec.decode(new DataValue.RecordValue(fields));
    }
    static FlowRuntime runtime(FlowProgram program, long n) {
        return new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program,
            new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(n), "count", new DataValue.IntegerValue(0))), LIMITS);
    }
}
