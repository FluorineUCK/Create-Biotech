package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.*;

class FlowMonitorScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }

    @Test void draggingAMonitorCreatesAnEditableIsaBlockWithoutInstalling() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        workspace.root().putCell("counter", DataType.Scalar.INTEGER, new DataValue.IntegerValue(0));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "category_variables"); FlowPaletteScreenTest.search(screen).setValue("monitor");
        var prototype = button(screen, "add_monitor_show"); assertTrue(prototype.active);
        var canvas = get(screen, "canvas");
        double x = (int)get(canvas, "x") + 24, y = (int)get(canvas, "y") + 50;
        double fromX = prototype.getX() + 10, fromY = prototype.getY() + 10;
        var before = workspace.snapshot();
        assertTrue(screen.mouseClicked(fromX, fromY, 0));
        screen.mouseDragged(x, y, 0, x - fromX, y - fromY);
        assertEquals(before, workspace.snapshot());
        assertTrue(screen.mouseReleased(x, y, 0));
        var created = workspace.snapshot();
        var block = assertInstanceOf(FlowProgram.Monitor.class, created.flow().orElseThrow().steps().getFirst());
        assertEquals("counter", block.cell()); assertTrue(block.visible()); assertFalse(block.privateLocal());
        assertFalse((boolean)get(screen, "parameters")); assertFalse((boolean)get(screen, "waiting"));
        assertEquals(created, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(created)));
        click(screen, "undo"); assertEquals(before, workspace.snapshot());
        click(screen, "redo"); assertEquals(created, workspace.snapshot());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void inlineTargetSelectionDistinguishesSameNamedScopesWithoutAForm(int width) throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        workspace.root().putCell("counter", DataType.Scalar.INTEGER, new DataValue.IntegerValue(0));
        var draft = new FlowDraft(workspace.flow().orElseThrow());
        draft.initial(new DataValue.RecordValue(Map.of("counter", new DataValue.IntegerValue(5))));
        draft.append(new FlowProgram.Monitor("watch", "counter", true));
        workspace.setFlow(Optional.of(draft.snapshot()));
        var screen = FlowCallScreenTest.editor(workspace, width);
        set(screen, "selected", "watch"); screen.init(); click(screen, "edit");
        assertNotNull(button(screen, "monitor_target"));
        click(screen, "monitor_private_target");
        var monitor = assertInstanceOf(FlowProgram.Monitor.class, workspace.flow().orElseThrow().steps().getFirst());
        assertTrue(monitor.privateLocal()); assertEquals("counter", monitor.cell());
        assertFalse((boolean)get(screen, "parameters"));
        click(screen, "undo");
        assertFalse(((FlowProgram.Monitor)workspace.flow().orElseThrow().steps().getFirst()).privateLocal());
        bounds(screen);
    }

    @Test void monitorWithoutAnyDeclaredTargetCannotCreateAnInvalidBlock() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        var screen = FlowCallScreenTest.editor(workspace, 320);
        click(screen, "category_variables"); FlowPaletteScreenTest.search(screen).setValue("monitor");
        assertFalse(button(screen, "add_monitor_show").active);
        assertFalse(button(screen, "add_monitor_hide").active);
        assertTrue(workspace.flow().orElseThrow().steps().isEmpty());
    }
}
