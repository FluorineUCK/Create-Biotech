package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowProcedureValueTest {
    private static final ModuleDefinition.Key KEY = new ModuleDefinition.Key("waiter", 1);
    private static final DataType.Scalar INTEGER = DataType.Scalar.INTEGER;
    private static final ResourceLimits LIMITS = new ResourceLimits(1000, 100000, 1);
    private static final FlowProgram.Procedure DEFINITION = new FlowProgram.Procedure("def", KEY,
        Map.of("ticks", INTEGER), Map.of("result", INTEGER), Map.of("result", "ticks"), FlowBlockRules.NONE);
    private static final FlowProgram.Evaluate VALUE = new FlowProgram.Evaluate("value", "calculate",
        new FlowCallBindings(Map.of(), Map.of("result", "answer")));

    private static ProgramArchive expression() {
        var graph = new ComputationGraph(Map.of(), Map.of(
            "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "call", new ComputationGraph.ProcedureValue(KEY, "result", Map.of("ticks", "one"))),
            Map.of("result", "call"));
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of(), List.of(), List.of(), Map.of("calculate", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
    }

    @Test void newReporterOperandsSelectConstructionAndComparisonRecoveryWithoutReclassifyingOldOwners() {
        var consumer = new ModuleDefinition.Key("consume", 1);
        var reporter = new ModuleDefinition.Key("identity", 1);
        var inputs = new FlowProgram.InvokeOperands("operands", Map.of());
        var call = new FlowProgram.Invoke("call", consumer, new FlowCallBindings(Map.of(), Map.of()), Optional.of(inputs));
        var one = new ComputationGraph.Constant(new DataValue.IntegerValue(1));
        var graph = new ComputationGraph(Map.of(), Map.of("one", one,
            "record", new ComputationGraph.MakeRecord(Map.of("n", "one")),
            "equal", new ComputationGraph.ValueEquals("record", "record")), Map.of("value", "equal"));
        var old = operandProgram(graph, call, consumer, reporter);
        assertFalse(old.usesConstructionAssembly(), "Do not silently rewrite an already installed legacy owner's schema marker");
        assertFalse(old.usesValueEqualityAssembly());
        var nodes = new LinkedHashMap<>(graph.nodes());
        nodes.put("wrapped", new ComputationGraph.ProcedureValue(reporter, "result", Map.of("n", "one")));
        nodes.put("record", new ComputationGraph.MakeRecord(Map.of("n", "wrapped")));
        graph = new ComputationGraph(Map.of(), nodes, graph.outputs());
        var program = operandProgram(graph, call, consumer, reporter);
        assertTrue(program.usesConstructionAssembly(), "A new reporter argument is not exempt from metered construction recovery");
        assertTrue(program.usesValueEqualityAssembly());
        assertTrue(program.usesMatchingAssembly());
        assertTrue(program.usesBoundedAssemblyRestore());
    }
    private static FlowProgram operandProgram(ComputationGraph graph, FlowProgram.Invoke call,
            ModuleDefinition.Key consumer, ModuleDefinition.Key reporter) {
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of(), List.of(), List.of(), Map.of("operands", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        return new FlowProgram(Map.of(), List.of(call,
            new FlowProgram.Procedure("def_consume", consumer, Map.of("value", DataType.Scalar.BOOLEAN), Map.of(), Map.of()),
            new FlowProgram.EndProcedure("end_consume"),
            new FlowProgram.Procedure("def_identity", reporter, Map.of("n", INTEGER), Map.of("result", INTEGER), Map.of("result", "n")),
            new FlowProgram.EndProcedure("end_identity")), archive, FlowProgram.Execution.REGISTER_V1);
    }

    @Test void aCommandOperandCannotHideASynchronousWaitOrRecursion() {
        var receiver = new ModuleDefinition.Key("receiver", 1);
        var consume = new FlowProgram.Procedure("receiver", receiver, Map.of("result", INTEGER), Map.of(), Map.of());
        var send = new FlowProgram.Invoke("invoke", receiver, new FlowCallBindings(Map.of(), Map.of()),
            Optional.of(new FlowProgram.InvokeOperands("calculate", Map.of())));
        var end = new FlowProgram.EndProcedure("end_def");
        var endConsume = new FlowProgram.EndProcedure("end_receiver");
        var archive = expression();
        var atomic = new FlowProgram(Map.of(), List.of(new FlowProgram.Atomic("atomic", "status"), send,
            new FlowProgram.EndAtomic("end_atomic"), DEFINITION, new FlowProgram.Sleep("sleep", "ticks"), end,
            consume, endConsume), archive, FlowProgram.Execution.REGISTER_V1);
        assertTrue(assertThrows(ComputationException.class, () -> atomic.validate(LIMITS)).getMessage().contains("forbids device_wait"));
        var recursive = new FlowProgram(Map.of(), List.of(DEFINITION, send, end, consume, endConsume), archive, FlowProgram.Execution.REGISTER_V1);
        assertTrue(assertThrows(ComputationException.class, () -> recursive.validate(LIMITS)).getMessage().contains("Recursive"));
    }

    @Test void returnedBlockAlsoCarriesItsForbiddenContextIntoTheCaller() {
        var definition = new FlowProgram.Procedure(DEFINITION.sourceId(), KEY, DEFINITION.inputs(),
            DEFINITION.outputs(), DEFINITION.returns(), new FlowBlockRules(Set.of(), Set.of("loop")));
        var program = new FlowProgram(Map.of(), List.of(
            new FlowProgram.Repeat("repeat", new FlowInput.Literal(new DataValue.IntegerValue(1))), VALUE,
            new FlowProgram.EndRepeat("end_repeat"), definition, new FlowProgram.EndProcedure("end_def")),
            expression(), FlowProgram.Execution.REGISTER_V1);
        var error = assertThrows(ComputationException.class, () -> program.validate(LIMITS));
        assertTrue(error.getMessage().contains("forbids context loop"), error::getMessage);
    }

    @Test void reportersCannotBypassRecursionAtomicOrTheIsaBoundary() {
        var archive = expression();
        var end = new FlowProgram.EndProcedure("end_def");
        var recursive = new FlowProgram(Map.of(), List.of(DEFINITION, VALUE, end), archive, FlowProgram.Execution.REGISTER_V1);
        var error = assertThrows(ComputationException.class, () -> recursive.validate(LIMITS));
        assertEquals(ComputationException.Code.INVALID_GRAPH, error.code());
        assertTrue(error.getMessage().contains("Recursive"));

        var atomic = new FlowProgram(Map.of(), List.of(new FlowProgram.Atomic("atomic", "status"), VALUE,
            new FlowProgram.EndAtomic("end_atomic"), DEFINITION, new FlowProgram.Sleep("sleep", "ticks"), end),
            archive, FlowProgram.Execution.REGISTER_V1);
        error = assertThrows(ComputationException.class, () -> atomic.validate(LIMITS));
        assertEquals(ComputationException.Code.INVALID_GRAPH, error.code());
        assertTrue(error.getMessage().contains("forbids device_wait"), error.getMessage());

        var legacy = new FlowProgram(Map.of(), List.of(VALUE, DEFINITION, end), archive, FlowProgram.Execution.LEGACY_GRAPH);
        assertTrue(assertThrows(ComputationException.class, () -> legacy.validate(LIMITS)).getMessage().contains("ISA"));
        assertThrows(ComputationException.class, () -> archive.compile(LIMITS), "A graph without a Flow owner cannot run this node");
    }
}
