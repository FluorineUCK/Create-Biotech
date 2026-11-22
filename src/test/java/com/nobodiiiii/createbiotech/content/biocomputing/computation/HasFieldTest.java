package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class HasFieldTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(100, 100_000, 1);
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(100_000, 1_000_000, 10_000_000);

    @Test void archivesGateEveryGraphAndRetainOldWriterSchemasAndSubgraphLayouts() {
        var graph = graph(DataType.Scalar.ANY, DataType.Scalar.TEXT);
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var key = new ModuleDefinition.Key("presence", 2);
        for (var archive : List.of(archive(graph),
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
                    Map.of("presence", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of()),
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of(key, new ModuleDefinition(key, graph))))) {
            var encoded = ProgramArchiveCodec.encode(archive);
            assertEquals(new DataValue.IntegerValue(19), encoded.field("version"));
            assertEquals(archive, ProgramArchiveCodec.decode(encoded));
            assertEquals(archive, ProgramEditorWire.decode(ProgramEditorWire.encode(archive, ARCHIVE), ARCHIVE));
            // Archives predating event entries do not interpret the entry field at all.
            for (int version = 3; version <= 18; version++) {
                var old = with(encoded, "version", new DataValue.IntegerValue(version));
                assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(old));
            }
        }
        var doc = new SubgraphDocument(key, Map.of(key, new ModuleDefinition(key, graph)),
            Map.of(ProgramDocument.graphId(key), Map.of("has", new ProgramDocument.Position(12, -20))));
        var encoded = SubgraphDocumentCodec.encode(doc);
        assertEquals(new DataValue.IntegerValue(17), encoded.field("graph_version"));
        assertEquals(doc, SubgraphDocumentCodec.decode(encoded));
        assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(with(encoded, "graph_version", new DataValue.IntegerValue(16))));
        var oldNodes = new LinkedHashMap<>(graph.nodes()); oldNodes.put("has", new ComputationGraph.DynamicField("record", "key"));
        var old = archive(new ComputationGraph(graph.inputs(), oldNodes, graph.outputs()));
        assertEquals(new DataValue.IntegerValue(18), ProgramArchiveCodec.encode(old).field("version"));
        assertEquals(old, ProgramArchiveCodec.decode(ProgramArchiveCodec.encode(old)));
        assertEquals(new DataValue.IntegerValue(12), ProgramArchiveCodec.encode(archive(empty)).field("version"));
        var raw = (DataValue.RecordValue)GraphArchiveCodec.encode(graph);
        var nodes = (DataValue.RecordValue)raw.field("nodes");
        var forged = with(raw, "nodes", with(nodes, "has", with((DataValue.RecordValue)nodes.field("has"), "literal", new DataValue.TextValue("x"))));
        assertThrows(ArchiveException.class, () -> GraphArchiveCodec.decode(forged, 17));
    }

    @Test void presentNullIsDifferentFromAnAbsentLiteralKey() {
        var source = new DataValue.RecordValue(Map.of("a.b", DataValue.NullValue.INSTANCE));
        var installed = archive(graph(DataType.Scalar.ANY, DataType.Scalar.TEXT)).compile(LIMITS);
        assertEquals(DataType.Scalar.BOOLEAN, installed.root().nodeTypes().get("has"));
        for (String name : List.of("a.b", "missing")) {
            var machine = new ComputationMachine(installed);
            machine.begin(inputs(source, new DataValue.TextValue(name))); finish(machine);
            assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
            assertEquals(new DataValue.BooleanValue(name.equals("a.b")), machine.report().output().orElseThrow().field("present"));
        }
        assertEquals(Map.of("a.b", DataValue.NullValue.INSTANCE), source.fields());
    }

    @Test void exactKeysInRecordsAndMapsIncludeEmptyNamesAndErrorValuesButNotNestedPaths() {
        for (String name : List.of("", " ", "a.b", "中文", "a\nb", "é", "e\u0301", "\uD800")) {
            Map<String, DataValue> fields = Map.of(name, new DataValue.ErrorValue("unavailable", "data"));
            for (DataValue source : List.of(new DataValue.RecordValue(fields), new DataValue.MapValue(fields))) {
                var installed = archive(graph(DataType.typeOf(source), DataType.Scalar.TEXT)).compile(LIMITS);
                assertEquals(DataType.Scalar.BOOLEAN, installed.root().nodeTypes().get("has"));
                var machine = new ComputationMachine(installed);
                machine.begin(inputs(source, new DataValue.TextValue(name))); finish(machine);
                assertEquals(new DataValue.BooleanValue(true), machine.report().output().orElseThrow().field("present"));
                var retained = source instanceof DataValue.RecordValue r ? r.fields() : ((DataValue.MapValue)source).entries();
                assertEquals(fields, retained); assertThrows(UnsupportedOperationException.class, retained::clear);
            }
        }
        for (String name : List.of("a.b", "é")) {
            var source = new DataValue.RecordValue(Map.of("a", new DataValue.RecordValue(Map.of("b", DataValue.NullValue.INSTANCE)),
                "e\u0301", DataValue.NullValue.INSTANCE));
            var machine = new ComputationMachine(archive(graph(DataType.Scalar.ANY, DataType.Scalar.TEXT)).compile(LIMITS));
            machine.begin(inputs(source, new DataValue.TextValue(name))); finish(machine);
            assertEquals(new DataValue.BooleanValue(false), machine.report().output().orElseThrow().field("present"));
        }
    }

    @Test void invalidSourceIncludingReadErrorsIsNotFalseAndDoesNotCommitMemory() {
        var any = DataType.Scalar.ANY;
        for (var types : List.of(List.of(DataType.Scalar.INTEGER, DataType.Scalar.TEXT),
                List.of(any, DataType.Scalar.INTEGER), List.of(new DataType.ListType(any), DataType.Scalar.TEXT)))
            assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class,
                () -> archive(graph(types.get(0), types.get(1))).compile(LIMITS)).code());
        var program = new ProgramDefinition(graph(any, any),
            Map.of("present", new ProgramDefinition.Cell(any, new DataValue.TextValue("unchanged"))),
            List.of(new ProgramDefinition.Write("present", "present")));
        var installed = ProgramCompiler.compile(program, Map.of(), LIMITS);
        for (var input : List.of(inputs(new DataValue.IntegerValue(1), new DataValue.TextValue("x")),
                inputs(new DataValue.ErrorValue("unloaded", "source"), new DataValue.TextValue("x")),
                inputs(DataValue.NullValue.INSTANCE, new DataValue.TextValue("x")),
                inputs(new DataValue.RecordValue(Map.of()), new DataValue.IntegerValue(0)),
                inputs(new DataValue.MapValue(Map.of()), DataValue.NullValue.INSTANCE))) {
            var machine = new ComputationMachine(installed); machine.begin(input); finish(machine);
            assertEquals(ComputationException.Code.TYPE_MISMATCH, machine.report().diagnostic().orElseThrow().code());
            assertEquals(new DataValue.TextValue("unchanged"), machine.memory().field("present"));
            assertTrue(machine.report().output().isEmpty());
        }
        assertThrows(ComputationException.class, () -> new ComputationGraph.HasField(" ", "name"));
        assertThrows(ComputationException.class, () -> new ComputationGraph.HasField("source", " "));
    }

    @Test void resultUsesOrdinaryMemoryAndWorkBudgetsRatherThanBypassingStateCommit() {
        var program = new ProgramDefinition(graph(DataType.Scalar.ANY, DataType.Scalar.TEXT),
            Map.of("present", new ProgramDefinition.Cell(DataType.Scalar.BOOLEAN, new DataValue.BooleanValue(false))),
            List.of(new ProgramDefinition.Write("present", "present")));
        var input = inputs(new DataValue.RecordValue(Map.of("x", DataValue.NullValue.INSTANCE)), new DataValue.TextValue("x"));
        var roomy = new ComputationMachine(ProgramCompiler.compile(program, Map.of(), LIMITS));
        roomy.begin(input); roomy.step(); roomy.step();
        var bounded = new ComputationMachine(ProgramCompiler.compile(program, Map.of(),
            new ResourceLimits(100, roomy.checkpoint().arenaUnits(), 1)));
        bounded.begin(input); bounded.step(); bounded.step(); bounded.step();
        assertEquals(ComputationException.Code.MEMORY_LIMIT, bounded.report().diagnostic().orElseThrow().code());
        assertEquals(new DataValue.BooleanValue(false), bounded.memory().field("present"));
        assertTrue(bounded.report().output().isEmpty());
        long work = roomy.report().workUnits(); roomy.step(); assertEquals(work + 1, roomy.report().workUnits());
        finish(roomy); assertEquals(new DataValue.BooleanValue(true), roomy.memory().field("present"));
    }

    @Test void computedNameThroughFixedSubgraphRestoresAPausedComputerAtEveryWorkBoundary() {
        var key = new ModuleDefinition.Key("presence", 2);
        var module = new ModuleDefinition(key, graph(DataType.Scalar.ANY, DataType.Scalar.TEXT));
        var root = new ComputationGraph(Map.of("record", DataType.Scalar.ANY, "suffix", DataType.Scalar.TEXT), Map.of(
            "source", new ComputationGraph.Input("record"), "prefix", new ComputationGraph.ReadMemory("prefix"),
            "suffix", new ComputationGraph.Input("suffix"),
            "key", new ComputationGraph.Text(TextOperation.CONCAT, Map.of("left", "prefix", "right", "suffix")),
            "call", new ComputationGraph.Call(key, Map.of("record", "source", "key", "key")),
            "result", new ComputationGraph.Field("call", "present")), Map.of("present", "result"));
        var program = new ProgramArchive(new ProgramDefinition(root,
            Map.of("prefix", new ProgramDefinition.Cell(DataType.Scalar.TEXT, new DataValue.TextValue("route."))), List.of()), Map.of(key, module));
        var source = new DataValue.RecordValue(Map.of("route.east", DataValue.NullValue.INSTANCE));
        var runtime = new ComputerRuntime(UUID.randomUUID(), program, LIMITS);
        runtime.begin(new DataValue.RecordValue(Map.of("record", source, "suffix", new DataValue.TextValue("east"))));
        for (int guard = 0; runtime.report().status() == ComputationMachine.Status.RUNNING; guard++) {
            assertTrue(guard < 100); runtime.setPaused(true);
            var before = runtime.snapshot();
            var decoded = ComputerNbtCodec.decode(ComputerNbtCodec.encode(before, ARCHIVE), ARCHIVE);
            assertEquals(before, decoded); runtime = ComputerRuntime.restore(decoded, LIMITS);
            runtime.tick(); assertEquals(before, runtime.snapshot());
            runtime.load(); runtime.tick(); assertEquals(before, runtime.snapshot());
            runtime.step(); assertEquals(before.machine().workUnits() + 1, runtime.report().workUnits());
        }
        assertEquals(new DataValue.BooleanValue(true), runtime.report().output().orElseThrow().field("present"));
        assertEquals(new DataValue.TextValue("route."), runtime.memory().field("prefix"));
        assertEquals(DataValue.NullValue.INSTANCE, source.field("route.east"));
    }

    @Test void existingLazyBranchCanGuardAnOptionalLookupWithoutEvaluatingTheAbsentField() {
        var present = new ModuleDefinition.Key("read", 1);
        var absent = new ModuleDefinition.Key("fallback", 1);
        Map<String, DataType> inputs = Map.of("record", DataType.Scalar.ANY, "key", DataType.Scalar.TEXT);
        var read = new ComputationGraph(inputs, Map.of("record", new ComputationGraph.Input("record"),
            "key", new ComputationGraph.Input("key"), "value", new ComputationGraph.DynamicField("record", "key")), Map.of("value", "value"));
        var fallback = new ComputationGraph(inputs, Map.of("value", new ComputationGraph.Constant(new DataValue.TextValue("absent"))), Map.of("value", "value"));
        var nodes = new LinkedHashMap<>(graph(DataType.Scalar.ANY, DataType.Scalar.TEXT).nodes());
        nodes.put("branch", new ComputationGraph.Branch("has", present, absent, Map.of("record", "record", "key", "key")));
        var program = new ProgramArchive(new ProgramDefinition(new ComputationGraph(inputs, nodes, Map.of("result", "branch")), Map.of(), List.of()),
            Map.of(present, new ModuleDefinition(present, read), absent, new ModuleDefinition(absent, fallback)));
        var installed = program.compile(LIMITS);
        for (String name : List.of("x", "missing")) {
            var machine = new ComputationMachine(installed);
            machine.begin(inputs(new DataValue.RecordValue(Map.of("x", DataValue.NullValue.INSTANCE)), new DataValue.TextValue(name))); finish(machine);
            assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
            var result = (DataValue.RecordValue)machine.report().output().orElseThrow().field("result");
            assertEquals(name.equals("x") ? DataValue.NullValue.INSTANCE : new DataValue.TextValue("absent"), result.field("value"));
        }
    }

    @Test void flowPresenceExpressionResumesWithoutChangingTheSourceOrSwallowingReadErrors() {
        var graph = graph(DataType.Scalar.ANY, DataType.Scalar.TEXT);
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var expressions = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
            Map.of("has", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(graph.inputs(), List.of(new FlowProgram.Evaluate("check", "has")), expressions);
        for (var original : List.of(
                inputs(new DataValue.MapValue(Map.of("a.b", DataValue.NullValue.INSTANCE)), new DataValue.TextValue("a.b")),
                inputs(new DataValue.MapValue(Map.of()), new DataValue.TextValue("a.b")),
                inputs(new DataValue.ErrorValue("unloaded", "source"), new DataValue.TextValue("a.b")))) {
            var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, original, LIMITS);
            for (int guard = 0; runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE && runtime.checkpoint().status() != FlowRuntime.Status.FAILED; guard++) {
                assertTrue(guard < 30); runtime.advance(1);
                var before = new FlowNbtCodec.Snapshot(program, runtime.checkpoint());
                var decoded = FlowNbtCodec.decode(FlowNbtCodec.encode(before, ARCHIVE), ARCHIVE); assertEquals(before, decoded);
                runtime = FlowRuntime.restore(decoded.program(), decoded.state(), LIMITS);
                assertEquals(0, runtime.advance(1)); assertEquals(before.state(), runtime.checkpoint()); runtime.load();
            }
            if (original.field("record") instanceof DataValue.MapValue map) {
                assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
                assertEquals(new DataValue.BooleanValue(map.entries().containsKey("a.b")), runtime.checkpoint().locals().field("present"));
                assertEquals(original.field("record"), runtime.checkpoint().locals().field("record"));
            } else {
                assertEquals(ComputationException.Code.TYPE_MISMATCH, runtime.checkpoint().diagnostic().orElseThrow().code());
                assertEquals(original, runtime.checkpoint().locals());
            }
        }
    }

    private static ComputationGraph graph(DataType source, DataType name) {
        return new ComputationGraph(Map.of("record", source, "key", name), Map.of(
            "record", new ComputationGraph.Input("record"), "key", new ComputationGraph.Input("key"),
            "has", new ComputationGraph.HasField("record", "key")), Map.of("present", "has"));
    }
    private static DataValue.RecordValue with(DataValue.RecordValue value, String name, DataValue field) {
        var fields = new LinkedHashMap<>(value.fields()); fields.put(name, field); return new DataValue.RecordValue(fields);
    }
    private static ProgramArchive archive(ComputationGraph graph) {
        return new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
    }
    private static DataValue.RecordValue inputs(DataValue source, DataValue name) {
        return new DataValue.RecordValue(Map.of("record", source, "key", name));
    }
    private static void finish(ComputationMachine machine) {
        for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) {
            assertTrue(guard < 100); machine.step();
        }
    }
}
