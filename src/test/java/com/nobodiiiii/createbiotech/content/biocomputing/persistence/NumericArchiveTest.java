package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NumericArchiveTest {
    @Test void independentSubgraphsWithoutNumericExtensionsKeepTheirPreviousWriterVersion() {
        var key = new ModuleDefinition.Key("legacy", 1);
        var dynamic = new ComputationGraph(Map.of(), Map.of(
            "items", new ComputationGraph.Constant(new DataValue.ListValue(List.of(new DataValue.IntegerValue(1)))),
            "offset", new ComputationGraph.Constant(new DataValue.IntegerValue(0)),
            "pick", new ComputationGraph.DynamicIndex("items", "offset")), Map.of("result", "pick"));
        var constant = new ComputationGraph(Map.of(), Map.of("value", new ComputationGraph.Constant(new DataValue.IntegerValue(1))), Map.of("result", "value"));
        for (var graph : List.of(constant, dynamic,
                NumericProgramFixtures.graph(ScalarOperation.ADD_DECIMAL), NumericProgramFixtures.graph(ScalarOperation.DECIMAL_TO_INTEGER))) {
            var document = new SubgraphDocument(key, Map.of(key, new ModuleDefinition(key, graph)), Map.of());
            var encoded = SubgraphDocumentCodec.encode(document);
            assertEquals(new DataValue.IntegerValue(11), encoded.field("graph_version"));
            assertEquals(document, SubgraphDocumentCodec.decode(encoded));
        }
    }

    @Test void newOperationOnlyInSubgraphDependencyStillSelectsNewVersion() {
        var root = new ModuleDefinition.Key("root", 1); var child = new ModuleDefinition.Key("child", 2);
        var graph = new ComputationGraph(Map.of(), Map.of(
            "input", new ComputationGraph.Constant(new DataValue.DecimalValue(9)),
            "call", new ComputationGraph.Call(child, Map.of("value", "input"))), Map.of("result", "call"));
        var document = new SubgraphDocument(root, Map.of(root, new ModuleDefinition(root, graph),
            child, new ModuleDefinition(child, NumericProgramFixtures.graph(ScalarOperation.SQRT_DECIMAL))), Map.of());
        var encoded = SubgraphDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(12), encoded.field("graph_version"));
        assertEquals(document, SubgraphDocumentCodec.decode(encoded));
        assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(with(encoded, "graph_version", 11)));
    }

    @Test void numericExtensionsRequireVersionFourteenInRootEntriesAndFixedModules() {
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var key = new ModuleDefinition.Key("numeric", 2);
        var limits = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
        for (var operation : NumericProgramFixtures.OPERATIONS) {
            var graph = NumericProgramFixtures.graph(operation);
            for (var archive : List.of(NumericProgramFixtures.archive(operation),
                    new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
                        Map.of("calculate", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of()),
                    new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of(key, new ModuleDefinition(key, graph))))) {
                var encoded = ProgramArchiveCodec.encode(archive);
                assertEquals(new DataValue.IntegerValue(14), encoded.field("version"), operation.name());
                assertEquals(archive, ProgramArchiveCodec.decode(encoded));
                assertEquals(archive, ProgramEditorWire.decode(ProgramEditorWire.encode(archive, limits), limits));
                for (int version = 1; version <= 13; version++) {
                    var downgraded = with(encoded, "version", version);
                    if (version < 3 && !archive.program().entries().isEmpty())
                        assertTrue(ProgramArchiveCodec.decode(downgraded).program().entries().isEmpty());
                    else assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(downgraded), operation + " version " + version);
                }
            }
            var document = new SubgraphDocument(key, Map.of(key, new ModuleDefinition(key, graph)),
                Map.of(ProgramDocument.graphId(key), Map.of("numeric", new ProgramDocument.Position(12, -20))));
            var encoded = SubgraphDocumentCodec.encode(document);
            assertEquals(document, SubgraphDocumentCodec.decode(encoded));
            for (int version = 1; version <= 11; version++) {
                var downgraded = with(encoded, "graph_version", version);
                assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(downgraded));
            }
        }
        var old = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of());
        assertEquals(new DataValue.IntegerValue(12), ProgramArchiveCodec.encode(old).field("version"));
    }
    private static DataValue.RecordValue with(DataValue.RecordValue record, String key, long version) {
        var fields = new LinkedHashMap<>(record.fields()); fields.put(key, new DataValue.IntegerValue(version));
        return new DataValue.RecordValue(fields);
    }
}
