package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NumericRuntimeTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(100, 100_000, 1);
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(100_000, 1_000_000, 10_000_000);

    @Test void flowEvaluateRestoresEveryBudgetStepAndPreservesInputsForEveryNewOperation() {
        for (var operation : NumericProgramFixtures.OPERATIONS) {
            var graph = NumericProgramFixtures.graph(operation);
            var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
            var expressions = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
                Map.of("calculate", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
            var program = new FlowProgram(graph.inputs(), List.of(new FlowProgram.Evaluate("numeric", "calculate")), expressions);
            var inputs = NumericProgramFixtures.inputs(operation);
            var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, inputs, LIMITS);
            for (int i = 0; flow.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) {
                assertTrue(i < 30, operation.name()); flow.advance(1);
                var snapshot = new FlowNbtCodec.Snapshot(flow.program(), flow.checkpoint());
                var saved = FlowNbtCodec.decode(FlowNbtCodec.encode(snapshot, ARCHIVE), ARCHIVE);
                assertEquals(snapshot, saved);
                flow = FlowRuntime.restore(saved.program(), saved.state(), LIMITS);
                assertEquals(0, flow.advance(1)); flow.load();
            }
            assertEquals(NumericProgramFixtures.expected(operation), flow.checkpoint().locals().field("result"));
            for (var input : inputs.fields().entrySet()) assertEquals(input.getValue(), flow.checkpoint().locals().field(input.getKey()));
        }
    }

    @Test void failedNumericEventsCannotPartiallyCommitOrLoseTheirDiagnosticAfterRestoring() {
        for (var operation : NumericProgramFixtures.OPERATIONS) {
            var archive = NumericProgramFixtures.archive(operation);
            var installed = ProgramCompiler.compile(archive.program(), archive.modules(), LIMITS);
            var machine = new ComputationMachine(installed); machine.begin(NumericProgramFixtures.inputs(operation)); finish(machine);
            var previous = machine.memory();
            var values = new LinkedHashMap<>(NumericProgramFixtures.inputs(operation).fields());
            var code = switch (operation) {
                case SQRT_DECIMAL -> { values.put("value", new DataValue.DecimalValue(-1)); yield ComputationException.Code.INVALID_ARGUMENT; }
                case POWER_DECIMAL -> { values.put("left", new DataValue.DecimalValue(-2)); yield ComputationException.Code.INVALID_ARGUMENT; }
                case REMAINDER_DECIMAL -> { values.put("right", new DataValue.DecimalValue(-0.0)); yield ComputationException.Code.DIVISION_BY_ZERO; }
                default -> { values.put("value", new DataValue.DecimalValue(0x1.0p63)); yield ComputationException.Code.ARITHMETIC_OVERFLOW; }
            };
            machine.begin(new DataValue.RecordValue(values));
            for (int i = 0; machine.report().status() == ComputationMachine.Status.RUNNING; i++) {
                assertTrue(i < 20); machine.step();
                machine = ComputationMachine.restore(installed, machine.checkpoint());
                assertEquals(previous, machine.memory());
            }
            assertEquals(ComputationMachine.Status.FAILED, machine.report().status());
            assertEquals(code, machine.report().diagnostic().orElseThrow().code());
            assertTrue(machine.report().diagnostic().orElseThrow().location().contains("numeric"));
            assertTrue(machine.report().output().isEmpty());
        }
    }

    private static void finish(ComputationMachine machine) {
        for (int i = 0; machine.report().status() == ComputationMachine.Status.RUNNING; i++) { assertTrue(i < 20); machine.step(); }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
    }
}
