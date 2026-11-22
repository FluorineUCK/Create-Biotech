package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowBlockRulesTest {
    static final ModuleDefinition.Key KEY = new ModuleDefinition.Key("helper", 1);
    static FlowProgram program(Set<String> inside, Set<String> contexts, boolean loop, boolean wait) {
        var steps = new ArrayList<FlowProgram.Step>();
        if (loop) steps.add(new FlowProgram.Repeat("repeat", "value"));
        steps.add(new FlowProgram.Invoke("call", KEY, new FlowCallBindings(Map.of("value", "value"), Map.of())));
        if (loop) steps.add(new FlowProgram.EndRepeat("repeat_end"));
        steps.add(new FlowProgram.Procedure("definition", KEY, Map.of("value", DataType.Scalar.INTEGER), Map.of(), Map.of()));
        if (wait) steps.add(new FlowProgram.Sleep("wait", "value"));
        steps.add(new FlowProgram.EndProcedure("return"));
        var encoded = (DataValue.RecordValue)FlowProgramCodec.encode(FlowAssemblyMemoryTest.withSteps(steps));
        var root = new LinkedHashMap<>(encoded.fields());
        var source = new ArrayList<>(((DataValue.ListValue)root.get("steps")).values());
        for (int i = 0; i < source.size(); i++) {
            var entry = (DataValue.RecordValue)source.get(i);
            if (entry.field("kind").equals(new DataValue.TextValue("procedure"))) {
                var fields = new LinkedHashMap<>(entry.fields());
                fields.put("rules", new DataValue.RecordValue(Map.of("version", new DataValue.IntegerValue(1),
                    "forbid_inside", names(inside), "forbid_contexts", names(contexts))));
                source.set(i, new DataValue.RecordValue(fields));
            }
        }
        root.put("steps", new DataValue.ListValue(source));
        return FlowProgramCodec.decode(new DataValue.RecordValue(root));
    }
    static DataValue.ListValue names(Set<String> ids) { return new DataValue.ListValue(ids.stream().sorted().map(s -> (DataValue)new DataValue.TextValue(s)).toList()); }
    @Test void insideRulesAccountForDirectAndIndirectReadsInInlineArguments() {
        for (boolean viaReporter : List.of(false, true)) {
            var blocked = inlineCall(new FlowBlockRules(Set.of("public_memory"), Set.of()), viaReporter, true);
            var limits = FlowAssemblyTasksTest.LIMITS.hardware();
            var failure = assertThrows(ComputationException.class, () -> blocked.validate(limits));
            assertEquals(ComputationException.Code.INVALID_GRAPH, failure.code());
            assertTrue(failure.getMessage().contains("forbids public_memory inside"), failure::getMessage);
            assertDoesNotThrow(() -> inlineCall(FlowBlockRules.NONE, viaReporter, true).validate(limits));
            assertDoesNotThrow(() -> inlineCall(new FlowBlockRules(Set.of("public_memory"), Set.of()), viaReporter, false).validate(limits));
        }
    }
    private static FlowProgram inlineCall(FlowBlockRules rules, boolean viaReporter, boolean readsMemory) {
        var outer = new ModuleDefinition.Key("outer", 1);
        var reader = new ModuleDefinition.Key("report", 1);
        var value = Map.<String, DataType>of("value", DataType.Scalar.INTEGER);
        var noArgs = new FlowCallBindings(Map.of(), Map.of());
        ComputationGraph.Node source = readsMemory ? new ComputationGraph.ReadMemory("counter")
            : new ComputationGraph.Constant(new DataValue.IntegerValue(4));
        var nested = new ComputationGraph.ProcedureValue(reader, "value", Map.of());
        var arguments = new ComputationGraph(Map.of(), Map.of("arg", viaReporter ? nested : source), Map.of("value", "arg"));
        var entries = new LinkedHashMap<String, ProgramDefinition.Entry>();
        entries.put("operands", new ProgramDefinition.Entry(arguments, List.of(), List.of()));
        if (viaReporter) entries.put("reporter_body", new ProgramDefinition.Entry(
            new ComputationGraph(Map.of(), Map.of("result", source), Map.of("value", "result")), List.of(), List.of()));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(4))),
            List.of(), List.of(), entries), Map.of());
        var steps = new ArrayList<FlowProgram.Step>(List.of(
            new FlowProgram.Invoke("start", outer, noArgs),
            new FlowProgram.Procedure("outer_def", outer, Map.of(), Map.of(), Map.of(), rules),
            new FlowProgram.Invoke("use_value", KEY, noArgs, Optional.of(new FlowProgram.InvokeOperands("operands", Map.of()))),
            new FlowProgram.EndProcedure("outer_end"),
            new FlowProgram.Procedure("use_value_def", KEY, value, Map.of(), Map.of()),
            new FlowProgram.EndProcedure("use_value_end")));
        if (viaReporter) {
            steps.add(new FlowProgram.Procedure("report_def", reader, Map.of(), value, Map.of("value", "result")));
            steps.add(new FlowProgram.Evaluate("get_value", "reporter_body", new FlowCallBindings(Map.of(), Map.of("value", "result"))));
            steps.add(new FlowProgram.EndProcedure("report_end"));
        }
        return new FlowProgram(Map.of(), steps, archive).withAssembly();
    }
    @Test void customRulesSurvivePublicCodecAndAllowUnrelatedOperations() {
        var program = program(Set.of("device_wait"), Set.of("atomic"), true, false);
        assertEquals(program, FlowProgramCodec.decode(FlowProgramCodec.encode(program)));
        program.validate(FlowAssemblyTasksTest.LIMITS.hardware());
    }
    @Test void customDeclarationCanForbidOperationsInsideItsBody() {
        var program = program(Set.of("device_wait"), Set.of(), false, true);
        assertEquals(ComputationException.Code.INVALID_GRAPH, assertThrows(ComputationException.class,
            () -> program.validate(FlowAssemblyTasksTest.LIMITS.hardware())).code());
    }
    @Test void customDeclarationCanForbidEnteringALoopContext() {
        var program = program(Set.of(), Set.of("loop"), true, false);
        assertEquals(ComputationException.Code.INVALID_GRAPH, assertThrows(ComputationException.class,
            () -> program.validate(FlowAssemblyTasksTest.LIMITS.hardware())).code());
    }
    @Test void callsCannotHideEitherDirectionOfADeclarationRule() {
        var relay = new ModuleDefinition.Key("relay", 1);
        for (boolean contexts : List.of(false, true)) {
            var source = program(Set.of(), contexts ? Set.of("loop") : Set.of(), contexts, !contexts);
            var steps = new ArrayList<>(source.steps());
            for (int i=0;i<steps.size();i++) if (steps.get(i) instanceof FlowProgram.Invoke call)
                steps.set(i, new FlowProgram.Invoke(call.sourceId(), relay, call.bindings()));
            steps.add(new FlowProgram.Procedure("relay_def", relay, Map.of("value", DataType.Scalar.INTEGER), Map.of(), Map.of(),
                contexts ? FlowBlockRules.NONE : new FlowBlockRules(Set.of("device_wait"), Set.of())));
            steps.add(new FlowProgram.Invoke("nested_call", KEY, new FlowCallBindings(Map.of("value", "value"), Map.of())));
            steps.add(new FlowProgram.EndProcedure("relay_end"));
            assertThrows(ComputationException.class, () -> FlowAssemblyMemoryTest.withSteps(steps).validate(FlowAssemblyTasksTest.LIMITS.hardware()));
        }
    }
    @Test void unknownAndNonContextSelectorsCannotSilentlyDisableChecks() {
        for (String selector : List.of("missing", "sleep", "end_repeat")) {
            var source = program(Set.of(), Set.of(selector), false, false);
            assertThrows(ComputationException.class, () -> source.validate(FlowAssemblyTasksTest.LIMITS.hardware()));
        }
    }
    @Test void ordinaryNestedControlsRemainAllowedAndDelimitersMatchDeclarations() {
        for (var outer : List.<FlowProgram.Step>of(new FlowProgram.If("if", new FlowInput.Literal(new DataValue.BooleanValue(true))),
                new FlowProgram.Repeat("repeat", "value"), new FlowProgram.Until("until", new FlowInput.Literal(new DataValue.BooleanValue(true))),
                new FlowProgram.Forever("forever"))) {
            var end = switch (FlowBlockDeclarations.of(outer)) {
                case IF -> new FlowProgram.EndIf("end"); case REPEAT -> new FlowProgram.EndRepeat("end");
                case UNTIL -> new FlowProgram.EndUntil("end"); default -> new FlowProgram.EndForever("end");
            };
            var steps = List.<FlowProgram.Step>of(outer, new FlowProgram.Atomic("update", "result"), new FlowProgram.EndAtomic("commit"), end);
            FlowAssemblyMemoryTest.withSteps(steps).validate(FlowAssemblyTasksTest.LIMITS.hardware());
            var invalid = new ArrayList<>(steps); invalid.set(2, new FlowProgram.EndIf("commit"));
            assertThrows(ComputationException.class, () -> FlowBranches.structure(invalid));
        }
    }
}
