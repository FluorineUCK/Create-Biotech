package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ListCompositionTest {
    @Test void constructsOrderedValuesAndRestoresEveryWorkStepWithoutChangingInputs() {
        var graph = new ComputationGraph(Map.of("sample", DataType.Scalar.ANY), Map.of(
            "sample", new ComputationGraph.Input("sample"),
            "seven", new ComputationGraph.Constant(new DataValue.IntegerValue(7)),
            "list", new ComputationGraph.MakeList(List.of("sample", "seven", "sample"))), Map.of("result", "list"));
        var installed = compile(graph, 10_000);
        var source = new DataValue.RecordValue(Map.of("name", new DataValue.TextValue("reading")));
        var inputs = new DataValue.RecordValue(Map.of("sample", source));
        var machine = new ComputationMachine(installed);
        machine.begin(inputs);
        int steps = 0;
        while (machine.report().status() == ComputationMachine.Status.RUNNING) {
            assertTrue(++steps < 100);
            var saved = machine.checkpoint();
            machine = ComputationMachine.restore(installed, saved);
            assertEquals(saved, machine.checkpoint());
            machine.step();
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
        assertTrue(steps >= 3 + 3, "each emitted list item consumes work");
        var result = (DataValue.ListValue) machine.report().output().orElseThrow().field("result");
        assertEquals(List.of(source, new DataValue.IntegerValue(7), source), result.values());
        assertThrows(UnsupportedOperationException.class, () -> result.values().clear());
        assertEquals(new DataValue.TextValue("reading"), source.field("name"));
        assertEquals(new DataType.ListType(DataType.Scalar.ANY), installed.root().nodeTypes().get("list"));
    }

    @Test void concatenatesWithoutFlatteningAndResumesEveryTick() {
        var nested = new DataValue.ListValue(List.of(new DataValue.IntegerValue(8)));
        var left = new DataValue.ListValue(List.of(nested, new DataValue.IntegerValue(1)));
        var right = new DataValue.ListValue(List.of(new DataValue.TextValue("end"), nested));
        var graph = concatGraph(DataType.Scalar.ANY);
        var installed = compile(graph, 10_000);
        var machine = new ComputationMachine(installed);
        machine.begin(new DataValue.RecordValue(Map.of("left", left, "right", right)));
        int steps = 0;
        while (machine.report().status() == ComputationMachine.Status.RUNNING) {
            assertTrue(++steps < 100);
            machine = ComputationMachine.restore(installed, machine.checkpoint());
            machine.tick();
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
        assertEquals(new DataValue.ListValue(List.of(nested, new DataValue.IntegerValue(1), new DataValue.TextValue("end"), nested)),
            machine.report().output().orElseThrow().field("result"));
        assertEquals(List.of(nested, new DataValue.IntegerValue(1)), left.values());
        assertEquals(List.of(new DataValue.TextValue("end"), nested), right.values());
        assertTrue(steps >= 4 + 3);
    }

    @Test void emptyHomogeneousAndHeterogeneousConstructionKeepExistingTypeRules() {
        for (var refs : List.of(List.<String>of(), List.of("a", "a"), List.of("a", "b"))) {
            var graph = new ComputationGraph(Map.of(), Map.of(
                "a", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
                "b", new ComputationGraph.Constant(DataValue.NullValue.INSTANCE),
                "list", new ComputationGraph.MakeList(refs)), Map.of("result", "list"));
            var installed = compile(graph, 10_000);
            var expected = refs.equals(List.of("a", "a")) ? DataType.Scalar.INTEGER : DataType.Scalar.ANY;
            assertEquals(new DataType.ListType(expected), installed.root().nodeTypes().get("list"));
            var machine = new ComputationMachine(installed); machine.begin(new DataValue.RecordValue(Map.of())); finish(machine);
            assertEquals(refs.size(), ((DataValue.ListValue) machine.report().output().orElseThrow().field("result")).values().size());
        }
        assertThrows(ComputationException.class, () -> new ComputationGraph.MakeList(List.of(" ")));
        var refs = new ArrayList<>(List.of("value")); var node = new ComputationGraph.MakeList(refs); refs.clear();
        assertEquals(List.of("value"), node.dependencies());
        assertEquals(new DataType.ListType(DataType.Scalar.INTEGER),
            compile(concatGraph(new DataType.ListType(DataType.Scalar.INTEGER)), 10_000).root().nodeTypes().get("joined"));
    }

    @Test void bothConcatInputsMustBeListsEvenWhenTheOtherIsEmpty() {
        assertEquals(ComputationException.Code.TYPE_MISMATCH,
            assertThrows(ComputationException.class, () -> compile(concatGraph(DataType.Scalar.INTEGER), 10_000)).code());
        for (String bad : List.of("left", "right")) {
            var inputs = new HashMap<String, DataValue>();
            inputs.put("left", new DataValue.ListValue(List.of())); inputs.put("right", new DataValue.ListValue(List.of()));
            inputs.put(bad, DataValue.NullValue.INSTANCE);
            var machine = new ComputationMachine(compile(concatGraph(DataType.Scalar.ANY), 10_000));
            machine.begin(new DataValue.RecordValue(inputs)); finish(machine);
            assertEquals(ComputationException.Code.TYPE_MISMATCH, machine.report().diagnostic().orElseThrow().code());
            assertTrue(machine.report().output().isEmpty());
        }
    }

    @Test void longConcatRemainsUnfinishedAndMemoryFailureDoesNotCommitPartialList() {
        var graph = concatGraph(new DataType.ListType(DataType.Scalar.INTEGER));
        var empty = new DataValue.ListValue(List.of());
        var definition = new ProgramDefinition(graph, Map.of("log", new ProgramDefinition.Cell(new DataType.ListType(DataType.Scalar.INTEGER), empty)),
            List.of(new ProgramDefinition.Write("log", "result")));
        var source = new DataValue.ListValue(Collections.nCopies(256, new DataValue.IntegerValue(5)));
        var inputs = new DataValue.RecordValue(Map.of("left", source, "right", source));
        var large = new ComputationMachine(ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(100, 100_000, 1)));
        large.begin(inputs);
        while (large.checkpoint().frames().stream().noneMatch(MachineCheckpoint.ListState.class::isInstance)) large.step();
        long limit = large.report().peakMemoryUnits() + 2;
        large.tick(); assertEquals(ComputationMachine.Status.RUNNING, large.report().status());
        assertEquals(empty, large.memory().field("log"));
        var small = new ComputationMachine(ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(100, limit, 1)));
        small.begin(inputs); finish(small);
        assertEquals(ComputationException.Code.MEMORY_LIMIT, small.report().diagnostic().orElseThrow().code());
        assertEquals(empty, small.memory().field("log")); assertTrue(small.report().output().isEmpty());
        finish(large); assertEquals(512, ((DataValue.ListValue) large.memory().field("log")).values().size());
        assertEquals(256, source.values().size());
    }

    @Test void forgedListCursorOrMissingParentIsRejectedWithoutExecuting() {
        var installed = compile(concatGraph(DataType.Scalar.ANY), 10_000);
        var machine = new ComputationMachine(installed);
        machine.begin(new DataValue.RecordValue(Map.of("left", new DataValue.ListValue(List.of(new DataValue.IntegerValue(1))),
            "right", new DataValue.ListValue(List.of()))));
        while (machine.checkpoint().frames().stream().noneMatch(MachineCheckpoint.ListState.class::isInstance)) machine.step();
        var saved = machine.checkpoint();
        for (var frames : List.of(List.<MachineCheckpoint.FrameState>of(new MachineCheckpoint.ListState(0)),
            List.of(saved.frames().getFirst(), new MachineCheckpoint.ListState(-1)),
            List.of(saved.frames().getFirst(), new MachineCheckpoint.ListState(2)))) {
            var bad = new MachineCheckpoint(saved.memory(), saved.status(), saved.paused(), saved.workUnits(), saved.arenaUnits(),
                saved.output(), saved.diagnostic(), frames);
            assertEquals(ComputationException.Code.INVALID_CHECKPOINT,
                assertThrows(ComputationException.class, () -> ComputationMachine.restore(installed, bad)).code());
        }
    }

    static void finish(ComputationMachine machine) {
        for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) {
            assertTrue(guard < 2000); machine.step();
        }
    }

    static ComputationGraph concatGraph(DataType type) {
        return new ComputationGraph(Map.of("left", type, "right", type), Map.of(
            "left", new ComputationGraph.Input("left"), "right", new ComputationGraph.Input("right"),
            "joined", new ComputationGraph.ConcatLists("left", "right")), Map.of("result", "joined"));
    }

    static ProgramCompiler.InstalledProgram compile(ComputationGraph graph, long memory) {
        return ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of()), Map.of(), new ResourceLimits(100, memory, 1));
    }
}
