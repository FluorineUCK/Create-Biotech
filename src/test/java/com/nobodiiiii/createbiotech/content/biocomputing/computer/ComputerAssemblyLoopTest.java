package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerAssemblyLoopTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {-1, 0, 2})
    void literalRepeatHasExplicitZeroAndNegativeSemantics(long count) {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program(List.of(new FlowProgram.Repeat("repeat", new FlowInput.Literal(new DataValue.IntegerValue(count))),
            increment(), new FlowProgram.EndRepeat("end"))), inputs(9), LIMITS, true);
        for (int i = 0; i < 100 && state(host).status() == FlowRuntime.Status.READY; i++) host.step(LIMITS, true);
        assertEquals(count < 0 ? FlowRuntime.Status.FAILED : FlowRuntime.Status.COMPLETE, state(host).status());
        assertEquals(new DataValue.IntegerValue(Math.max(0, count)), state(host).locals().field("counter"));
        if (count < 0) {
            assertEquals(ComputationException.Code.INVALID_ARGUMENT, state(host).diagnostic().orElseThrow().code());
            assertEquals("repeat", state(host).diagnostic().orElseThrow().location());
        }
        var before = host.flowSnapshot().orElseThrow();
        var restored = new ComputerBlockHost(() -> {}); restored.read(host.write()); restored.tick(LIMITS, true);
        assertEquals(before, restored.flowSnapshot().orElseThrow());
    }

    @Test void nestedRepeatRecapturesItsExpressionOnReentryWithoutChangingTheOuterCounter() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program(List.of(new FlowProgram.Repeat("outer", "n"),
            new FlowProgram.Repeat("inner", new FlowInput.Expression("increment", "value", Map.of("value", "n"))), increment(),
            new FlowProgram.Evaluate("clear_count", "zero", new FlowCallBindings(Map.of(), Map.of("value", "n"))),
            new FlowProgram.EndRepeat("inner_end"), new FlowProgram.EndRepeat("outer_end"))), inputs(2), LIMITS, true);
        for (int i = 0; i < 300 && state(host).status() == FlowRuntime.Status.READY; i++) {
            host.step(LIMITS, true); var saved = host.flowSnapshot().orElseThrow();
            var restored = new ComputerBlockHost(() -> {}); restored.read(host.write()); restored.tick(LIMITS, true);
            assertEquals(saved, restored.flowSnapshot().orElseThrow()); host = restored;
        }
        assertEquals(FlowRuntime.Status.COMPLETE, state(host).status());
        assertEquals(new DataValue.IntegerValue(4), state(host).locals().field("counter"), "Inner captures 3 then 1; outer remains 2");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"forever", "until"})
    void emptyInfiniteLoopsStillSpendBudgetAndDoNotAccumulateFrames(String kind) {
        var steps = kind.equals("forever") ? List.<FlowProgram.Step>of(new FlowProgram.Forever("head"), new FlowProgram.EndForever("end"))
            : List.<FlowProgram.Step>of(new FlowProgram.Until("head", new FlowInput.Literal(new DataValue.BooleanValue(false))), new FlowProgram.EndUntil("end"));
        var host = new ComputerBlockHost(() -> {}); host.installFlow(program(steps), inputs(0), LIMITS, true);
        host.setPaused(false, LIMITS, true);
        for (int i = 0; i < 12; i++) host.tick(LIMITS, true);
        long size = state(host).controlUnits();
        for (int i = 0; i < 120; i++) host.tick(LIMITS, true);
        assertEquals(132, state(host).work()); assertEquals(FlowRuntime.Status.READY, state(host).status());
        assertEquals(size, state(host).controlUnits()); assertEquals(FlowRepeatState.EMPTY, state(host).repeats());
        assertEquals(inputs(0), state(host).locals());
    }

    @Test void cycleCounterExhaustionFailsDurablyInsteadOfWrappingOrThrowingOutsideTheVm() {
        var program = program(List.of(new FlowProgram.Forever("forever"), new FlowProgram.EndForever("end")));
        var initial = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, inputs(0), LIMITS.hardware()).checkpoint();
        var runtime = FlowRuntime.restore(program, withWork(initial, Long.MAX_VALUE - 1), LIMITS.hardware()); runtime.load();
        assertEquals(1, runtime.advance(1));
        var failed = runtime.checkpoint();
        assertEquals(FlowRuntime.Status.FAILED, failed.status());
        assertEquals(ComputationException.Code.ARITHMETIC_OVERFLOW, failed.diagnostic().orElseThrow().code());
        assertEquals(Long.MAX_VALUE, failed.work()); assertEquals(initial.assembly(), failed.assembly());
        var restored = FlowRuntime.restore(program, failed, LIMITS.hardware()); restored.load();
        assertEquals(0, restored.advance(1)); assertEquals(failed, restored.checkpoint());
        assertThrows(ComputationException.class, () -> FlowRuntime.restore(program, withWork(initial, Long.MAX_VALUE), LIMITS.hardware()));
    }
    private static FlowRuntime.Checkpoint withWork(FlowRuntime.Checkpoint old, long work) {
        return new FlowRuntime.Checkpoint(old.computer(), old.flow(), old.cursor(), old.status(), old.locals(), work, old.paused(),
            old.expression(), old.diagnostic(), old.sleepTicks(), old.signals(), work, old.repeats(), old.inputInvocation(), old.calls(), old.entry(), old.assembly());
    }

    @Test void foreverYieldsToEachComputersOwnBudgetAndPauseOrUnloadDoesNotAdvanceIt() {
        var program = program(List.of(new FlowProgram.Forever("forever"), increment(), new FlowProgram.EndForever("end")));
        var fastLimits = new ComputerHostLimits(new ResourceLimits(1000, 100000, 5), LIMITS.archive());
        var slowLimits = new ComputerHostLimits(new ResourceLimits(1000, 100000, 2), LIMITS.archive());
        var fast = new ComputerBlockHost(() -> {}); var slow = new ComputerBlockHost(() -> {});
        fast.installFlow(program, inputs(0), fastLimits, true); slow.installFlow(program, inputs(0), slowLimits, true);
        fast.setPaused(false, fastLimits, true); slow.setPaused(false, slowLimits, true);
        for (int tick = 1; tick <= 8; tick++) {
            fast.tick(fastLimits, true); slow.tick(slowLimits, true);
            assertEquals(tick * 5L, state(fast).work()); assertEquals(tick * 2L, state(slow).work());
            assertEquals(FlowRuntime.Status.READY, state(fast).status()); assertEquals(FlowRuntime.Status.READY, state(slow).status());
        }
        assertNotEquals(state(fast).computer(), state(slow).computer());
        assertTrue(((DataValue.IntegerValue)state(fast).locals().field("counter")).value()
            > ((DataValue.IntegerValue)state(slow).locals().field("counter")).value());
        fast.setPaused(true, fastLimits, true); var frozen = fast.flowSnapshot().orElseThrow();
        fast.tick(fastLimits, true); slow.tick(slowLimits, true);
        assertEquals(frozen, fast.flowSnapshot().orElseThrow()); assertEquals(18, state(slow).work());
        var raw = fast.write(); fast.unload(); fast.tick(fastLimits, false);
        assertFalse(fast.loaded()); assertEquals(raw, fast.write());
        fast.tick(fastLimits, true); assertEquals(frozen, fast.flowSnapshot().orElseThrow());
        fast.step(fastLimits, true); assertEquals(frozen.state().work() + 1, state(fast).work(), "Debug step is one instruction, not the hardware tick budget");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, 3})
    void untilReevaluatesItsNestedExpressionWithExplicitBindingsAtEveryHead(long count) {
        var until = new FlowProgram.Until("until", new FlowInput.Expression("finished", "value", Map.of("value", "counter", "limit", "n")));
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program(List.of(until, increment(), new FlowProgram.EndUntil("end"))), inputs(count), LIMITS, true);
        for (int i = 0; i < 200 && state(host).status() == FlowRuntime.Status.READY; i++) {
            host.step(LIMITS, true);
            var before = host.flowSnapshot().orElseThrow();
            var restored = new ComputerBlockHost(() -> {}); restored.read(host.write()); restored.tick(LIMITS, true);
            assertEquals(before, restored.flowSnapshot().orElseThrow()); host = restored;
        }
        assertEquals(FlowRuntime.Status.COMPLETE, state(host).status());
        assertEquals(new DataValue.IntegerValue(count), state(host).locals().field("counter"));
        assertTrue(state(host).expression().isEmpty()); assertTrue(state(host).inputInvocation().isEmpty());
    }

    @Test void repeatCapturesCountOnceAndRestoresEveryInstructionInTheActualOwner() {
        var program = program(List.of(new FlowProgram.Repeat("repeat", "n"), increment(),
            new FlowProgram.Evaluate("clear_count", "zero", new FlowCallBindings(Map.of(), Map.of("value", "n"))),
            new FlowProgram.EndRepeat("end")));
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, inputs(3), LIMITS, true);
        for (int i = 0; i < 200 && state(host).status() == FlowRuntime.Status.READY; i++) {
            var before = host.flowSnapshot().orElseThrow();
            host.tick(LIMITS, true); assertEquals(before, host.flowSnapshot().orElseThrow(), "Paused ticks do not execute");
            host.step(LIMITS, true);
            assertEquals(before.state().work() + 1, state(host).work());
            assertTrue(state(host).assembly().isPresent()); assertTrue(state(host).expression().isEmpty());
            assertEquals(FlowRepeatState.EMPTY, state(host).repeats(), "Loop control is compiled, not a legacy repeat frame");
            var saved = host.flowSnapshot().orElseThrow();
            var restored = new ComputerBlockHost(() -> {}); restored.read(host.write()); restored.tick(LIMITS, true);
            assertEquals(saved, restored.flowSnapshot().orElseThrow()); host = restored;
        }
        assertEquals(FlowRuntime.Status.COMPLETE, state(host).status());
        assertEquals(inputs(3), host.flowSnapshot().orElseThrow().initialInputs());
        assertEquals(new DataValue.IntegerValue(3), state(host).locals().field("counter"));
        assertEquals(new DataValue.IntegerValue(0), state(host).locals().field("n"));
        assertEquals(Set.of("n", "counter"), state(host).locals().fields().keySet(), "Private loop registers are not named player locals");
    }

    static FlowProgram program(List<FlowProgram.Step> steps) {
        var increment = new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), Map.of(
            "input", new ComputationGraph.Input("value"), "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "input", "one")), Map.of("value", "sum"));
        var zero = new ComputationGraph(Map.of(), Map.of("zero", new ComputationGraph.Constant(new DataValue.IntegerValue(0))), Map.of("value", "zero"));
        var finishedNodes = new LinkedHashMap<>(increment.nodes());
        finishedNodes.put("limit", new ComputationGraph.Input("limit"));
        finishedNodes.put("finished", new ComputationGraph.Scalar(ScalarOperation.GREATER_INTEGER, Map.of("left", "sum", "right", "limit")));
        var finished = new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER, "limit", DataType.Scalar.INTEGER), finishedNodes, Map.of("value", "finished"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
            Map.of("increment", new ProgramDefinition.Entry(increment, List.of(), List.of()),
                "zero", new ProgramDefinition.Entry(zero, List.of(), List.of()),
                "finished", new ProgramDefinition.Entry(finished, List.of(), List.of()))), Map.of());
        return new FlowProgram(Map.of("n", DataType.Scalar.INTEGER, "counter", DataType.Scalar.INTEGER), steps, archive).withAssembly();
    }
    static FlowProgram.Evaluate increment() {
        return new FlowProgram.Evaluate("increment", "increment", new FlowCallBindings(Map.of("value", "counter"), Map.of("value", "counter")));
    }
    static DataValue.RecordValue inputs(long n) {
        return new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(n), "counter", new DataValue.IntegerValue(0)));
    }
    static FlowRuntime.Checkpoint state(ComputerBlockHost host) { return host.flowSnapshot().orElseThrow().state(); }
}
