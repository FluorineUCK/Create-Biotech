package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class KeysPersistenceTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(100, 100_000, 1);
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(100_000, 1_000_000, 10_000_000);

    @Test void gatesAllProgramGraphsAndFullSubgraphPackagesWithoutUpgradingOldPrograms() {
        var graph = keysGraph(); var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var key = new ModuleDefinition.Key("keys", 2);
        for (var archive : List.of(archive(graph),
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
                    Map.of("keys", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of()),
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of(key, new ModuleDefinition(key, graph))))) {
            var encoded = ProgramArchiveCodec.encode(archive);
            assertEquals(new DataValue.IntegerValue(21), encoded.field("version"));
            assertEquals(archive, ProgramArchiveCodec.decode(encoded));
            assertEquals(archive, ProgramEditorWire.decode(ProgramEditorWire.encode(archive, ARCHIVE), ARCHIVE));
            for (int version = 3; version <= 20; version++) {
                var old = with(encoded, "version", new DataValue.IntegerValue(version));
                assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(old));
            }
        }
        var root = new ModuleDefinition.Key("root", 1);
        var caller = new ComputationGraph(graph.inputs(), Map.of("input", new ComputationGraph.Input("source"),
            "call", new ComputationGraph.Call(key, Map.of("source", "input"))), Map.of("result", "call"));
        var doc = new SubgraphDocument(root, Map.of(root, new ModuleDefinition(root, caller), key, new ModuleDefinition(key, graph)),
            Map.of(ProgramDocument.graphId(key), Map.of("keys", new ProgramDocument.Position(12, -20))));
        var encoded = SubgraphDocumentCodec.encode(doc);
        assertEquals(new DataValue.IntegerValue(19), encoded.field("graph_version"));
        assertEquals(doc, SubgraphDocumentCodec.decode(encoded));
        assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(with(encoded, "graph_version", new DataValue.IntegerValue(18))));
        var old = archive(new ComputationGraph(graph.inputs(), Map.of("input", new ComputationGraph.Input("source"),
            "error", new ComputationGraph.Scalar(ScalarOperation.IS_ERROR, Map.of("value", "input"))), Map.of("result", "error")));
        assertEquals(new DataValue.IntegerValue(20), ProgramArchiveCodec.encode(old).field("version"));
        assertEquals(old, ProgramArchiveCodec.decode(ProgramArchiveCodec.encode(old)));
        assertEquals(new DataValue.IntegerValue(12), ProgramArchiveCodec.encode(archive(empty)).field("version"));
        var raw = (DataValue.RecordValue)GraphArchiveCodec.encode(graph);
        var nodes = (DataValue.RecordValue)raw.field("nodes");
        var forged = with(raw, "nodes", with(nodes, "keys", with((DataValue.RecordValue)nodes.field("keys"), "limit", new DataValue.IntegerValue(1))));
        assertThrows(ArchiveException.class, () -> GraphArchiveCodec.decode(forged, 19));
    }

    @Test void genericKeysMapAndDynamicFieldEnumerateUnknownRecordsAcrossEveryComputerAndFlowSaveBoundary() {
        var key = new ModuleDefinition.Key("entry", 3);
        var module = new ComputationGraph(Map.of("source", DataType.Scalar.ANY, "key", DataType.Scalar.TEXT), Map.of(
            "source", new ComputationGraph.Input("source"), "key", new ComputationGraph.Input("key"),
            "value", new ComputationGraph.DynamicField("source", "key"),
            "entry", new ComputationGraph.MakeRecord(Map.of("name", "key", "value", "value"))), Map.of("entry", "entry"));
        var nodes = new LinkedHashMap<>(keysGraph().nodes());
        nodes.put("map", new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP, "keys", key, "key", Map.of("source", "input"), "entry"));
        var graph = new ComputationGraph(keysGraph().inputs(), nodes, Map.of("entries", "map"));
        var modules = Map.of(key, new ModuleDefinition(key, module));
        var program = new ProgramArchive(new ProgramDefinition(graph, Map.of("saved", new ProgramDefinition.Cell(DataType.Scalar.ANY, DataValue.NullValue.INSTANCE)),
            List.of(new ProgramDefinition.Write("saved", "entries"))), modules);
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("z", new DataValue.IntegerValue(7)); fields.put("a.b", DataValue.NullValue.INSTANCE); fields.put("", new DataValue.ErrorValue("unavailable", ""));
        var expected = new DataValue.ListValue(fields.entrySet().stream().map(e -> (DataValue)new DataValue.RecordValue(Map.of(
            "name", new DataValue.TextValue(e.getKey()), "value", e.getValue()))).toList());
        for (DataValue source : List.of(new DataValue.RecordValue(fields), new DataValue.MapValue(fields))) {
            var inputs = new DataValue.RecordValue(Map.of("source", source));
            var computer = new ComputerRuntime(UUID.randomUUID(), program, LIMITS); computer.begin(inputs);
            boolean enumerating = false;
            for (int guard = 0; computer.report().status() == ComputationMachine.Status.RUNNING; guard++) {
                assertTrue(guard < 200); computer.setPaused(true); var before = computer.snapshot();
                enumerating |= before.machine().frames().stream().anyMatch(f -> f instanceof MachineCheckpoint.ListState l && l.index() > 0);
                var decoded = ComputerNbtCodec.decode(ComputerNbtCodec.encode(before, ARCHIVE), ARCHIVE); assertEquals(before, decoded);
                computer = ComputerRuntime.restore(decoded, LIMITS); computer.tick(); assertEquals(before, computer.snapshot());
                computer.load(); computer.tick(); assertEquals(before, computer.snapshot()); computer.step();
                assertEquals(before.machine().workUnits() + 1, computer.report().workUnits());
            }
            assertTrue(enumerating); assertEquals(ComputationMachine.Status.SUCCEEDED, computer.report().status());
            assertEquals(expected, computer.memory().field("saved"));
            var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
            var expressions = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
                Map.of("entries", new ProgramDefinition.Entry(graph, List.of(), List.of()))), modules);
            var flowProgram = new FlowProgram(graph.inputs(), List.of(new FlowProgram.Evaluate("enumerate", "entries")), expressions);
            var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), flowProgram, inputs, LIMITS);
            for (int guard = 0; flow.checkpoint().status() != FlowRuntime.Status.COMPLETE; guard++) {
                assertTrue(guard < 200); flow.advance(1);
                var before = new FlowNbtCodec.Snapshot(flowProgram, flow.checkpoint());
                var decoded = FlowNbtCodec.decode(FlowNbtCodec.encode(before, ARCHIVE), ARCHIVE); assertEquals(before, decoded);
                flow = FlowRuntime.restore(decoded.program(), decoded.state(), LIMITS);
                assertEquals(0, flow.advance(1)); assertEquals(before.state(), flow.checkpoint()); flow.load();
            }
            assertEquals(expected, flow.checkpoint().locals().field("entries"));
            assertEquals(source, flow.checkpoint().locals().field("source"));
            assertEquals(fields, source instanceof DataValue.RecordValue record ? record.fields() : ((DataValue.MapValue)source).entries());
        }
    }

    private static ComputationGraph keysGraph() {
        return new ComputationGraph(Map.of("source", DataType.Scalar.ANY), Map.of("input", new ComputationGraph.Input("source"),
            "keys", new ComputationGraph.Keys("input")), Map.of("keys", "keys"));
    }
    private static ProgramArchive archive(ComputationGraph graph) { return new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of()); }
    private static DataValue.RecordValue with(DataValue.RecordValue source, String key, DataValue value) {
        var fields = new LinkedHashMap<>(source.fields()); fields.put(key, value); return new DataValue.RecordValue(fields);
    }
}
