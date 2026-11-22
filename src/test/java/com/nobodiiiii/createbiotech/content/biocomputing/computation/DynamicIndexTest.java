package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.GraphArchiveCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DynamicIndexTest {
    @Test void selectedValueStillConsumesOrdinaryMemoryBudgetAndCannotPartiallyCommit() {
        var graph = graph(new DataType.ListType(DataType.Scalar.INTEGER), DataType.Scalar.INTEGER);
        var definition = new ProgramDefinition(graph, Map.of("chosen", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(99))),
            List.of(new ProgramDefinition.Write("chosen", "item")));
        var inputs = inputs(new DataValue.ListValue(List.of(new DataValue.IntegerValue(7))), new DataValue.IntegerValue(0));
        var roomy = new ComputationMachine(ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(30, 10_000, 1)));
        roomy.begin(inputs); roomy.step(); roomy.step();
        long onlyInputsFit = roomy.checkpoint().arenaUnits();
        var bounded = new ComputationMachine(ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(30, onlyInputsFit, 1)));
        bounded.begin(inputs); bounded.step(); bounded.step();
        assertEquals(ComputationMachine.Status.RUNNING, bounded.report().status());
        bounded.step();
        assertEquals(ComputationException.Code.MEMORY_LIMIT, bounded.report().diagnostic().orElseThrow().code());
        assertEquals(new DataValue.IntegerValue(99), bounded.memory().field("chosen"));
        assertTrue(bounded.report().output().isEmpty());
        finish(roomy); assertEquals(new DataValue.IntegerValue(7), roomy.memory().field("chosen"));
    }

    @Test void sequentialPureStepSavesEveryBoundaryWithoutRestartingAndFailureKeepsOriginalLocals() {
        var graph = graph(new DataType.ListType(DataType.Scalar.INTEGER), DataType.Scalar.INTEGER);
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var expressions = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
            Map.of("pick", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(graph.inputs(), List.of(new FlowProgram.Evaluate("select", "pick")), expressions);
        var limits = new ResourceLimits(40, 100_000, 1); var archive = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
        for (long index : new long[] {1, 1L << 32}) {
            var original = inputs(new DataValue.ListValue(List.of(new DataValue.IntegerValue(7), new DataValue.IntegerValue(19))), new DataValue.IntegerValue(index));
            var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, original, limits);
            for (int i = 0; runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE && runtime.checkpoint().status() != FlowRuntime.Status.FAILED; i++) {
                assertTrue(i < 30); runtime.advance(1);
                var before = new FlowNbtCodec.Snapshot(runtime.program(), runtime.checkpoint());
                var decoded = FlowNbtCodec.decode(FlowNbtCodec.encode(before, archive), archive);
                assertEquals(before, decoded);
                runtime = FlowRuntime.restore(decoded.program(), decoded.state(), limits);
                assertEquals(before.state(), runtime.checkpoint());
                assertEquals(0, runtime.advance(1), "An unloaded restored flow does not run"); runtime.load();
            }
            if (index == 1) {
                assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
                assertEquals(new DataValue.IntegerValue(19), runtime.checkpoint().locals().field("item"));
                assertEquals(original.field("items"), runtime.checkpoint().locals().field("items"));
            } else {
                assertEquals(ComputationException.Code.INDEX_OUT_OF_BOUNDS, runtime.checkpoint().diagnostic().orElseThrow().code());
                assertEquals(original, runtime.checkpoint().locals());
            }
        }
    }
    @Test void archiveVersionGatesRootsEntriesAndFixedModulesWithoutUpgradingOldPrograms() {
        var graph = graph(new DataType.ListType(DataType.Scalar.INTEGER), DataType.Scalar.INTEGER);
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of()); var key = new ModuleDefinition.Key("pick", 2);
        for (var archive : List.of(
                new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of()),
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
                    Map.of("select", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of()),
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of(key, new ModuleDefinition(key, graph))))) {
            var encoded = ProgramArchiveCodec.encode(archive);
            assertEquals(archive, ProgramArchiveCodec.decode(encoded));
            assertEquals(new DataValue.IntegerValue(13), encoded.field("version"));
            for (int version = 1; version <= 12; version++) {
                int v = version;
                var downgraded = with(encoded, "version", new DataValue.IntegerValue(v));
                if (v < 3 && !archive.program().entries().isEmpty())
                    assertTrue(ProgramArchiveCodec.decode(downgraded).program().entries().isEmpty(), "Historical schemas 1/2 do not import event entries at all");
                else assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(downgraded));
            }
            var bounds = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
            assertEquals(archive, ProgramEditorWire.decode(ProgramEditorWire.encode(archive, bounds), bounds));
        }
        var old = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(
            "list", new ComputationGraph.Constant(new DataValue.ListValue(List.of(new DataValue.IntegerValue(2)))),
            "pick", new ComputationGraph.Index("list", 0)), Map.of("item", "pick")), Map.of(), List.of()), Map.of());
        assertEquals(new DataValue.IntegerValue(12), ProgramArchiveCodec.encode(old).field("version"));
        assertEquals(old, ProgramArchiveCodec.decode(ProgramArchiveCodec.encode(old)));
        var doc = new SubgraphDocument(key, Map.of(key, new ModuleDefinition(key, graph)),
            Map.of(ProgramDocument.graphId(key), Map.of("pick", new ProgramDocument.Position(12, -20))));
        var data = SubgraphDocumentCodec.encode(doc);
        assertEquals(doc, SubgraphDocumentCodec.decode(data));
        assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(with(data, "graph_version", new DataValue.IntegerValue(10))));
    }

    @Test void preservesStructuredNullAndErrorElementsAsImmutableValues() {
        for (DataValue item : List.of(new DataValue.RecordValue(Map.of("name", new DataValue.TextValue("east"))),
                new DataValue.MapValue(Map.of("x", new DataValue.IntegerValue(1))),
                new DataValue.ListValue(List.of(new DataValue.IntegerValue(2))), DataValue.NullValue.INSTANCE,
                new DataValue.ErrorValue("sample", "plain data"))) {
            var source = new DataValue.ListValue(List.of(item)); var type = DataType.typeOf(source);
            var installed = compile(graph(type, DataType.Scalar.INTEGER));
            assertEquals(DataType.typeOf(item), installed.root().nodeTypes().get("pick"));
            var machine = new ComputationMachine(installed); machine.begin(inputs(source, new DataValue.IntegerValue(0))); finish(machine);
            assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
            assertEquals(item, machine.report().output().orElseThrow().field("item"));
            assertThrows(UnsupportedOperationException.class, () -> source.values().clear());
        }
    }

    @Test void boundsAreCheckedAsLongBeforeNarrowingAndFailedEventKeepsOldRam() {
        var graph = graph(new DataType.ListType(DataType.Scalar.INTEGER), DataType.Scalar.INTEGER);
        var definition = new ProgramDefinition(graph, Map.of("chosen", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(99))),
            List.of(new ProgramDefinition.Write("chosen", "item")));
        var installed = ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(30, 10_000, 1));
        for (long offset : new long[] {-1, Long.MIN_VALUE, 1, Integer.MAX_VALUE, 1L << 32, Long.MAX_VALUE}) {
            var machine = new ComputationMachine(installed); var list = new DataValue.ListValue(List.of(new DataValue.IntegerValue(7)));
            machine.begin(inputs(list, new DataValue.IntegerValue(0))); finish(machine);
            assertEquals(new DataValue.IntegerValue(7), machine.memory().field("chosen"));
            machine.begin(inputs(list, new DataValue.IntegerValue(offset))); finish(machine);
            assertEquals(ComputationException.Code.INDEX_OUT_OF_BOUNDS, machine.report().diagnostic().orElseThrow().code());
            assertEquals(new DataValue.IntegerValue(7), machine.memory().field("chosen"));
            assertTrue(machine.report().output().isEmpty());
        }
        var empty = new ComputationMachine(installed); empty.begin(inputs(new DataValue.ListValue(List.of()), new DataValue.IntegerValue(0))); finish(empty);
        assertEquals(ComputationException.Code.INDEX_OUT_OF_BOUNDS, empty.report().diagnostic().orElseThrow().code());
        assertEquals(new DataValue.IntegerValue(99), empty.memory().field("chosen"));
    }

    @Test void staticallyInvalidTypesFailAndAnyInputsAreCheckedAgainAtRuntime() {
        var integer = DataType.Scalar.INTEGER; var any = DataType.Scalar.ANY; var list = new DataType.ListType(integer);
        for (var types : List.of(List.of(integer, integer), List.of(list, DataType.Scalar.TEXT), List.of(any, DataType.Scalar.DECIMAL)))
            assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> compile(graph(types.get(0), types.get(1)))).code());
        var installed = compile(graph(any, any));
        assertEquals(any, installed.root().nodeTypes().get("pick"));
        for (var values : List.of(inputs(new DataValue.IntegerValue(1), new DataValue.IntegerValue(0)),
                inputs(new DataValue.ListValue(List.of()), new DataValue.DecimalValue(0)),
                inputs(new DataValue.ListValue(List.of()), DataValue.NullValue.INSTANCE))) {
            var machine = new ComputationMachine(installed); machine.begin(values); finish(machine);
            assertEquals(ComputationException.Code.TYPE_MISMATCH, machine.report().diagnostic().orElseThrow().code());
            assertTrue(machine.report().output().isEmpty());
        }
        assertThrows(ComputationException.class, () -> new ComputationGraph.DynamicIndex("source", " "));
        assertThrows(ComputationException.class, () -> new ComputationGraph.DynamicIndex(" ", "index"));
    }
    @Test void namedIntegerSelectsListItemAndEachBudgetStepCanBeRestored() {
        var graph = graph(new DataType.ListType(DataType.Scalar.INTEGER), DataType.Scalar.INTEGER);
        var installed = ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of()), Map.of(), new ResourceLimits(20, 10_000, 1));
        assertEquals(DataType.Scalar.INTEGER, installed.root().nodeTypes().get("pick"));
        var list = new DataValue.ListValue(List.of(new DataValue.IntegerValue(3), new DataValue.IntegerValue(7)));
        var machine = new ComputationMachine(installed);
        machine.begin(new DataValue.RecordValue(Map.of("items", list, "offset", new DataValue.IntegerValue(1))));
        for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) {
            assertTrue(guard < 30);
            var before = machine.checkpoint();
            machine = ComputationMachine.restore(installed, before);
            assertEquals(before, machine.checkpoint());
            machine.tick();
            assertEquals(before.workUnits() + 1, machine.report().workUnits());
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
        assertEquals(new DataValue.IntegerValue(7), machine.report().output().orElseThrow().field("item"));
        assertEquals(List.of(new DataValue.IntegerValue(3), new DataValue.IntegerValue(7)), list.values());
    }

    /** Public serialized node is the tracer bullet: absence fails as unsupported behavior, not Java compilation. */
    static ComputationGraph graph(DataType source, DataType offset) {
        var original = new ComputationGraph(Map.of("items", source, "offset", offset), Map.of(
            "items", new ComputationGraph.Input("items"), "offset", new ComputationGraph.Input("offset"),
            "pick", new ComputationGraph.Index("items", 0)), Map.of("item", "pick"));
        var encoded = (DataValue.RecordValue) GraphArchiveCodec.encode(original);
        var nodes = new LinkedHashMap<>(((DataValue.RecordValue)encoded.field("nodes")).fields());
        nodes.put("pick", new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("dynamic_index"),
            "source", new DataValue.TextValue("items"), "index", new DataValue.TextValue("offset"))));
        var fields = new LinkedHashMap<>(encoded.fields()); fields.put("nodes", new DataValue.RecordValue(nodes));
        return GraphArchiveCodec.decode(new DataValue.RecordValue(fields), GraphArchiveCodec.VERSION);
    }

    private static ProgramCompiler.InstalledProgram compile(ComputationGraph graph) {
        return ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of()), Map.of(), new ResourceLimits(30, 10_000, 1));
    }
    private static DataValue.RecordValue inputs(DataValue source, DataValue offset) {
        return new DataValue.RecordValue(Map.of("items", source, "offset", offset));
    }
    private static void finish(ComputationMachine machine) {
        for (int i = 0; machine.report().status() == ComputationMachine.Status.RUNNING; i++) { assertTrue(i < 50); machine.tick(); }
    }
    private static DataValue.RecordValue with(DataValue.RecordValue record, String key, DataValue value) {
        var fields = new LinkedHashMap<>(record.fields()); fields.put(key, value); return new DataValue.RecordValue(fields);
    }
}
