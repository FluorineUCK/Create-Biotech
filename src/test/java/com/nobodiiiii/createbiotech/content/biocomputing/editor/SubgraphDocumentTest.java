package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.SubgraphWorkspaceTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SubgraphDocumentTest {
    @Test void fullMixedReferencePackageRoundTripsExactlyAsValuesNbtAndBoundedWire() {
        var document = fixture();
        assertEquals(8, document.modules().size());
        var value = SubgraphDocumentCodec.encode(document);
        assertEquals(Set.of("version", "graph_version", "root", "modules", "layouts"), value.fields().keySet());
        assertEquals(document, SubgraphDocumentCodec.decode(value));
        assertEquals(document, SubgraphDocumentCodec.decode(ValueNbtCodec.decode(ValueNbtCodec.encode(value, LIMITS), LIMITS)));
        assertEquals(document, SubgraphDocumentWire.decode(SubgraphDocumentWire.encode(document, LIMITS), LIMITS));
    }
    @Test void containersAndLayoutsAreImmutableCopies() {
        var source = fixture(); var modules = new LinkedHashMap<>(source.modules());
        var positions = new LinkedHashMap<>(source.positions());
        var document = new SubgraphDocument(source.root(), modules, positions);
        modules.clear(); positions.clear(); assertEquals(source, document);
        assertThrows(UnsupportedOperationException.class, () -> document.modules().clear());
        assertThrows(UnsupportedOperationException.class, () -> document.positions().clear());
        assertThrows(UnsupportedOperationException.class, () -> document.positions().values().iterator().next().clear());
    }
    @Test void incompleteMismatchedAndExtraDependencyDefinitionsAreRejected() {
        var document = fixture(); var missing = new LinkedHashMap<>(document.modules()); missing.remove(new ModuleDefinition.Key("leaf", 99));
        assertThrows(ComputationException.class, () -> new SubgraphDocument(document.root(), missing, Map.of()));
        var mismatch = new LinkedHashMap<>(document.modules()); mismatch.put(document.root(), new ModuleDefinition(new ModuleDefinition.Key("wrong", 1), numberGraph(1)));
        assertThrows(ComputationException.class, () -> new SubgraphDocument(document.root(), mismatch, Map.of()));
        var extra = new LinkedHashMap<>(document.modules()); var unused = new ModuleDefinition.Key("extra", 1);
        extra.put(unused, new ModuleDefinition(unused, numberGraph(1)));
        assertThrows(IllegalArgumentException.class, () -> new SubgraphDocument(document.root(), extra, Map.of()));
    }
    @Test void recursionCyclesUnknownReferencesAndMemoryAreRejectedEvenWhenNotAnOutput() {
        var root = new ModuleDefinition.Key("bad", 1);
        var reference = new ComputationGraph.Call(root, Map.of());
        assertEquals(ComputationException.Code.RECURSIVE_MODULE, assertThrows(ComputationException.class,
            () -> document(root, new ComputationGraph(Map.of(), Map.of("self", reference), Map.of("out", "self")))).code());
        var cyclic = new ComputationGraph(Map.of(), Map.of("a", new ComputationGraph.Field("b", "x"), "b", new ComputationGraph.Field("a", "x")), Map.of("out", "a"));
        assertEquals(ComputationException.Code.GRAPH_CYCLE, assertThrows(ComputationException.class, () -> document(root, cyclic)).code());
        var hiddenMemory = new LinkedHashMap<>(numberGraph(1).nodes()); hiddenMemory.put("hidden", new ComputationGraph.ReadMemory("secret"));
        assertEquals(ComputationException.Code.INVALID_GRAPH, assertThrows(ComputationException.class,
            () -> document(root, new ComputationGraph(Map.of(), hiddenMemory, Map.of("result", "value")))).code());
        var missingNode = new ComputationGraph(Map.of(), Map.of("field", new ComputationGraph.Field("absent", "x")), Map.of("out", "field"));
        assertThrows(ComputationException.class, () -> document(root, missingNode));
    }
    @Test void unknownLayoutAndDuplicateRowsAndEnvelopeFieldsAreRejected() {
        var document = fixture();
        assertThrows(IllegalArgumentException.class, () -> new SubgraphDocument(document.root(), document.modules(), Map.of("root", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> new SubgraphDocument(document.root(), document.modules(), Map.of(ProgramDocument.graphId(document.root()), Map.of("absent", new ProgramDocument.Position(0, 0)))));
        var value = SubgraphDocumentCodec.encode(document); var fields = new LinkedHashMap<>(value.fields());
        fields.put("extra", DataValue.NullValue.INSTANCE); assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(new DataValue.RecordValue(fields)));
        fields.remove("extra"); var modules = new ArrayList<>(((DataValue.ListValue)fields.get("modules")).values()); modules.add(modules.getFirst());
        fields.put("modules", new DataValue.ListValue(modules)); assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(new DataValue.RecordValue(fields)));
        var layoutFields = new LinkedHashMap<>(value.fields()); var layouts = ((DataValue.ListValue)layoutFields.get("layouts")).values();
        var duplicate = new LinkedHashMap<>(layoutFields); duplicate.put("layouts", new DataValue.ListValue(List.of(layouts.getFirst(), layouts.getFirst())));
        assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(new DataValue.RecordValue(duplicate)));
    }
    @Test void unsupportedVersionsAndTruncatedOrOversizedTransportFailExplicitly() {
        var document = fixture(); var encoded = SubgraphDocumentCodec.encode(document);
        for (var key : List.of("version", "graph_version")) {
            var fields = new LinkedHashMap<>(encoded.fields()); fields.put(key, new DataValue.IntegerValue(999));
            assertEquals(ArchiveException.Code.UNSUPPORTED_VERSION, assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(new DataValue.RecordValue(fields))).code());
        }
        assertThrows(IllegalArgumentException.class, () -> SubgraphDocumentWire.decode(new byte[ProgramEditorWire.MAX_BYTES + 1], LIMITS));
        var bytes = SubgraphDocumentWire.encode(document, LIMITS);
        assertThrows(IllegalArgumentException.class, () -> SubgraphDocumentWire.decode(Arrays.copyOf(bytes, bytes.length - 1), LIMITS));
        assertThrows(ArchiveException.class, () -> SubgraphDocumentWire.encode(document, new ArchiveLimits(1, 1, 1)));
    }
    @Test void graphCodecIsTheSameVersionedRepresentationAsProgramsIncludingNonJsonValues() {
        var key = new ModuleDefinition.Key("literal", 1);
        var payload = new DataValue.MapValue(Map.of("a", new DataValue.ErrorValue("absent", "kept as data")));
        var graph = new ComputationGraph(Map.of(), Map.of("value", new ComputationGraph.Constant(payload)), Map.of("result", "value"));
        var document = document(key, graph);
        assertEquals(document, SubgraphDocumentWire.decode(SubgraphDocumentWire.encode(document, LIMITS), LIMITS));
        var program = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
        assertEquals(ProgramArchiveCodec.encode(program).field("graph"), GraphArchiveCodec.encode(graph));
        for (int version = 1; version <= 8; version++) assertEquals(graph, GraphArchiveCodec.decode(GraphArchiveCodec.encode(graph), version));
    }
    static SubgraphDocument document(ModuleDefinition.Key key, ComputationGraph graph) {
        return new SubgraphDocument(key, Map.of(key, new ModuleDefinition(key, graph)), Map.of());
    }
    static ComputationGraph numberGraph(long value) {
        return new ComputationGraph(Map.of(), Map.of("value", new ComputationGraph.Constant(new DataValue.IntegerValue(value))), Map.of("result", "value"));
    }
    static SubgraphDocument fixture() {
        var root = new ModuleDefinition.Key("root_package", 7); var leaf = new ModuleDefinition.Key("leaf", 4);
        var other = new ModuleDefinition.Key("leaf", 99); var grand = new ModuleDefinition.Key("grand", 2);
        var alternate = new ModuleDefinition.Key("alternate", 1); var mapper = new ModuleDefinition.Key("map", 3);
        var predicate = new ModuleDefinition.Key("filter", 2); var sum = new ModuleDefinition.Key("sum", 6);
        var modules = new LinkedHashMap<ModuleDefinition.Key, ModuleDefinition>();
        modules.put(grand, new ModuleDefinition(grand, numberGraph(5))); modules.put(other, new ModuleDefinition(other, numberGraph(99)));
        modules.put(alternate, new ModuleDefinition(alternate, numberGraph(9)));
        modules.put(leaf, new ModuleDefinition(leaf, new ComputationGraph(Map.of(), Map.of("call", new ComputationGraph.Call(grand, Map.of()), "field", new ComputationGraph.Field("call", "result")), Map.of("result", "field"))));
        modules.put(mapper, new ModuleDefinition(mapper, new ComputationGraph(Map.of("item", DataType.Scalar.INTEGER), Map.of("i", new ComputationGraph.Input("item")), Map.of("result", "i"))));
        modules.put(predicate, new ModuleDefinition(predicate, new ComputationGraph(Map.of("item", DataType.Scalar.INTEGER), Map.of("i", new ComputationGraph.Input("item"), "zero", new ComputationGraph.Constant(new DataValue.IntegerValue(0)), "eq", new ComputationGraph.Binary(ComputationGraph.BinaryOp.EQUAL_INTEGER, "i", "zero")), Map.of("result", "eq"))));
        modules.put(sum, new ModuleDefinition(sum, new ComputationGraph(Map.of("item", DataType.Scalar.INTEGER, "acc", DataType.Scalar.INTEGER), Map.of("i", new ComputationGraph.Input("item"), "a", new ComputationGraph.Input("acc"), "add", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "i", "a")), Map.of("result", "add"))));
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        nodes.put("items", new ComputationGraph.Constant(new DataValue.ListValue(List.of(new DataValue.IntegerValue(1), new DataValue.IntegerValue(2)))));
        nodes.put("flag", new ComputationGraph.Constant(new DataValue.BooleanValue(true))); nodes.put("zero", new ComputationGraph.Constant(new DataValue.IntegerValue(0)));
        nodes.put("call", new ComputationGraph.Call(leaf, Map.of()));
        nodes.put("branch", new ComputationGraph.Branch("flag", leaf, alternate, Map.of()));
        nodes.put("map", new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP, "items", mapper, "item", Map.of(), "result"));
        nodes.put("filter", new ComputationGraph.Iterate(ComputationGraph.Iteration.FILTER, "items", predicate, "item", Map.of(), "result"));
        nodes.put("fold", new ComputationGraph.Fold("items", "zero", sum, "item", "acc", Map.of(), "result"));
        nodes.put("unconnected_call", new ComputationGraph.Call(other, Map.of()));
        modules.put(root, new ModuleDefinition(root, new ComputationGraph(Map.of(), nodes, Map.of("called", "call", "branched", "branch", "mapped", "map", "filtered", "filter", "folded", "fold"))));
        return new SubgraphDocument(root, modules, Map.of(ProgramDocument.graphId(root), Map.of("flag", new ProgramDocument.Position(-12.5, 200.25), "unconnected_call", new ProgramDocument.Position(19, 23))));
    }
}
