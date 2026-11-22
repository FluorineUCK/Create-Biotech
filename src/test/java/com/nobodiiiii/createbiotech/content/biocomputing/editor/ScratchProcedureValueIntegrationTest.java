package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Cross-worktree contract: nested reporters use the existing ISA CALL/checkpoint, not a graph VM. */
class ScratchProcedureValueIntegrationTest {
    private static final DataType.Scalar INTEGER = DataType.Scalar.INTEGER;
    private static final ModuleDefinition.Key WRAPPER = new ModuleDefinition.Key("wrapped", 1);
    private static final ModuleDefinition.Key SLEEPER = new ModuleDefinition.Key("sleeper", 1);
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());

    private static ProgramDocument document(FlowProgram.Compilation policy) {
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        nodes.put("two", new ComputationGraph.Constant(new DataValue.IntegerValue(2)));
        nodes.put("three", new ComputationGraph.Constant(new DataValue.IntegerValue(3)));
        nodes.put("left", new ComputationGraph.ProcedureValue(WRAPPER, "result", Map.of("n", "two")));
        nodes.put("right", new ComputationGraph.ProcedureValue(WRAPPER, "result", Map.of("n", "three")));
        nodes.put("sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "left", "right"));
        var graph = new ComputationGraph(Map.of(), nodes, Map.of("result", "sum", "again", "left"));
        var wrapped = new ComputationGraph(Map.of("n", INTEGER), Map.of(
            "arg", new ComputationGraph.Input("n"),
            "nested", new ComputationGraph.ProcedureValue(SLEEPER, "result", Map.of("n", "arg"))), Map.of("result", "nested"));
        var main = new FlowProgram.Evaluate("calculate", "unused", new FlowCallBindings(Map.of(), Map.of("result", "answer", "again", "same")));
        var inner = new FlowProgram.Evaluate("inside", "unused", new FlowCallBindings(Map.of("n", "n"), Map.of("result", "out")));
        var steps = List.<FlowProgram.Step>of(main,
            new FlowProgram.Procedure("wrapper", WRAPPER, Map.of("n", INTEGER), Map.of("result", INTEGER), Map.of("result", "out")),
            inner, new FlowProgram.EndProcedure("wrapper_end"),
            new FlowProgram.Procedure("sleeper", SLEEPER, Map.of("n", INTEGER), Map.of("result", INTEGER), Map.of("result", "n")),
            new FlowProgram.Shared("count_calls", "calls", new FlowInput.Literal(new DataValue.IntegerValue(1)), true),
            new FlowProgram.Sleep("sleep", new FlowInput.Literal(new DataValue.IntegerValue(2))),
            new FlowProgram.EndProcedure("sleeper_end"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("calls", new ProgramDefinition.Cell(INTEGER, new DataValue.IntegerValue(0))), List.of()), Map.of());
        var body = new FlowDocumentBody(Map.of(), List.of(main), EMPTY, new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(), List.of(
            new FlowBlockLayout.Stack(new ProgramDocument.Position(300, 16), steps.subList(1, 4)),
            new FlowBlockLayout.Stack(new ProgramDocument.Position(600, 16), steps.subList(4, steps.size())))),
            Map.of("calculate", graph, "inside", wrapped), FlowProgram.Execution.REGISTER_V1, policy);
        return new ProgramDocument(archive, Map.of(), Optional.empty(), Optional.of(body));
    }

    @ParameterizedTest @EnumSource(FlowProgram.Compilation.class)
    void nestedReportersSleepReturnAndResumeWithoutReplayingEffects(FlowProgram.Compilation policy) {
        var document = document(policy);
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var program = document.flow().orElseThrow().program(document.archive());
        assertEquals(program, FlowProgramCodec.decode(FlowProgramCodec.encode(program)));
        var limits = StockApplicationDriverDocuments.hardware();
        var uninterrupted = new ComputerBlockHost(() -> {});
        uninterrupted.installProgram(PreparedProgram.prepare(document, limits), ComputerRuntime.MemoryPolicy.RESET, limits, true);
        var resumed = new ComputerBlockHost(() -> {}); resumed.read(uninterrupted.write()); resumed.tick(limits, true);
        boolean slept = false;
        for (int i = 0; i < 500; i++) {
            var expected = uninterrupted.flowSnapshot().orElseThrow();
            assertEquals(expected, resumed.flowSnapshot().orElseThrow());
            assertNotEquals(FlowRuntime.Status.FAILED, expected.state().status(), expected.state().diagnostic().toString());
            slept |= expected.state().status() == FlowRuntime.Status.SLEEPING;
            if (expected.state().status() == FlowRuntime.Status.COMPLETE) {
                assertTrue(slept);
                var locals = program.displayLocals(expected.state().assembly().orElseThrow());
                assertEquals(new DataValue.IntegerValue(5), locals.field("answer"));
                assertEquals(new DataValue.IntegerValue(2), locals.field("same"));
                assertEquals(new DataValue.IntegerValue(2), expected.memory().cells().field("calls"),
                    "A shared reporter node runs once, and recovery never repeats its public-memory effect");
                assertTrue(expected.state().work() > 20, "Nested calls, copies and transactions are ordinary metered ISA");
                return;
            }
            uninterrupted.step(limits, true); resumed.step(limits, true);
            var reloaded = new ComputerBlockHost(() -> {}); reloaded.read(resumed.write()); reloaded.tick(limits, true);
            assertTrue(reloaded.loaded(), reloaded.diagnostic().toString());
            resumed = reloaded;
        }
        fail("Nested reporter failed to return within the finite test budget");
    }
}
