package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class KeysTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(100, 100_000, 1);

    @Test void enumeratesExactNamesOfRecordsAndMapsWithoutDependingOnTheirValues() {
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("z", new DataValue.IntegerValue(2)); fields.put("", DataValue.NullValue.INSTANCE);
        fields.put("a.b", new DataValue.ErrorValue("unloaded", "")); fields.put("中文", new DataValue.ListValue(List.of()));
        fields.put("e\u0301", new DataValue.TextValue("value")); fields.put("\uD800", new DataValue.RecordValue(Map.of()));
        var expected = new DataValue.ListValue(fields.keySet().stream().map(name -> (DataValue)new DataValue.TextValue(name)).toList());
        for (DataValue source : List.of(new DataValue.RecordValue(fields), new DataValue.MapValue(fields))) {
            for (var type : List.of(DataType.Scalar.ANY, DataType.typeOf(source))) {
                var installed = ProgramCompiler.compile(new ProgramDefinition(graph(type), Map.of(), List.of()), Map.of(), LIMITS);
                assertEquals(new DataType.ListType(DataType.Scalar.TEXT), installed.root().nodeTypes().get("keys"));
                var machine = new ComputationMachine(installed); machine.begin(input(source)); finish(machine);
                assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
                assertEquals(expected, machine.report().output().orElseThrow().field("keys"));
                var result = (DataValue.ListValue)machine.report().output().orElseThrow().field("keys");
                assertThrows(UnsupportedOperationException.class, () -> result.values().clear());
            }
            assertEquals(fields, source instanceof DataValue.RecordValue record ? record.fields() : ((DataValue.MapValue)source).entries());
        }
    }

    @Test void emptyContainersAreEmptyButInvalidSourcesFailAndNeverCommitRam() {
        for (DataValue source : List.of(new DataValue.RecordValue(Map.of()), new DataValue.MapValue(Map.of()),
                DataValue.NullValue.INSTANCE, new DataValue.ErrorValue("unloaded", "not an empty record"),
                new DataValue.TextValue("{}"), new DataValue.ListValue(List.of()), new DataValue.IntegerValue(0))) {
            boolean valid = source instanceof DataValue.RecordValue || source instanceof DataValue.MapValue;
            if (!valid) assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class,
                () -> ProgramCompiler.compile(writing(graph(DataType.typeOf(source))), Map.of(), LIMITS)).code());
            var machine = new ComputationMachine(ProgramCompiler.compile(writing(graph(DataType.Scalar.ANY)), Map.of(), LIMITS));
            machine.begin(input(source)); finish(machine);
            if (valid) {
                assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
                assertEquals(new DataValue.ListValue(List.of()), machine.memory().field("saved"));
            } else {
                assertEquals(ComputationException.Code.TYPE_MISMATCH, machine.report().diagnostic().orElseThrow().code());
                assertEquals(new DataValue.TextValue("unchanged"), machine.memory().field("saved"));
                assertTrue(machine.report().output().isEmpty());
            }
        }
    }

    @Test void enumeratesOneKeyPerWorkUnitAndRejectsBadContinuationOrMemoryOverflow() {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < 30; i++) fields.put("key" + i, DataValue.NullValue.INSTANCE);
        var source = new DataValue.RecordValue(fields);
        var program = writing(graph(DataType.Scalar.ANY)); var installed = ProgramCompiler.compile(program, Map.of(), LIMITS);
        var machine = new ComputationMachine(installed); machine.begin(input(source)); machine.step(); machine.step();
        var initial = machine.checkpoint(); assertEquals(new MachineCheckpoint.ListState(0), initial.frames().getLast());
        for (int cursor = 1; cursor <= fields.size(); cursor++) {
            long previous = machine.report().workUnits(); machine.step();
            assertEquals(previous + 1, machine.report().workUnits());
            assertEquals(new MachineCheckpoint.ListState(cursor), machine.checkpoint().frames().getLast());
            assertTrue(machine.report().output().isEmpty());
        }
        for (int bad : List.of(-1, fields.size() + 1)) {
            var frames = new ArrayList<>(initial.frames()); frames.set(frames.size() - 1, new MachineCheckpoint.ListState(bad));
            var forged = new MachineCheckpoint(initial.memory(), initial.status(), initial.paused(), initial.workUnits(), initial.arenaUnits(),
                initial.output(), initial.diagnostic(), frames);
            assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
                () -> ComputationMachine.restore(installed, forged)).code());
        }
        var bounded = new ComputationMachine(ProgramCompiler.compile(program, Map.of(), new ResourceLimits(100, initial.arenaUnits(), 1)));
        bounded.begin(input(source)); finish(bounded);
        assertEquals(ComputationException.Code.MEMORY_LIMIT, bounded.report().diagnostic().orElseThrow().code());
        assertEquals(new DataValue.TextValue("unchanged"), bounded.memory().field("saved"));
        assertTrue(bounded.report().output().isEmpty()); finish(machine);
        assertEquals(fields.size(), ((DataValue.ListValue)machine.memory().field("saved")).values().size());
        assertEquals(fields, source.fields());
    }

    private static ProgramDefinition writing(ComputationGraph graph) {
        return new ProgramDefinition(graph, Map.of("saved", new ProgramDefinition.Cell(DataType.Scalar.ANY, new DataValue.TextValue("unchanged"))),
            List.of(new ProgramDefinition.Write("saved", "keys")));
    }
    private static ComputationGraph graph(DataType source) {
        return new ComputationGraph(Map.of("source", source), Map.of("input", new ComputationGraph.Input("source"),
            "keys", new ComputationGraph.Keys("input")), Map.of("keys", "keys"));
    }
    private static DataValue.RecordValue input(DataValue source) { return new DataValue.RecordValue(Map.of("source", source)); }
    private static void finish(ComputationMachine machine) {
        for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) { assertTrue(guard < 100); machine.step(); }
    }
}
