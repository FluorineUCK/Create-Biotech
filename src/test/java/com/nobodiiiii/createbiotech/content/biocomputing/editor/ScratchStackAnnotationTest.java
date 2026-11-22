package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockRules;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowInput;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

class ScratchStackAnnotationTest {
    @Test void onlyDefinitionsAndParkedStatementsNeedAnnotationsAboveTheirStacks() {
        var startup = stack(FlowProgram.Procedure.startupHat("entry", key("entry")));
        var broadcast = stack(new FlowProgram.Procedure("inbox", key("inbox"), Map.of(), Map.of(), Map.of(),
            FlowBlockRules.NONE, Optional.empty(), Optional.of("batch")));
        var key = stack(FlowProgram.Procedure.keyHat("keyboard", key("keyboard"), "space"));
        var button = stack(procedure("button"));
        var watch = stack(procedure("check"), new FlowProgram.Watch("observe", Optional.empty(), new FlowInput.Literal(new DataValue.BooleanValue(false))));
        var definition = stack(procedure("calculate"));
        var parked = new FlowBlockLayout.Stack(new ProgramDocument.Position(80, 120), List.of(new FlowProgram.Sleep("sleep", new FlowInput.Literal(new DataValue.IntegerValue(1)))));
        var stacks = List.of(startup, broadcast, key, button, watch, definition, parked);
        var layout = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(), stacks, ScratchValueLayout.EMPTY, Map.of(), Map.of("button", "Run"), Map.of());
        var body = new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()), layout, Map.of(), FlowProgram.Execution.REGISTER_V1);
        var canvas = new FlowBlockCanvas(new FlowDraft(body), () -> {});
        var parkedLabel = Component.literal("Parked: not run");
        assertAll(
            () -> assertNull(canvas.stackAnnotation(startup, parkedLabel), "start event"),
            () -> assertNull(canvas.stackAnnotation(broadcast, parkedLabel), "broadcast event"),
            () -> assertNull(canvas.stackAnnotation(key, parkedLabel), "terminal key event"),
            () -> assertNull(canvas.stackAnnotation(button, parkedLabel), "terminal button event"),
            () -> assertNull(canvas.stackAnnotation(watch, parkedLabel), "edge event"),
            () -> assertEquals("create_biotech.biocomputing.flow_editor.procedure_definition_hint",
                ((TranslatableContents)canvas.stackAnnotation(definition, parkedLabel).getContents()).getKey()),
            () -> assertSame(parkedLabel, canvas.stackAnnotation(parked, parkedLabel)));
    }
    private static FlowProgram.Procedure procedure(String name) {
        return new FlowProgram.Procedure(name, key(name), Map.of(), Map.of(), Map.of());
    }
    private static ModuleDefinition.Key key(String name) {
        return new ModuleDefinition.Key(name, 1);
    }
    private static FlowBlockLayout.Stack stack(FlowProgram.Procedure procedure, FlowProgram.Step... body) {
        var steps = new ArrayList<FlowProgram.Step>();
        steps.add(procedure); steps.addAll(Arrays.asList(body));
        steps.add(new FlowProgram.EndProcedure(procedure.sourceId() + "_end"));
        return new FlowBlockLayout.Stack(new ProgramDocument.Position(80, 40), steps);
    }
}
