package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.GraphArchiveCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DynamicFieldTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(100, 100_000, 1);
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(100_000, 1_000_000, 10_000_000);

    @Test void literalKeysPreserveNullErrorsAndStructuredDataWithoutPathInterpretation() {
        var values = List.of(DataValue.NullValue.INSTANCE, new DataValue.ErrorValue("sample", "data"),
            new DataValue.RecordValue(Map.of("nested", new DataValue.IntegerValue(7))),
            new DataValue.ListValue(List.of(new DataValue.TextValue("item"))));
        for (String key : List.of("", " ", "a.b", "中文", "a\nb", "é", "e\u0301", "\uD800")) {
            for (DataValue value : values) {
                var fields = Map.of(key, value);
                for (DataValue source : List.of(new DataValue.RecordValue(fields), new DataValue.MapValue(fields))) {
                    var installed = archive(graph(DataType.typeOf(source), DataType.Scalar.TEXT)).compile(LIMITS);
                    assertEquals(source instanceof DataValue.MapValue ? DataType.typeOf(value) : DataType.Scalar.ANY,
                        installed.root().nodeTypes().get("pick"));
                    var machine = new ComputationMachine(installed);
                    machine.begin(inputs(source, new DataValue.TextValue(key))); finish(machine);
                    assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
                    assertEquals(value, machine.report().output().orElseThrow().field("value"));
                    var map = source instanceof DataValue.RecordValue r ? r.fields() : ((DataValue.MapValue)source).entries();
                    assertThrows(UnsupportedOperationException.class, map::clear);
                }
            }
        }
        var machine = new ComputationMachine(archive(graph(DataType.Scalar.ANY, DataType.Scalar.TEXT)).compile(LIMITS));
        machine.begin(inputs(new DataValue.RecordValue(Map.of("a", new DataValue.RecordValue(Map.of("b", new DataValue.IntegerValue(7))))),
            new DataValue.TextValue("a.b"))); finish(machine);
        assertEquals(ComputationException.Code.MISSING_FIELD, machine.report().diagnostic().orElseThrow().code());
    }

    @Test void invalidTypesFailStaticallyOrDynamicallyAndMissingFieldsDoNotCommitRam() {
        var any = DataType.Scalar.ANY;
        for (var types : List.of(List.of(DataType.Scalar.INTEGER, DataType.Scalar.TEXT),
                List.of(any, DataType.Scalar.INTEGER), List.of(new DataType.ListType(any), DataType.Scalar.TEXT)))
            assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class,
                () -> archive(graph(types.get(0), types.get(1))).compile(LIMITS)).code());
        var program = new ProgramDefinition(graph(any, any), Map.of("chosen",
            new ProgramDefinition.Cell(any, new DataValue.IntegerValue(99))), List.of(new ProgramDefinition.Write("chosen", "value")));
        var installed = ProgramCompiler.compile(program, Map.of(), LIMITS);
        for (var input : List.of(inputs(new DataValue.IntegerValue(1), new DataValue.TextValue("x")),
                inputs(new DataValue.RecordValue(Map.of()), new DataValue.IntegerValue(0)),
                inputs(new DataValue.MapValue(Map.of()), DataValue.NullValue.INSTANCE),
                inputs(new DataValue.RecordValue(Map.of()), new DataValue.TextValue("missing")),
                inputs(new DataValue.MapValue(Map.of()), new DataValue.TextValue("missing")))) {
            var machine = new ComputationMachine(installed); machine.begin(input); finish(machine);
            var expected = input.field("key") instanceof DataValue.TextValue t && t.value().equals("missing")
                ? ComputationException.Code.MISSING_FIELD : ComputationException.Code.TYPE_MISMATCH;
            assertEquals(expected, machine.report().diagnostic().orElseThrow().code());
            assertEquals(new DataValue.IntegerValue(99), machine.memory().field("chosen"));
            assertTrue(machine.report().output().isEmpty());
        }
        assertThrows(ComputationException.class, () -> new ComputationGraph.DynamicField(" ", "name"));
        assertThrows(ComputationException.class, () -> new ComputationGraph.DynamicField("source", " "));
    }

    @Test void selectedValueUsesOrdinaryMemoryBudgetAndFailedLookupCannotPartiallyCommit() {
        var program = new ProgramDefinition(graph(DataType.Scalar.ANY, DataType.Scalar.TEXT),
            Map.of("chosen", new ProgramDefinition.Cell(DataType.Scalar.ANY, new DataValue.IntegerValue(99))),
            List.of(new ProgramDefinition.Write("chosen", "value")));
        var input = inputs(new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(7))), new DataValue.TextValue("x"));
        var roomy = new ComputationMachine(ProgramCompiler.compile(program, Map.of(), LIMITS));
        roomy.begin(input); roomy.step(); roomy.step();
        var bounded = new ComputationMachine(ProgramCompiler.compile(program, Map.of(),
            new ResourceLimits(100, roomy.checkpoint().arenaUnits(), 1)));
        bounded.begin(input); bounded.step(); bounded.step(); bounded.step();
        assertEquals(ComputationException.Code.MEMORY_LIMIT, bounded.report().diagnostic().orElseThrow().code());
        assertEquals(new DataValue.IntegerValue(99), bounded.memory().field("chosen"));
        assertTrue(bounded.report().output().isEmpty());
        finish(roomy); assertEquals(new DataValue.IntegerValue(7), roomy.memory().field("chosen"));
    }

    @Test void computedKeyAndPassedSubgraphArgumentsSurvivePausedComputerNbtAtEveryStep() {
        var key = new ModuleDefinition.Key("select", 2);
        var module = new ModuleDefinition(key, graph(DataType.Scalar.ANY, DataType.Scalar.TEXT));
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        nodes.put("source", new ComputationGraph.Input("record"));
        nodes.put("left", new ComputationGraph.ReadMemory("prefix"));
        nodes.put("right", new ComputationGraph.Input("suffix"));
        nodes.put("name", new ComputationGraph.Text(TextOperation.CONCAT, Map.of("left", "left", "right", "right")));
        nodes.put("call", new ComputationGraph.Call(key, Map.of("record", "source", "key", "name")));
        nodes.put("chosen", new ComputationGraph.Field("call", "value"));
        var root = new ComputationGraph(Map.of("record", DataType.Scalar.ANY, "suffix", DataType.Scalar.TEXT), nodes, Map.of("value", "chosen"));
        var program = new ProgramArchive(new ProgramDefinition(root,
            Map.of("prefix", new ProgramDefinition.Cell(DataType.Scalar.TEXT, new DataValue.TextValue("route."))), List.of()), Map.of(key, module));
        var source = new DataValue.RecordValue(Map.of("route.east", new DataValue.IntegerValue(7)));
        var runtime = new ComputerRuntime(UUID.randomUUID(), program, LIMITS);
        runtime.begin(new DataValue.RecordValue(Map.of("record", source, "suffix", new DataValue.TextValue("east"))));
        for (int guard = 0; runtime.report().status() == ComputationMachine.Status.RUNNING; guard++) {
            assertTrue(guard < 100); runtime.setPaused(true);
            var before = runtime.snapshot(); var decoded = ComputerNbtCodec.decode(ComputerNbtCodec.encode(before, ARCHIVE), ARCHIVE);
            assertEquals(before, decoded); runtime = ComputerRuntime.restore(decoded, LIMITS);
            assertEquals(before, runtime.snapshot()); runtime.tick(); assertEquals(before, runtime.snapshot());
            runtime.load(); runtime.tick(); assertEquals(before, runtime.snapshot());
            runtime.step(); assertEquals(before.machine().workUnits() + 1, runtime.report().workUnits());
        }
        assertEquals(new DataValue.IntegerValue(7), runtime.report().output().orElseThrow().field("value"));
        assertEquals(new DataValue.TextValue("route."), runtime.memory().field("prefix"));
        assertEquals(new DataValue.IntegerValue(7), source.field("route.east"));
    }

    @Test void sequentialNamedExpressionRestoresEveryBoundaryAndFailurePreservesLocals() {
        var graph = graph(DataType.Scalar.ANY, DataType.Scalar.TEXT);
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var expressions = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
            Map.of("pick", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(graph.inputs(), List.of(new FlowProgram.Evaluate("select", "pick")), expressions);
        for (String name : List.of("a.b", "missing")) {
            var original = inputs(new DataValue.MapValue(Map.of("a.b", DataValue.NullValue.INSTANCE)), new DataValue.TextValue(name));
            var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, original, LIMITS);
            for (int i = 0; runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE && runtime.checkpoint().status() != FlowRuntime.Status.FAILED; i++) {
                assertTrue(i < 30); runtime.advance(1);
                var before = new FlowNbtCodec.Snapshot(program, runtime.checkpoint());
                var decoded = FlowNbtCodec.decode(FlowNbtCodec.encode(before, ARCHIVE), ARCHIVE); assertEquals(before, decoded);
                runtime = FlowRuntime.restore(decoded.program(), decoded.state(), LIMITS);
                assertEquals(0, runtime.advance(1)); assertEquals(before.state(), runtime.checkpoint()); runtime.load();
            }
            if (name.equals("a.b")) {
                assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
                assertEquals(DataValue.NullValue.INSTANCE, runtime.checkpoint().locals().field("value"));
                assertEquals(original.field("record"), runtime.checkpoint().locals().field("record"));
            } else {
                assertEquals(ComputationException.Code.MISSING_FIELD, runtime.checkpoint().diagnostic().orElseThrow().code());
                assertEquals(original, runtime.checkpoint().locals());
            }
        }
    }

    @Test void archivesGateRootsEntriesAndFixedModulesAndKeepOldFieldVersion() {
        var graph = graph(DataType.Scalar.ANY, DataType.Scalar.TEXT);
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of()); var key = new ModuleDefinition.Key("select", 2);
        for (var archive : List.of(archive(graph),
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
                    Map.of("select", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of()),
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of(key, new ModuleDefinition(key, graph))))) {
            var encoded = ProgramArchiveCodec.encode(archive); assertEquals(new DataValue.IntegerValue(18), encoded.field("version"));
            assertEquals(archive, ProgramArchiveCodec.decode(encoded));
            assertEquals(archive, ProgramEditorWire.decode(ProgramEditorWire.encode(archive, ARCHIVE), ARCHIVE));
            for (int version = 1; version <= 17; version++) {
                var old = with(encoded, "version", new DataValue.IntegerValue(version));
                if (version < 3 && !archive.program().entries().isEmpty())
                    assertTrue(ProgramArchiveCodec.decode(old).program().entries().isEmpty());
                else assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(old));
            }
        }
        var doc = new SubgraphDocument(key, Map.of(key, new ModuleDefinition(key, graph)),
            Map.of(ProgramDocument.graphId(key), Map.of("pick", new ProgramDocument.Position(12, -20))));
        var encoded = SubgraphDocumentCodec.encode(doc); assertEquals(doc, SubgraphDocumentCodec.decode(encoded));
        assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(with(encoded, "graph_version", new DataValue.IntegerValue(15))));
        var old = archive(new ComputationGraph(graph.inputs(), Map.of("record", new ComputationGraph.Input("record"),
            "pick", new ComputationGraph.Field("record", "x")), graph.outputs()));
        assertEquals(new DataValue.IntegerValue(12), ProgramArchiveCodec.encode(old).field("version"));
        assertEquals(old, ProgramArchiveCodec.decode(ProgramArchiveCodec.encode(old)));
        var encodedGraph = (DataValue.RecordValue)GraphArchiveCodec.encode(graph);
        var nodes = (DataValue.RecordValue)encodedGraph.field("nodes");
        var forged = with(encodedGraph, "nodes", with(nodes, "pick", with((DataValue.RecordValue)nodes.field("pick"), "literal", new DataValue.TextValue("x"))));
        assertThrows(ArchiveException.class, () -> GraphArchiveCodec.decode(forged, 16));
    }
    @Test void namedTextSelectsADataFieldAndEveryBudgetStepRestoresWithoutChangingTheSource() {
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("left", new DataValue.IntegerValue(3)); fields.put("right", new DataValue.IntegerValue(7));
        for (DataValue source : List.of(new DataValue.RecordValue(fields), new DataValue.MapValue(fields))) {
            var graph = graph(DataType.typeOf(source), DataType.Scalar.TEXT);
            var installed = ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of()), Map.of(), new ResourceLimits(30, 10000, 1));
            var machine = new ComputationMachine(installed);
            machine.begin(inputs(source, new DataValue.TextValue("right")));
            for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) {
                assertTrue(guard < 30);
                var saved = machine.checkpoint(); machine = ComputationMachine.restore(installed, saved);
                assertEquals(saved, machine.checkpoint()); machine.tick();
                assertEquals(saved.workUnits() + 1, machine.report().workUnits());
            }
            assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
            assertEquals(new DataValue.IntegerValue(7), machine.report().output().orElseThrow().field("value"));
            assertEquals(new DataValue.IntegerValue(7), source instanceof DataValue.RecordValue r ? r.field("right") : ((DataValue.MapValue)source).field("right"));
        }
    }

    static ComputationGraph graph(DataType source, DataType name) {
        var original = new ComputationGraph(Map.of("record", source, "key", name), Map.of(
            "record", new ComputationGraph.Input("record"), "key", new ComputationGraph.Input("key"),
            "pick", new ComputationGraph.Field("record", "right")), Map.of("value", "pick"));
        var data = (DataValue.RecordValue)GraphArchiveCodec.encode(original);
        var nodes = new LinkedHashMap<>(((DataValue.RecordValue)data.field("nodes")).fields());
        nodes.put("pick", new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("dynamic_field"),
            "source", new DataValue.TextValue("record"), "name", new DataValue.TextValue("key"))));
        var fields = new LinkedHashMap<>(data.fields()); fields.put("nodes", new DataValue.RecordValue(nodes));
        return GraphArchiveCodec.decode(new DataValue.RecordValue(fields), GraphArchiveCodec.VERSION);
    }
    private static DataValue.RecordValue inputs(DataValue source, DataValue name) {
        return new DataValue.RecordValue(Map.of("record", source, "key", name));
    }
    private static ProgramArchive archive(ComputationGraph graph) {
        return new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
    }
    private static void finish(ComputationMachine machine) {
        for (int i = 0; machine.report().status() == ComputationMachine.Status.RUNNING; i++) { assertTrue(i < 100); machine.tick(); }
    }
    private static DataValue.RecordValue with(DataValue.RecordValue record, String key, DataValue value) {
        var fields = new LinkedHashMap<>(record.fields()); fields.put(key, value); return new DataValue.RecordValue(fields);
    }
}
