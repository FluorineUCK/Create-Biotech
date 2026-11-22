package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextNextNestingTest {
    static final FlowBlockRules NO_EVALUATION = new FlowBlockRules(Set.of("evaluate"), Set.of());
    static FlowProgram.Procedure helper() {
        return FlowProcedureTemplates.all().stream().filter(p -> p.key().id().equals("text_next")).findFirst().orElseThrow();
    }
    static FlowCallBindings bindings(String result) {
        return new FlowCallBindings(Map.of("text", "text", "offset", "offset"), Map.of("result", result));
    }
    @Test void publicTextNextBodyCannotBypassItsDirectEvaluationBan() {
        var helper = helper();
        var restricted = new FlowProgram.Procedure(helper.sourceId(), helper.key(), helper.inputs(), helper.outputs(),
            helper.returns(), NO_EVALUATION, helper.assembly());
        var program = new FlowProgram(helper.inputs(), List.of(
            new FlowProgram.Invoke("call_text_next", helper.key(), bindings("out")), restricted,
            new FlowProgram.EndProcedure("helper_end"))).withAssembly();
        var error = assertThrows(ComputationException.class,
            () -> program.validate(NativeLibraryOwnersTest.limits("cleric", 4).hardware()));
        assertEquals(ComputationException.Code.INVALID_GRAPH, error.code());
        assertEquals(helper.sourceId(), error.location());
        assertTrue(error.getMessage().contains("Assembly body forbids evaluate"), error.getMessage());
    }
    @Test void ordinaryIndirectCallCannotHidePublicTextNextFromOuterEvaluationBan() {
        var helper = helper();
        var outer = new ModuleDefinition.Key("restricted_text_outer", 1);
        var program = new FlowProgram(helper.inputs(), List.of(
            new FlowProgram.Invoke("call_outer", outer, bindings("out")),
            new FlowProgram.Procedure("restricted_outer", outer, helper.inputs(), helper.outputs(),
                Map.of("result", "result"), NO_EVALUATION),
            new FlowProgram.Invoke("indirect_text_next", helper.key(), bindings("result")),
            new FlowProgram.EndProcedure("outer_end"), helper,
            new FlowProgram.EndProcedure("helper_end"))).withAssembly();
        var error = assertThrows(ComputationException.class,
            () -> program.validate(NativeLibraryOwnersTest.limits("cleric", 4).hardware()));
        assertEquals(ComputationException.Code.INVALID_GRAPH, error.code());
        assertEquals("indirect_text_next", error.location());
        assertTrue(error.getMessage().contains("restricted_outer"), error.getMessage());
        assertTrue(error.getMessage().contains("forbids evaluate"), error.getMessage());
        assertTrue(error.getMessage().contains(helper.sourceId()), error.getMessage());
    }
}
