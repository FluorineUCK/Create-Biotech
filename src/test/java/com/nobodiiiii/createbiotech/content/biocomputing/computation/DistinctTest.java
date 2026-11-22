package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DistinctTest {
    @Test void archiveAndMachineRetainFirstOccurrenceAcrossEveryCheckpoint() {
        var graph = new ComputationGraph(Map.of("source", new DataType.ListType(DataType.Scalar.ANY)),
            Map.of("source", new ComputationGraph.Input("source"), "unique", new ComputationGraph.Distinct("source")),
            Map.of("result", "unique", "original", "source"));
        graph = GraphArchiveCodec.decode(GraphArchiveCodec.encode(graph), 14);
        var installed = ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of()), Map.of(), new ResourceLimits(100, 10000, 1));
        var source = ListEditTest.list(3, 1, 3, 2, 1);
        var machine = new ComputationMachine(installed);
        machine.begin(new DataValue.RecordValue(Map.of("source", source)));
        for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) {
            assertTrue(guard < 200);
            var checkpoint = machine.checkpoint();
            machine = ComputationMachine.restore(installed, checkpoint);
            assertEquals(checkpoint, machine.checkpoint());
            machine.tick();
            assertEquals(checkpoint.workUnits() + 1, machine.report().workUnits());
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
        assertEquals(ListEditTest.list(3, 1, 2), machine.report().output().orElseThrow().field("result"));
        assertEquals(source, machine.report().output().orElseThrow().field("original"));
        assertEquals(ListEditTest.list(3, 1, 3, 2, 1), source);
    }
    @Test void typedEqualityIncludesErrorsNullOrderedListsAndUnorderedNamedValues() {
        var fields = new LinkedHashMap<String, DataValue>(); fields.put("x", ListEditTest.list(1, 2)); fields.put("y", new DataValue.TextValue("same"));
        var reversed = new LinkedHashMap<String, DataValue>(); reversed.put("y", new DataValue.TextValue("same")); reversed.put("x", ListEditTest.list(1, 2));
        var first = List.<DataValue>of(new DataValue.IntegerValue(1), new DataValue.TextValue("1"), new DataValue.DecimalValue(1),
            new DataValue.DecimalValue(0.0), new DataValue.DecimalValue(-0.0), new DataValue.BooleanValue(true), DataValue.NullValue.INSTANCE,
            new DataValue.ErrorValue("code", "message"), new DataValue.RecordValue(fields), new DataValue.MapValue(fields),
            ListEditTest.list(1, 2), ListEditTest.list(2, 1));
        var values = new ArrayList<>(first); values.addAll(first); values.add(new DataValue.RecordValue(reversed)); values.add(new DataValue.MapValue(reversed));
        assertEquals(new DataValue.ListValue(first), run(new DataValue.ListValue(values), true).output().orElseThrow().field("result"));
        assertEquals(new DataValue.ListValue(List.of()), run(new DataValue.ListValue(List.of()), true).output().orElseThrow().field("result"));
    }
    static ComputationMachine.Report run(DataValue.ListValue source, boolean restore) {
        var graph = new ComputationGraph(Map.of("source", new DataType.ListType(DataType.Scalar.ANY)),
            Map.of("source", new ComputationGraph.Input("source"), "unique", new ComputationGraph.Distinct("source")), Map.of("result", "unique"));
        var installed = ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of()), Map.of(), new ResourceLimits(100, 1000000, 1));
        var machine = new ComputationMachine(installed); machine.begin(new DataValue.RecordValue(Map.of("source", source)));
        for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) {
            assertTrue(guard < 100000);
            if (restore) { var saved = machine.checkpoint(); machine = ComputationMachine.restore(installed, saved); assertEquals(saved, machine.checkpoint()); }
            machine.tick();
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status()); return machine.report();
    }
    @Test void longTextKeysAndErrorFieldsAreBudgetedAndRestoredWorkMatchesUninterrupted() {
        String prefix = "𝄞".repeat(80);
        for (var pair : List.of(
                List.<DataValue>of(new DataValue.TextValue(prefix), new DataValue.TextValue(new String(prefix))),
                List.<DataValue>of(new DataValue.TextValue(prefix + "a"), new DataValue.TextValue(prefix + "b")),
                List.<DataValue>of(new DataValue.ErrorValue(prefix, prefix), new DataValue.ErrorValue(new String(prefix), new String(prefix))),
                List.<DataValue>of(new DataValue.ErrorValue(prefix, prefix + "a"), new DataValue.ErrorValue(prefix, prefix + "b")),
                List.<DataValue>of(new DataValue.RecordValue(Map.of(prefix + "a", ListEditTest.list(1, 2))), new DataValue.RecordValue(Map.of(prefix + "b", ListEditTest.list(1, 2)))),
                List.<DataValue>of(new DataValue.MapValue(Map.of(prefix, ListEditTest.list(1, 2))), new DataValue.MapValue(Map.of(new String(prefix), ListEditTest.list(1, 2)))))) {
            var source = new DataValue.ListValue(pair); var direct = run(source, false); var restored = run(source, true);
            assertEquals(direct, restored); assertTrue(direct.workUnits() >= prefix.length());
            var expected = pair.get(0).equals(pair.get(1)) ? List.of(pair.getFirst()) : pair;
            assertEquals(new DataValue.ListValue(expected), direct.output().orElseThrow().field("result"));
        }
    }
    @Test void deeplyNestedComparisonAndFalseUnwindDoNotUseJavaCallStack() {
        DataValue a = new DataValue.IntegerValue(1), b = new DataValue.IntegerValue(1), c = new DataValue.IntegerValue(2);
        for (int i = 0; i < 3000; i++) { a = new DataValue.ListValue(List.of(a)); b = new DataValue.ListValue(List.of(b)); c = new DataValue.ListValue(List.of(c)); }
        var result = run(new DataValue.ListValue(List.of(a, b, c)), false);
        assertEquals(new DataValue.ListValue(List.of(a, c)), result.output().orElseThrow().field("result"));
        assertTrue(result.workUnits() >= 12000);
    }
}
