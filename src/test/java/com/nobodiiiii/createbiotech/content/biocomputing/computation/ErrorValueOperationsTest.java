package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ErrorValueOperationsTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(100, 100_000, 1);
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(100_000, 1_000_000, 10_000_000);

    @Test void isErrorInspectsOnlyTheTypedValueNotNullTextOrARecordWithSimilarFields() {
        var operation = ScalarOperation.valueOf("IS_ERROR");
        var error = new DataValue.ErrorValue("unsupported", "not available");
        for (DataValue value : List.of(error, DataValue.NullValue.INSTANCE, new DataValue.IntegerValue(0),
                new DataValue.TextValue("error"), new DataValue.ListValue(List.of(error)),
                new DataValue.RecordValue(Map.of("code", new DataValue.TextValue("unsupported"), "message", new DataValue.TextValue("not available"))),
                new DataValue.MapValue(Map.of("failure", error)))) {
            assertEquals(DataType.Scalar.BOOLEAN, operation.outputType(Map.of("value", DataType.typeOf(value)), "test"));
            var machine = run(operation, value, DataType.Scalar.ANY);
            assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
            assertEquals(new DataValue.BooleanValue(value instanceof DataValue.ErrorValue), machine.report().output().orElseThrow().field("result"));
        }
    }

    @Test void extractorsReturnTheExactStoredCodeAndMessageAndRejectNonErrors() {
        for (String id : List.of("ERROR_CODE", "ERROR_MESSAGE")) {
            var operation = ScalarOperation.valueOf(id);
            assertEquals(Map.of("value", DataType.Scalar.ERROR), operation.inputs());
            assertEquals(DataType.Scalar.TEXT, operation.outputType(Map.of("value", DataType.Scalar.ANY), "test"));
            for (var error : List.of(new DataValue.ErrorValue("unsupported", ""),
                    new DataValue.ErrorValue("", "中文\n\uD800"), new DataValue.ErrorValue("e\u0301", "é"))) {
                for (var type : List.of(DataType.Scalar.ERROR, DataType.Scalar.ANY)) {
                    var machine = run(operation, error, type);
                    assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
                    assertEquals(new DataValue.TextValue(id.equals("ERROR_CODE") ? error.code() : error.message()), machine.report().output().orElseThrow().field("result"));
                }
            }
            for (DataValue invalid : List.of(DataValue.NullValue.INSTANCE, new DataValue.TextValue("unsupported"),
                    new DataValue.RecordValue(Map.of("code", new DataValue.TextValue("unsupported"))),
                    new DataValue.ListValue(List.of(new DataValue.ErrorValue("nested", ""))))) {
                var failure = assertThrows(ComputationException.class, () -> operation.outputType(Map.of("value", DataType.typeOf(invalid)), "test"));
                assertEquals(ComputationException.Code.TYPE_MISMATCH, failure.code());
                var machine = run(operation, invalid, DataType.Scalar.ANY);
                assertEquals(ComputationException.Code.TYPE_MISMATCH, machine.report().diagnostic().orElseThrow().code());
                assertTrue(machine.report().output().isEmpty());
            }
        }
    }

    @Test void lazyBranchHandlesAnActualReadErrorWithoutEvaluatingErrorExtractionOnOrdinaryValues() {
        var read = ReadComponentRegistry.builder().build().session(new Object(), new ReadLimits(100, 1000, 10000));
        var error = read.read("unknown");
        assertInstanceOf(DataValue.ErrorValue.class, error);
        var yes = new ModuleDefinition.Key("failure", 1); var no = new ModuleDefinition.Key("normal", 1);
        var failure = graph(ScalarOperation.ERROR_CODE, DataType.Scalar.ANY);
        var normal = new ComputationGraph(failure.inputs(), Map.of("input", new ComputationGraph.Input("value")), Map.of("result", "input"));
        var predicate = graph(ScalarOperation.IS_ERROR, DataType.Scalar.ANY);
        var nodes = new LinkedHashMap<>(predicate.nodes());
        nodes.put("branch", new ComputationGraph.Branch("result", yes, no, Map.of("value", "input")));
        var program = new ProgramArchive(new ProgramDefinition(new ComputationGraph(predicate.inputs(), nodes, Map.of("handled", "branch")), Map.of(), List.of()),
            Map.of(yes, new ModuleDefinition(yes, failure), no, new ModuleDefinition(no, normal)));
        for (DataValue value : List.of(error, DataValue.NullValue.INSTANCE, new DataValue.IntegerValue(7))) {
            var machine = new ComputationMachine(program.compile(LIMITS)); machine.begin(input(value)); finish(machine);
            assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
            var result = (DataValue.RecordValue)machine.report().output().orElseThrow().field("handled");
            assertEquals(value == error ? new DataValue.TextValue("unknown_component") : value, result.field("result"));
        }
        assertEquals(new DataValue.ErrorValue("unknown_component", ""), read.read("unknown"));
    }

    @Test void upstreamExecutionFaultsAreNotCaughtAndInvalidExtractionDoesNotCommitRam() {
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        nodes.put("one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)));
        nodes.put("zero", new ComputationGraph.Constant(new DataValue.IntegerValue(0)));
        nodes.put("division", new ComputationGraph.Scalar(ScalarOperation.DIVIDE_INTEGER, Map.of("left", "one", "right", "zero")));
        nodes.put("result", new ComputationGraph.Scalar(ScalarOperation.IS_ERROR, Map.of("value", "division")));
        var source = new ComputationGraph(Map.of(), nodes, Map.of("result", "result"));
        for (var graph : List.of(source, graph(ScalarOperation.ERROR_MESSAGE, DataType.Scalar.ANY))) {
            var machine = new ComputationMachine(ProgramCompiler.compile(writing(graph), Map.of(), LIMITS));
            machine.begin(graph == source ? new DataValue.RecordValue(Map.of()) : input(DataValue.NullValue.INSTANCE)); finish(machine);
            assertEquals(graph == source ? ComputationException.Code.DIVISION_BY_ZERO : ComputationException.Code.TYPE_MISMATCH,
                machine.report().diagnostic().orElseThrow().code());
            assertTrue(machine.report().output().isEmpty()); assertEquals(new DataValue.TextValue("unchanged"), machine.memory().field("saved"));
        }
    }

    @Test void extractedTextConsumesMemoryAndFailureKeepsRamUnchanged() {
        var program = writing(graph(ScalarOperation.ERROR_MESSAGE, DataType.Scalar.ANY));
        var input = input(new DataValue.ErrorValue("large", "x".repeat(500)));
        var roomy = new ComputationMachine(ProgramCompiler.compile(program, Map.of(), LIMITS));
        roomy.begin(input); roomy.step(); long beforeResult = roomy.checkpoint().arenaUnits();
        var bounded = new ComputationMachine(ProgramCompiler.compile(program, Map.of(), new ResourceLimits(100, beforeResult, 1)));
        bounded.begin(input); bounded.step(); finish(bounded);
        assertEquals(ComputationException.Code.MEMORY_LIMIT, bounded.report().diagnostic().orElseThrow().code());
        assertEquals(new DataValue.TextValue("unchanged"), bounded.memory().field("saved")); assertTrue(bounded.report().output().isEmpty());
        finish(roomy); assertEquals(new DataValue.TextValue("x".repeat(500)), roomy.memory().field("saved"));
    }

    @Test void eachOperationRestoresComputerAndFlowAtEveryWorkBoundary() {
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var error = new DataValue.ErrorValue("unloaded", "原始说明");
        for (var op : List.of(ScalarOperation.IS_ERROR, ScalarOperation.ERROR_CODE, ScalarOperation.ERROR_MESSAGE)) {
            DataValue expected = op == ScalarOperation.IS_ERROR ? new DataValue.BooleanValue(true)
                : new DataValue.TextValue(op == ScalarOperation.ERROR_CODE ? error.code() : error.message());
            var graph = graph(op, DataType.Scalar.ANY);
            var runtime = new ComputerRuntime(UUID.randomUUID(), new ProgramArchive(writing(graph), Map.of()), LIMITS);
            runtime.begin(input(error));
            for (int guard = 0; runtime.report().status() == ComputationMachine.Status.RUNNING; guard++) {
                assertTrue(guard < 30); runtime.setPaused(true); var before = runtime.snapshot();
                var decoded = ComputerNbtCodec.decode(ComputerNbtCodec.encode(before, ARCHIVE), ARCHIVE); assertEquals(before, decoded);
                runtime = ComputerRuntime.restore(decoded, LIMITS); runtime.tick(); assertEquals(before, runtime.snapshot());
                runtime.load(); runtime.tick(); assertEquals(before, runtime.snapshot()); runtime.step();
                assertEquals(before.machine().workUnits() + 1, runtime.report().workUnits());
            }
            assertEquals(expected, runtime.memory().field("saved"));
            var expressions = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
                Map.of("inspect", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
            var program = new FlowProgram(graph.inputs(), List.of(new FlowProgram.Evaluate("check", "inspect")), expressions);
            var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, input(error), LIMITS);
            for (int guard = 0; flow.checkpoint().status() != FlowRuntime.Status.COMPLETE; guard++) {
                assertTrue(guard < 30); flow.advance(1);
                var before = new FlowNbtCodec.Snapshot(program, flow.checkpoint());
                var decoded = FlowNbtCodec.decode(FlowNbtCodec.encode(before, ARCHIVE), ARCHIVE); assertEquals(before, decoded);
                flow = FlowRuntime.restore(decoded.program(), decoded.state(), LIMITS);
                assertEquals(0, flow.advance(1)); assertEquals(before.state(), flow.checkpoint()); flow.load();
            }
            assertEquals(expected, flow.checkpoint().locals().field("result"));
            assertEquals(error, flow.checkpoint().locals().field("value"));
        }
    }

    private static ProgramDefinition writing(ComputationGraph graph) {
        return new ProgramDefinition(graph, Map.of("saved", new ProgramDefinition.Cell(DataType.Scalar.ANY, new DataValue.TextValue("unchanged"))),
            List.of(new ProgramDefinition.Write("saved", "result")));
    }
    private static DataValue.RecordValue input(DataValue value) { return new DataValue.RecordValue(Map.of("value", value)); }
    private static ComputationGraph graph(ScalarOperation operation, DataType input) {
        return new ComputationGraph(Map.of("value", input), Map.of("input", new ComputationGraph.Input("value"),
            "result", new ComputationGraph.Scalar(operation, Map.of("value", "input"))), Map.of("result", "result"));
    }
    private static ComputationMachine run(ScalarOperation operation, DataValue value, DataType input) {
        var installed = ProgramCompiler.compile(new ProgramDefinition(graph(operation, input), Map.of(), List.of()), Map.of(), LIMITS);
        var machine = new ComputationMachine(installed); machine.begin(new DataValue.RecordValue(Map.of("value", value))); finish(machine); return machine;
    }
    private static void finish(ComputationMachine machine) {
        for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) {
            assertTrue(guard < 100); machine.step();
        }
    }
}
