package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DistinctBoundaryTest {
    private static ComputationGraph graph(DataType type) {
        return new ComputationGraph(Map.of("source", type), Map.of("source", new ComputationGraph.Input("source"), "unique", new ComputationGraph.Distinct("source")), Map.of("result", "unique"));
    }
    @Test void compilerPreservesElementTypeAndDynamicNonListFailsExplicitly() {
        var type = new DataType.ListType(new DataType.MapType(DataType.Scalar.INTEGER));
        assertEquals(type, ListEditTest.compile(graph(type), 10000).root().nodeTypes().get("unique"));
        assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> ListEditTest.compile(graph(DataType.Scalar.INTEGER), 10000)).code());
        var machine = new ComputationMachine(ListEditTest.compile(graph(DataType.Scalar.ANY), 10000));
        machine.begin(new DataValue.RecordValue(Map.of("source", new DataValue.TextValue("not a list")))); ListEditTest.finish(machine);
        assertEquals(ComputationException.Code.TYPE_MISMATCH, machine.report().diagnostic().orElseThrow().code());
    }
    @Test void reservationsBeforeRetentionFailWithoutPublishingOutputOrRamAndPausedTicksDoNoWork() {
        var definition = new ProgramDefinition(graph(new DataType.ListType(DataType.Scalar.INTEGER)),
            Map.of("log", new ProgramDefinition.Cell(new DataType.ListType(DataType.Scalar.INTEGER), ListEditTest.list(99))), List.of(new ProgramDefinition.Write("log", "result")));
        var hardware = new ResourceLimits(100, 10000, 1); var installed = ProgramCompiler.compile(definition, Map.of(), hardware);
        var inputs = new DataValue.RecordValue(Map.of("source", ListEditTest.list(3, 1, 3)));
        var machine = new ComputationMachine(installed); machine.begin(inputs);
        for (int i = 0; machine.checkpoint().frames().stream().noneMatch(MachineCheckpoint.DistinctState.class::isInstance); i++) { assertTrue(i < 10); machine.tick(); }
        var initial = machine.checkpoint(); machine.setPaused(true); var paused = machine.checkpoint(); machine.tick(); assertEquals(paused, machine.checkpoint());
        machine.step(); assertEquals(paused.workUnits() + 1, machine.report().workUnits()); assertTrue(machine.report().peakMemoryUnits() > paused.arenaUnits());
        assertEquals(ListEditTest.list(99), machine.memory().field("log"));
        var bounded = new ComputationMachine(ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(100, initial.arenaUnits() + 1, 1)));
        bounded.begin(inputs); ListEditTest.finish(bounded);
        assertEquals(ComputationException.Code.MEMORY_LIMIT, bounded.report().diagnostic().orElseThrow().code());
        assertEquals(ListEditTest.list(99), bounded.memory().field("log")); assertTrue(bounded.report().output().isEmpty());
        assertTrue(bounded.report().peakMemoryUnits() <= initial.arenaUnits() + 1);
        assertInvalid(installed, new MachineCheckpoint(initial.memory(), initial.status(), initial.paused(), initial.workUnits(), initial.arenaUnits() - 1, initial.output(), initial.diagnostic(), initial.frames()));
    }
    @Test void corruptIndicesRetentionCompletionPhaseAndNestedCursorAreRejected() {
        var source = new DataValue.ListValue(List.of(new DataValue.TextValue("abc"), new DataValue.TextValue("abc"), new DataValue.TextValue("abd")));
        var installed = ListEditTest.compile(graph(new DataType.ListType(DataType.Scalar.ANY)), 100000);
        var machine = new ComputationMachine(installed); machine.begin(new DataValue.RecordValue(Map.of("source", source)));
        MachineCheckpoint first = null, comparison = null;
        for (int i = 0; i < 30 && comparison == null; i++) {
            machine.tick(); var saved = machine.checkpoint();
            if (saved.frames().getLast() instanceof MachineCheckpoint.DistinctState state) {
                if (first == null) first = saved;
                if (state.progress().field("comparison") instanceof DataValue.RecordValue cursor) {
                    var frames = ((DataValue.ListValue)cursor.field("frames")).values();
                    if (!frames.isEmpty() && ((DataValue.IntegerValue)((DataValue.RecordValue)frames.getLast()).field("offset")).value() == 1) comparison = saved;
                }
            }
        }
        assertNotNull(first); assertNotNull(comparison);
        var progress = ((MachineCheckpoint.DistinctState)first.frames().getLast()).progress();
        for (var mutation : List.of(replace(progress, "index", ListEditTest.number(-1)), replace(progress, "candidate", ListEditTest.number(Integer.MAX_VALUE)),
                replace(progress, "done", new DataValue.BooleanValue(true)), replace(progress, "index", ListEditTest.number(3)),
                replace(progress, "retained", ListEditTest.list(0, 0)), replace(progress, "comparison", new DataValue.RecordValue(Map.of()))))
            assertInvalid(installed, withProgress(first, mutation));
        var active = ((MachineCheckpoint.DistinctState)comparison.frames().getLast()).progress();
        var cursor = (DataValue.RecordValue)active.field("comparison"); var frames = ((DataValue.ListValue)cursor.field("frames")).values();
        var frame = (DataValue.RecordValue)frames.getFirst();
        for (var mutation : List.of(replace(frame, "offset", ListEditTest.number(Integer.MAX_VALUE)), replace(frame, "mode", new DataValue.TextValue("DONE")),
                replace(frame, "scan", ListEditTest.number(1)), replace(frame, "mode", new DataValue.TextValue("WAIT_LIST")))) {
            var forged = replace(cursor, "frames", new DataValue.ListValue(List.of(mutation)));
            assertInvalid(installed, withProgress(comparison, replace(active, "comparison", forged)));
        }
        var forgedComplete = replace(cursor, "frames", new DataValue.ListValue(List.of()));
        assertInvalid(installed, withProgress(comparison, replace(active, "comparison", replace(forgedComplete, "unequal", new DataValue.BooleanValue(true)))));
        assertInvalid(installed, new MachineCheckpoint(comparison.memory(), comparison.status(), comparison.paused(), comparison.workUnits(), 1, comparison.output(), comparison.diagnostic(), comparison.frames()));
        var impossible = List.<MachineCheckpoint.FrameState>of(new MachineCheckpoint.DistinctState(progress));
        assertInvalid(installed, new MachineCheckpoint(first.memory(), first.status(), first.paused(), first.workUnits(), first.arenaUnits(), first.output(), first.diagnostic(), impossible));
    }
    static DataValue.RecordValue replace(DataValue.RecordValue state, String name, DataValue value) {
        var fields = new LinkedHashMap<>(state.fields()); fields.put(name, value); return new DataValue.RecordValue(fields);
    }
    static MachineCheckpoint withProgress(MachineCheckpoint saved, DataValue.RecordValue progress) {
        var frames = new ArrayList<>(saved.frames()); frames.set(frames.size() - 1, new MachineCheckpoint.DistinctState(progress));
        return new MachineCheckpoint(saved.memory(), saved.status(), saved.paused(), saved.workUnits(), saved.arenaUnits(), saved.output(), saved.diagnostic(), frames);
    }
    private static void assertInvalid(ProgramCompiler.InstalledProgram installed, MachineCheckpoint checkpoint) {
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> ComputationMachine.restore(installed, checkpoint)).code());
    }
}
