package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowInlineScreenTest.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScratchParkedConstructorScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { environment.client(); }
    @org.junit.jupiter.api.AfterEach void restoreClient() throws Exception { environment.restore(); }

    @Test void aParkedListCanBeEditedBeforeItIsConnectedAndAppliesAsOneUndoableEdit() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        var canvas = (FlowBlockCanvas) get(editor, "canvas");
        double x = (int)get(canvas, "x") + 65, y = (int)get(canvas, "y") + 65;
        dropReporter(editor, "inline_list_make", new FlowBlockCanvas.Slot("unused", "unused", "0", x, y, 40, 16, false));
        var before = workspace.snapshot();
        var body = before.flow().orElseThrow();
        var parked = body.canvas().values().parked().getFirst();
        var hit = canvas.parkedSlotHit(x + 10, y + 12);
        assertNotNull(hit, "The new list is an independent visible block");
        assertTrue(parked.isRoot(hit.slot().node()));
        clickReporter(editor, hit.slot());
        click(editor, "construction_add");
        var member = constructorMember(editor);
        editor.mouseClicked(member.getX() + 3, member.getY() + 3, 0);
        var field = assertInstanceOf(net.minecraft.client.gui.components.EditBox.class, editor.getFocused());
        field.setValue("41");
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
        assertEquals(List.of(new DataValue.IntegerValue(0), new DataValue.IntegerValue(41)), constructorConstants(editor));
        assertEquals(before, workspace.snapshot(), "A parked reporter is not committed until Apply");
        click(editor, "construction_apply");
        var changed = workspace.snapshot();
        var result = changed.flow().orElseThrow().canvas().values().parked().getFirst();
        assertEquals(parked.position(), result.position());
        var list = assertInstanceOf(ComputationGraph.MakeList.class, result.graph().nodes().get(hit.slot().node()));
        assertEquals(List.of(new ComputationGraph.Constant(new DataValue.IntegerValue(0)),
            new ComputationGraph.Constant(new DataValue.IntegerValue(41))),
            list.elements().stream().map(result.graph().nodes()::get).toList());
        assertEquals(body.program(before.archive()), changed.flow().orElseThrow().program(changed.archive()),
            "A parked constructor still executes no code");
        assertEquals(changed, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(changed)));
        click(editor, "undo");
        assertEquals(before, workspace.snapshot());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void editingAParkedRecordKeepsItsOriginalScopeAndMemberFallback(boolean ownerAvailable) throws Exception {
        var key = new ModuleDefinition.Key("library_sample", 1);
        var scope = new ScratchValueLayout.ProcedureScope("procedure", key);
        var owner = new FlowProgram.Procedure(scope.source(), ownerAvailable ? key : new ModuleDefinition.Key(key.id(), 2),
            Map.of("from_owner", DataType.Scalar.INTEGER), Map.of(), Map.of());
        var definition = new FlowBlockLayout.Stack(new ProgramDocument.Position(230, 20),
            List.of(owner, new FlowProgram.EndProcedure("procedure_end")));
        var position = new ProgramDocument.Position(58, 66);
        var fallback = new DataValue.IntegerValue(12);
        var graph = new ComputationGraph(Map.of("input", DataType.Scalar.INTEGER), Map.of(
            "leaf", new ComputationGraph.Input("input"), "record", new ComputationGraph.MakeRecord(Map.of("reading", "leaf"))),
            Map.of("value", "record"));
        var reporter = new ScratchValueLayout.Reporter(position, graph, Map.of("input", "from_owner"), Map.of("leaf", fallback)).scoped(scope);
        var layout = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(), List.of(definition),
            ScratchValueLayout.EMPTY.withParked(List.of(reporter)));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("main_only", DataType.Scalar.INTEGER), List.of(),
            new DataValue.RecordValue(Map.of("main_only", new DataValue.IntegerValue(0))), layout, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        var hit = canvas.parkedSlotHit((int)get(canvas, "x") + position.x() + 4,
            (int)get(canvas, "y") + position.y() + 5);
        assertNotNull(hit);
        assertTrue(reporter.isRoot(hit.slot().node()));
        clickReporter(editor, hit.slot());
        FlowPaletteScreenTest.search(editor).setValue("main_only");
        assertFalse(hasConstructorVariable(editor, "main_only"), "Do not capture variables in the currently selected script");
        FlowPaletteScreenTest.search(editor).setValue("from_owner");
        assertEquals(ownerAvailable, hasConstructorVariable(editor, "from_owner"), "Do not rebind a stale fixed scope to a new revision");
        var before = workspace.snapshot();
        click(editor, "construction_add");
        assertEquals(before, workspace.snapshot());
        click(editor, "construction_apply");
        var changed = workspace.snapshot();
        var result = changed.flow().orElseThrow().canvas().values().parked().getFirst();
        assertEquals(reporter.arguments(), result.arguments());
        assertEquals(reporter.procedureScope(), result.procedureScope());
        assertEquals(reporter.shadows(), result.shadows());
        assertEquals(position, result.position());
        var record = assertInstanceOf(ComputationGraph.MakeRecord.class, result.graph().nodes().get("record"));
        assertEquals(Set.of("reading", "field"), record.fields().keySet());
        click(editor, "undo");
        assertEquals(before, workspace.snapshot());
    }

    private static boolean hasConstructorVariable(ComputerFlowScreen editor, String name) throws Exception {
        return ((Map<?, ?>)get(editor, "reporters")).values().stream().anyMatch(value ->
            value instanceof InlineGraphEdits.Variable variable && variable.name().equals(name));
    }

    private static void clickReporter(ComputerFlowScreen editor, FlowBlockCanvas.Slot slot) {
        editor.mouseClicked(slot.x() + 3, slot.y() + 3, 0);
        editor.mouseReleased(slot.x() + 3, slot.y() + 3, 0);
    }
}
