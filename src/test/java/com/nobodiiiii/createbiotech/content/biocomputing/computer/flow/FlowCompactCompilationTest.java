package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

class FlowCompactCompilationTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void privateArgumentsSingleResultAndParallelSwapSurviveEveryInstructionReload(boolean compact) {
        var integer = DataType.Scalar.INTEGER;
        var key = new ModuleDefinition.Key("copy", 1);
        var base = new FlowProgram(Map.of("a", integer, "b", integer), List.of(
            new FlowProgram.TaskStart("start", key, Map.of("a", "a"), "task"),
            new FlowProgram.Evaluate("increment", "increment", new FlowCallBindings(Map.of("a", "a"), Map.of("v", "a"))),
            new FlowProgram.Evaluate("copy", "copy", new FlowCallBindings(Map.of("a", "a"), Map.of("v", "c"))),
            new FlowProgram.Evaluate("swap", "swap", new FlowCallBindings(Map.of("a", "a", "b", "b"), Map.of("a", "b", "b", "a"))),
            new FlowProgram.TaskJoin("join", key, "task", "outcome"),
            new FlowProgram.Procedure("definition", key, Map.of("a", integer), Map.of("v", integer), Map.of("v", "a")),
            new FlowProgram.Sleep("sleep", "a"), new FlowProgram.EndProcedure("end"))).withAssembly();
        var graphs = Map.of(
            "increment", new ComputationGraph(Map.of("a", integer), Map.of(
                "a", new ComputationGraph.Input("a"), "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
                "sum", new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER, Map.of("left", "a", "right", "one"))), Map.of("v", "sum")),
            "copy", new ComputationGraph(Map.of("a", integer), Map.of("a", new ComputationGraph.Input("a")), Map.of("v", "a")),
            "swap", new ComputationGraph(Map.of("a", integer, "b", integer), Map.of(
                "a", new ComputationGraph.Input("a"), "b", new ComputationGraph.Input("b")), Map.of("a", "a", "b", "b")));
        var body = FlowDocumentBody.fromProgram(base, record("a", 7, "b", 3));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(body.inputs(), body.steps(), body.initial(), body.canvas(), graphs,
            body.execution(), compact ? FlowProgram.Compilation.COMPACT_V1 : FlowProgram.Compilation.ORIGINAL)));
        var document = workspace.snapshot(); var limits = FlowAssemblyTasksTest.LIMITS;
        assertEquals(document, ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, limits.archive()), limits.archive()));
        var host = new ComputerBlockHost(() -> {});
        host.installProgram(PreparedProgram.prepare(document, limits), ComputerRuntime.MemoryPolicy.RESET, limits, true);
        boolean slept = false;
        for (int i = 0; i < 200; i++) {
            var state = host.flowSnapshot().orElseThrow();
            assertNotEquals(FlowRuntime.Status.FAILED, state.state().status(), state.state().diagnostic().toString());
            slept |= state.tasks().contexts().stream().anyMatch(task -> task.status() == FlowRuntime.Status.SLEEPING);
            if (state.state().status() == FlowRuntime.Status.COMPLETE) {
                assertTrue(slept);
                assertEquals(new DataValue.IntegerValue(3), state.state().locals().field("a"));
                assertEquals(new DataValue.IntegerValue(8), state.state().locals().field("b"));
                assertEquals(new DataValue.IntegerValue(8), state.state().locals().field("c"));
                var outcome = (DataValue.RecordValue) state.state().locals().field("outcome");
                assertEquals(record("v", 7), outcome.field("value"), "Task arguments remain private captured values");
                assertTrue(state.tasks().contexts().isEmpty());
                return;
            }
            host.step(limits, true);
            var saved = host.flowSnapshot().orElseThrow();
            var restored = new ComputerBlockHost(() -> {}); restored.read(host.write()); restored.tick(limits, true);
            assertTrue(restored.loaded(), restored.diagnostic().toString());
            assertEquals(saved, restored.flowSnapshot().orElseThrow());
            host = restored;
        }
        fail("Program did not finish");
    }
}
