package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValueEqualityTest {
    @Test void nestedRecordsCompareAcrossEveryBudgetOneCheckpointWithoutChangingInputs() {
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("a", new DataValue.ListValue(List.of(new DataValue.IntegerValue(1), new DataValue.TextValue("1"))));
        fields.put("b", DataValue.NullValue.INSTANCE);
        var reversed = new LinkedHashMap<String, DataValue>(); reversed.put("b", fields.get("b")); reversed.put("a", fields.get("a"));
        var left = new DataValue.RecordValue(fields); var right = new DataValue.RecordValue(reversed);
        var installed = installation(DataType.Scalar.ANY, DataType.Scalar.ANY, 100000);
        var machine = new ComputationMachine(installed);
        machine.begin(new DataValue.RecordValue(Map.of("left", left, "right", right)));
        while (machine.report().status() == ComputationMachine.Status.RUNNING) {
            var saved = machine.checkpoint(); machine = ComputationMachine.restore(installed, saved);
            assertEquals(saved, machine.checkpoint()); machine.tick();
            assertEquals(saved.workUnits() + 1, machine.report().workUnits());
            assertTrue(machine.report().workUnits() < 1000);
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
        var output = machine.report().output().orElseThrow();
        assertEquals(new DataValue.BooleanValue(true), output.field("same"));
        assertEquals(left, output.field("left")); assertEquals(right, output.field("right"));
    }
    @Test void completeTypedValuesDoNotCoerceOrConfuseContainers() {
        var one = new DataValue.IntegerValue(1); var text = new DataValue.TextValue("1");
        var named = Map.<String, DataValue>of("a", new DataValue.ListValue(List.of(one)), "b", DataValue.NullValue.INSTANCE);
        for (var pair : List.of(List.<DataValue>of(one, text), List.<DataValue>of(one, new DataValue.DecimalValue(1)),
                List.<DataValue>of(new DataValue.DecimalValue(0.0), new DataValue.DecimalValue(-0.0)),
                List.<DataValue>of(new DataValue.RecordValue(named), new DataValue.MapValue(named)),
                List.<DataValue>of(new DataValue.ListValue(List.of(one, text)), new DataValue.ListValue(List.of(text, one))),
                List.<DataValue>of(new DataValue.ErrorValue("same", "a"), new DataValue.ErrorValue("same", "b")))) {
            var installed = installation(DataType.typeOf(pair.get(0)), DataType.typeOf(pair.get(1)), 100000);
            assertEquals(DataType.Scalar.BOOLEAN, installed.root().nodeTypes().get("same"));
            assertEquals(new DataValue.BooleanValue(false), run(pair.get(0), pair.get(1), true).output().orElseThrow().field("same"));
        }
        for (var value : List.<DataValue>of(DataValue.NullValue.INSTANCE, new DataValue.ErrorValue("code", "message"),
                new DataValue.ListValue(List.of()), new DataValue.RecordValue(Map.of()), new DataValue.MapValue(Map.of())))
            assertEquals(new DataValue.BooleanValue(true), run(value, value, true).output().orElseThrow().field("same"));
    }
    @Test void longTextKeysErrorsAndDeepMismatchAreBudgeted() {
        String prefix = "𝄞".repeat(60);
        for (var pair : List.of(List.<DataValue>of(new DataValue.TextValue(prefix), new DataValue.TextValue(new String(prefix))),
                List.<DataValue>of(new DataValue.TextValue(prefix + "a"), new DataValue.TextValue(prefix + "b")),
                List.<DataValue>of(new DataValue.ErrorValue(prefix, prefix), new DataValue.ErrorValue(new String(prefix), new String(prefix))),
                List.<DataValue>of(new DataValue.ErrorValue(prefix, prefix + "a"), new DataValue.ErrorValue(prefix, prefix + "b")),
                List.<DataValue>of(new DataValue.RecordValue(Map.of(prefix + "a", new DataValue.IntegerValue(1))), new DataValue.RecordValue(Map.of(prefix + "b", new DataValue.IntegerValue(1)))),
                List.<DataValue>of(new DataValue.MapValue(Map.of(prefix, new DataValue.IntegerValue(1))), new DataValue.MapValue(Map.of(new String(prefix), new DataValue.IntegerValue(1)))))) {
            var direct = run(pair.get(0), pair.get(1), false); var restored = run(pair.get(0), pair.get(1), true);
            assertEquals(direct, restored); assertTrue(direct.workUnits() >= prefix.length());
            assertEquals(new DataValue.BooleanValue(pair.get(0).equals(pair.get(1))), direct.output().orElseThrow().field("same"));
        }
        assertTrue(run(new DataValue.TextValue(prefix), new DataValue.TextValue(prefix), false).workUnits()
            > run(new DataValue.TextValue("x"), new DataValue.TextValue("x"), false).workUnits());
        DataValue left = new DataValue.IntegerValue(1), right = new DataValue.IntegerValue(2);
        for (int i = 0; i < 3000; i++) { left = new DataValue.ListValue(List.of(left)); right = new DataValue.ListValue(List.of(right)); }
        var failed = run(left, right, false); assertTrue(failed.workUnits() >= 9000);
        assertEquals(new DataValue.BooleanValue(false), failed.output().orElseThrow().field("same"));
    }
    @Test void pausedStepAndMemoryFailureRetainRamRatherThanReturnFalse() {
        var definition = new ProgramDefinition(graph(DataType.Scalar.ANY, DataType.Scalar.ANY),
            Map.of("log", new ProgramDefinition.Cell(DataType.Scalar.BOOLEAN, new DataValue.BooleanValue(false))), List.of(new ProgramDefinition.Write("log", "same")));
        var installed = ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(100, 100000, 1));
        var value = new DataValue.ListValue(List.of(new DataValue.TextValue("abc")));
        var input = new DataValue.RecordValue(Map.of("left", value, "right", new DataValue.ListValue(value.values())));
        var machine = new ComputationMachine(installed); machine.begin(input);
        while (machine.checkpoint().frames().stream().noneMatch(MachineCheckpoint.ValueEqualityState.class::isInstance)) machine.tick();
        var active = machine.checkpoint(); machine.setPaused(true); var paused = machine.checkpoint(); machine.tick(); assertEquals(paused, machine.checkpoint());
        machine.step(); assertEquals(paused.workUnits() + 1, machine.report().workUnits());
        var bounded = new ComputationMachine(ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(100, active.arenaUnits(), 1)));
        bounded.begin(input); while (bounded.report().status() == ComputationMachine.Status.RUNNING) bounded.tick();
        assertEquals(ComputationException.Code.MEMORY_LIMIT, bounded.report().diagnostic().orElseThrow().code());
        assertEquals(new DataValue.BooleanValue(false), bounded.memory().field("log")); assertTrue(bounded.report().output().isEmpty());
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> ComputationMachine.restore(installed,
            new MachineCheckpoint(active.memory(), active.status(), active.paused(), active.workUnits(), active.arenaUnits() - 1, active.output(), active.diagnostic(), active.frames()))).code());
    }
    @Test void errorsEvaluatingDependenciesStillFailTheEvent() {
        var graph = new ComputationGraph(Map.of("record", DataType.Scalar.ANY), Map.of("record", new ComputationGraph.Input("record"),
            "left", new ComputationGraph.Field("record", "absent"), "right", new ComputationGraph.Constant(DataValue.NullValue.INSTANCE),
            "same", new ComputationGraph.ValueEquals("left", "right")), Map.of("same", "same"));
        var installed = ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of()), Map.of(), new ResourceLimits(100, 10000, 1));
        var machine = new ComputationMachine(installed); machine.begin(new DataValue.RecordValue(Map.of("record", new DataValue.RecordValue(Map.of()))));
        while (machine.report().status() == ComputationMachine.Status.RUNNING) machine.tick();
        assertEquals(ComputationMachine.Status.FAILED, machine.report().status()); assertTrue(machine.report().output().isEmpty());
    }
    static ComputationMachine.Report run(DataValue left, DataValue right, boolean restore) {
        var installed = installation(DataType.Scalar.ANY, DataType.Scalar.ANY, 1000000);
        var machine = new ComputationMachine(installed); machine.begin(new DataValue.RecordValue(Map.of("left", left, "right", right)));
        for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) {
            assertTrue(guard < 50000);
            if (restore) {
                var snapshot = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime.Snapshot(UUID.randomUUID(),
                    new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(installed.definition(), Map.of()), machine.checkpoint(), Optional.empty(), Optional.empty());
                var limits = new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000, 1000000, 10000000);
                var decoded = com.nobodiiiii.createbiotech.content.biocomputing.persistence.ComputerNbtCodec.decode(
                    com.nobodiiiii.createbiotech.content.biocomputing.persistence.ComputerNbtCodec.encode(snapshot, limits), limits);
                machine = ComputationMachine.restore(installed, decoded.machine());
            }
            machine.tick();
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status()); return machine.report();
    }
    static ComputationGraph graph(DataType left, DataType right) {
        return new ComputationGraph(Map.of("left", left, "right", right),
            Map.of("left", new ComputationGraph.Input("left"), "right", new ComputationGraph.Input("right"),
                "same", new ComputationGraph.ValueEquals("left", "right")), Map.of("same", "same", "left", "left", "right", "right"));
    }
    static ProgramCompiler.InstalledProgram installation(DataType left, DataType right, long memory) {
        return ProgramCompiler.compile(new ProgramDefinition(graph(left, right), Map.of(), List.of()), Map.of(), new ResourceLimits(100, memory, 1));
    }
}
