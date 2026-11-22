package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.*;

class FlowCanvasScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restore() throws Exception { environment.restore(); }
    @Test void aNewComputerOpensTheBlockWorkspaceByDefaultWithoutInstalling() throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var opened = new net.minecraft.client.gui.screens.Screen[1];
        set(parent, "navigator", (java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(screen -> opened[0] = screen));
        set(parent, "draft", null); set(parent, "workspace", null); set(parent, "requested", true);
        set(menu, "responseVersion", 1L); parent.containerTick();
        assertInstanceOf(ComputerFlowScreen.class, opened[0]);
        assertTrue(((ProgramWorkspace)get(parent, "workspace")).flow().isPresent());
        assertFalse((boolean)get(parent, "waiting"));
    }
    @Test void realDragParksTheWholeSuffixAndSnapRestoresItWithoutInstalling() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(new FlowProgram.Correlation("a", "a"),
            new FlowProgram.Correlation("b", "b")), new DataValue.RecordValue(Map.of()))));
        var original = workspace.snapshot(); var screen = FlowCallScreenTest.editor(workspace, 640);
        int left = (int)get(get(screen, "canvas"), "x"), top = (int)get(get(screen, "canvas"), "y");
        int x = left + 24, y = top + 50, dropX = left + 300;
        assertTrue(screen.mouseClicked(x, y, 0));
        assertTrue(screen.mouseDragged(dropX, y, 0, dropX - x, 0));
        assertEquals(original, workspace.snapshot(), "Preview cannot mutate the draft");
        assertTrue(screen.mouseReleased(dropX, y, 0));
        var parked = workspace.snapshot(); assertTrue(parked.flow().orElseThrow().steps().isEmpty());
        assertEquals(2, parked.flow().orElseThrow().canvas().detached().getFirst().steps().size());
        assertEquals(parked, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(parked)));
        assertFalse((boolean)get(screen, "waiting"));
        assertTrue(screen.mouseClicked(dropX, y, 0));
        screen.mouseDragged(x, y, 0, x - dropX, 0); screen.mouseReleased(x, y, 0);
        assertEquals(original, workspace.snapshot());
        click(screen, "undo"); assertEquals(parked, workspace.snapshot());
        click(screen, "redo"); assertEquals(original, workspace.snapshot());
        screen.mouseClicked(x, y, 0); screen.mouseDragged(dropX, y, 0, dropX - x, 0);
        assertTrue(screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0));
        screen.mouseReleased(dropX, y, 0); assertEquals(original, workspace.snapshot());
        screen.mouseClicked(x, y, 0); screen.mouseDragged(dropX, y, 0, dropX - x, 0);
        screen.mouseReleased(0, 0, 0); assertEquals(original, workspace.snapshot());
        screen.mouseClicked(x, y, 0); screen.mouseReleased(dropX, y, 0);
        screen.mouseClicked(left + 24, top + 22, 0); screen.mouseReleased(left + 24, top + 22, 0);
        click(screen, "add_repeat"); click(screen, "input_literal"); boxes(screen).getFirst().setValue("2"); click(screen, "apply");
        assertEquals(2, workspace.flow().orElseThrow().steps().size(), "Selecting the main hat must not append new blocks to the previously selected detached stack");
        assertEquals(2, workspace.flow().orElseThrow().canvas().detached().getFirst().steps().size());
    }
}
