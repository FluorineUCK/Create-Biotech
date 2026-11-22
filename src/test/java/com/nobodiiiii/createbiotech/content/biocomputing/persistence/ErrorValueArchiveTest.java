package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ErrorValueArchiveTest {
    @Test void errorsRequireNewGraphVersionInRootsEntriesAndFullSubgraphPackagesOnlyWhenUsed() {
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var key = new ModuleDefinition.Key("inspect_error", 2);
        var limits = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
        for (var operation : List.of(ScalarOperation.IS_ERROR, ScalarOperation.ERROR_CODE, ScalarOperation.ERROR_MESSAGE)) {
            var graph = new ComputationGraph(Map.of("value", DataType.Scalar.ANY), Map.of("input", new ComputationGraph.Input("value"),
                "result", new ComputationGraph.Scalar(operation, Map.of("value", "input"))), Map.of("result", "result"));
            for (var archive : List.of(new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of()),
                    new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
                        Map.of("inspect", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of()),
                    new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of(key, new ModuleDefinition(key, graph))))) {
                var encoded = ProgramArchiveCodec.encode(archive);
                assertEquals(new DataValue.IntegerValue(20), encoded.field("version"));
                assertEquals(archive, ProgramArchiveCodec.decode(encoded));
                assertEquals(archive, ProgramEditorWire.decode(ProgramEditorWire.encode(archive, limits), limits));
                for (int version = 3; version <= 19; version++) {
                    var old = with(encoded, "version", version);
                    assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(old));
                }
            }
            // The new scalar must win version selection even when a HasField is also present.
            var nodes = new LinkedHashMap<>(graph.nodes());
            nodes.put("key", new ComputationGraph.Constant(new DataValue.TextValue("optional")));
            nodes.put("has", new ComputationGraph.HasField("input", "key"));
            assertEquals(18, GraphArchiveCodec.encodingVersion(List.of(new ComputationGraph(graph.inputs(), nodes, graph.outputs()))));
            var root = new ModuleDefinition.Key("root", 1);
            var call = new ComputationGraph(graph.inputs(), Map.of("input", new ComputationGraph.Input("value"),
                "call", new ComputationGraph.Call(key, Map.of("value", "input"))), Map.of("result", "call"));
            var doc = new SubgraphDocument(root, Map.of(root, new ModuleDefinition(root, call), key, new ModuleDefinition(key, graph)),
                Map.of(ProgramDocument.graphId(key), Map.of("result", new ProgramDocument.Position(12, -20))));
            var encoded = SubgraphDocumentCodec.encode(doc);
            assertEquals(new DataValue.IntegerValue(18), encoded.field("graph_version"));
            assertEquals(doc, SubgraphDocumentCodec.decode(encoded));
            assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(with(encoded, "graph_version", 17)));
        }
        var old = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(
            "value", new ComputationGraph.Constant(DataValue.NullValue.INSTANCE),
            "null", new ComputationGraph.Scalar(ScalarOperation.IS_NULL, Map.of("value", "value"))), Map.of("result", "null")), Map.of(), List.of()), Map.of());
        assertEquals(new DataValue.IntegerValue(12), ProgramArchiveCodec.encode(old).field("version"));
        assertEquals(old, ProgramArchiveCodec.decode(ProgramArchiveCodec.encode(old)));
    }

    private static DataValue.RecordValue with(DataValue.RecordValue value, String key, long version) {
        var fields = new LinkedHashMap<>(value.fields()); fields.put(key, new DataValue.IntegerValue(version)); return new DataValue.RecordValue(fields);
    }
}
