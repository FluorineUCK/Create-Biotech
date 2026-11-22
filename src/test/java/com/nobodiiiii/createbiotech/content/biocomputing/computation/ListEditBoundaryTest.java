package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ListEditTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ListEditBoundaryTest {
    private static ComputationGraph typedGraph(ListOperation op) { return graph(op.name(), op.inputs()); }
    private static Map<String, DataValue> arguments(ListOperation op, DataValue source, long index, long end) {
        var values = new LinkedHashMap<String, DataValue>(); values.put("source", source);
        if (op != ListOperation.APPEND) values.put(op == ListOperation.SLICE ? "start" : "index", number(index));
        if (op == ListOperation.SLICE) values.put("end", number(end));
        if (op.inputs().containsKey("value")) values.put("value", number(19));
        return values;
    }
    @ParameterizedTest @EnumSource(value=ListOperation.class, names="APPEND", mode=EnumSource.Mode.EXCLUDE)
    void outOfBoundsAndLongOverflowNeverPublishPartialRam(ListOperation op) {
        var definition = new ProgramDefinition(typedGraph(op), Map.of("log", new ProgramDefinition.Cell(new DataType.ListType(DataType.Scalar.ANY), list(99))),
            List.of(new ProgramDefinition.Write("log", "result")));
        var installed = ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(100, 10_000, 1));
        var machine = new ComputationMachine(installed);
        machine.begin(record(arguments(op, list(3, 7, 9), 1, 2))); finish(machine);
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
        var successful = machine.memory();
        long firstInvalid = op == ListOperation.REPLACE || op == ListOperation.REMOVE ? 3 : 4;
        for (long index : new long[] {-1, Long.MIN_VALUE, firstInvalid, 1L << 32, Long.MAX_VALUE}) {
            machine.begin(record(arguments(op, list(3, 7, 9), index, 3))); finish(machine);
            assertEquals(ComputationException.Code.INDEX_OUT_OF_BOUNDS, machine.report().diagnostic().orElseThrow().code());
            assertEquals(successful, machine.memory()); assertTrue(machine.report().output().isEmpty());
        }
        if (op == ListOperation.SLICE) for (long end : new long[] {-1, 0, 4, 1L << 32, Long.MAX_VALUE}) {
            machine.begin(record(arguments(op, list(3, 7, 9), 1, end))); finish(machine);
            assertEquals(ComputationException.Code.INDEX_OUT_OF_BOUNDS, machine.report().diagnostic().orElseThrow().code());
            assertEquals(successful, machine.memory());
        }
    }
    @ParameterizedTest @EnumSource(ListOperation.class)
    void emptySourcesHaveExplicitSemantics(ListOperation op) {
        var machine = new ComputationMachine(compile(typedGraph(op), 10_000));
        machine.begin(record(arguments(op, list(), 0, 0))); finish(machine);
        if (op == ListOperation.REPLACE || op == ListOperation.REMOVE)
            assertEquals(ComputationException.Code.INDEX_OUT_OF_BOUNDS, machine.report().diagnostic().orElseThrow().code());
        else assertEquals((op == ListOperation.INSERT || op == ListOperation.APPEND) ? list(19) : list(), machine.report().output().orElseThrow().field("result"));
    }
    @ParameterizedTest @EnumSource(ListOperation.class)
    void staticTypesAreCheckedAndDynamicInputsCannotBypassRuntimeChecks(ListOperation op) {
        for (String port : op.inputs().keySet()) {
            if (port.equals("value")) continue;
            var types = new LinkedHashMap<>(op.inputs()); types.put(port, DataType.Scalar.TEXT);
            assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> compile(graph(op.name(), types), 10_000)).code());
            types.replaceAll((name, ignored) -> DataType.Scalar.ANY);
            var machine = new ComputationMachine(compile(graph(op.name(), types), 10_000));
            var args = arguments(op, list(1, 2), 0, 1); args.put(port, new DataValue.TextValue("0"));
            machine.begin(record(args)); finish(machine);
            assertEquals(ComputationException.Code.TYPE_MISMATCH, machine.report().diagnostic().orElseThrow().code());
        }
    }
    @ParameterizedTest @EnumSource(ListOperation.class)
    void partialCopyConsumesMemoryAndCorruptCursorIsRejected(ListOperation op) {
        var definition = new ProgramDefinition(typedGraph(op), Map.of("log", new ProgramDefinition.Cell(new DataType.ListType(DataType.Scalar.ANY), list(99))),
            List.of(new ProgramDefinition.Write("log", "result")));
        var installed = ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(100, 100_000, 1));
        var inputs = record(arguments(op, new DataValue.ListValue(Collections.nCopies(64, number(3))), 0, 64));
        var roomy = new ComputationMachine(installed); roomy.begin(inputs);
        for (int i = 0; roomy.checkpoint().frames().stream().noneMatch(MachineCheckpoint.ListState.class::isInstance); i++) { assertTrue(i < 10); roomy.tick(); }
        var initial = roomy.checkpoint(); roomy.tick();
        assertTrue(roomy.checkpoint().arenaUnits() > initial.arenaUnits());
        assertEquals(list(99), roomy.memory().field("log"));
        var limited = new ComputationMachine(ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(100, initial.arenaUnits() + 1, 1)));
        limited.begin(inputs); finish(limited);
        assertEquals(ComputationException.Code.MEMORY_LIMIT, limited.report().diagnostic().orElseThrow().code());
        assertEquals(list(99), limited.memory().field("log")); assertTrue(limited.report().output().isEmpty());
        for (int index : new int[] {-1, 100, Integer.MAX_VALUE}) {
            var frames = List.of(initial.frames().getFirst(), new MachineCheckpoint.ListState(index));
            var corrupt = new MachineCheckpoint(initial.memory(), initial.status(), initial.paused(), initial.workUnits(), initial.arenaUnits(), initial.output(), initial.diagnostic(), frames);
            assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> ComputationMachine.restore(installed, corrupt)).code());
        }
        finish(roomy); assertEquals(ComputationMachine.Status.SUCCEEDED, roomy.report().status());
    }
    @ParameterizedTest @EnumSource(ListOperation.class)
    void flowEveryStepAndStandaloneSubgraphKeepNewOperationsAndFrozenSources(ListOperation op) {
        var graph = typedGraph(op); var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var expressions = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
            Map.of("edit", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(graph.inputs(), List.of(new FlowProgram.Evaluate("editList", "edit")), expressions);
        var limits = new ResourceLimits(100, 100_000, 1); var bounds = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
        for (long index : new long[] {1, 1L << 32}) {
            var inputs = record(arguments(op, list(3, 7, 9), index, 3));
            var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, inputs, limits);
            for (int i = 0; runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE && runtime.checkpoint().status() != FlowRuntime.Status.FAILED; i++) {
                assertTrue(i < 50); runtime.advance(1);
                var before = new FlowNbtCodec.Snapshot(runtime.program(), runtime.checkpoint());
                var restored = FlowNbtCodec.decode(FlowNbtCodec.encode(before, bounds), bounds); assertEquals(before, restored);
                runtime = FlowRuntime.restore(restored.program(), restored.state(), limits);
                assertEquals(0, runtime.advance(1)); runtime.load();
            }
            if (index == 1 || op == ListOperation.APPEND) {
                assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
                var direct = new ComputationMachine(compile(graph, 10_000)); direct.begin(inputs); finish(direct);
                assertEquals(direct.report().output().orElseThrow().field("result"), runtime.checkpoint().locals().field("result"));
                assertEquals(inputs.field("source"), runtime.checkpoint().locals().field("source"));
            } else {
                assertEquals(FlowRuntime.Status.FAILED, runtime.checkpoint().status());
                assertEquals(inputs, runtime.checkpoint().locals());
            }
        }
        var key = new ModuleDefinition.Key("list_edit", 4);
        var doc = new SubgraphDocument(key, Map.of(key, new ModuleDefinition(key, graph)), Map.of(ProgramDocument.graphId(key), Map.of("edited", new ProgramDocument.Position(34, -19))));
        var encoded = SubgraphDocumentCodec.encode(doc); assertEquals(number(op == ListOperation.APPEND ? 23 : 13), encoded.field("graph_version"));
        assertEquals(doc, SubgraphDocumentCodec.decode(encoded));
        var fields = new LinkedHashMap<>(encoded.fields()); fields.put("graph_version", number(op == ListOperation.APPEND ? 22 : 12));
        assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(record(fields)));
    }
    @Test void structuredReplacementPreservesValuesRatherThanWorldReferencesAndInfersHeterogeneousLists() {
        for (var value : List.of(record(Map.of("count", number(5))), new DataValue.MapValue(Map.of("x", number(1))),
                list(7, 8), DataValue.NullValue.INSTANCE, new DataValue.ErrorValue("sample", "data"))) {
            for (var op : List.of(ListOperation.REPLACE, ListOperation.INSERT)) {
                var types = new LinkedHashMap<>(op.inputs()); types.put("source", new DataType.ListType(DataType.Scalar.INTEGER)); types.put("value", DataType.typeOf(value));
                var installed = compile(graph(op.name(), types), 10_000); assertEquals(new DataType.ListType(DataType.Scalar.ANY), installed.root().nodeTypes().get("edited"));
                var args = arguments(op, list(1), 0, 0); args.put("value", value);
                var machine = new ComputationMachine(installed); machine.begin(record(args)); finish(machine);
                var result = (DataValue.ListValue)machine.report().output().orElseThrow().field("result"); assertEquals(value, result.values().getFirst());
                assertEquals(list(1), args.get("source")); assertThrows(UnsupportedOperationException.class, () -> result.values().clear());
            }
        }
    }
}
