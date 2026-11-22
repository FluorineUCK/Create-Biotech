package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.button;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.click;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class ScratchFlyoutScrollScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restore() throws Exception { environment.restore(); }

    @Test void wheelMovesExactlyOneRowAndThumbCanBeDraggedWithoutEditingTheProgram() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var original = workspace.snapshot();
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "category_operators");
        var first = visible(screen);
        var expected = ((List<?>)get(screen, "catalogEntries")).stream().map(e -> ((FlowBlockCatalog.Entry)e).id()).toList();
        assertTrue(first.size() > 2 && expected.size() > first.size() * 2);
        int rowX = (int)get(screen, "leftPos") + (int)get(screen, "paletteX");
        int y = button(screen, "inline_add_integer").getY() + 6;
        assertTrue(screen.mouseScrolled(rowX + 4, y, 0, -0.5));
        assertEquals(first, visible(screen), "A fractional wheel event does not flip the page");
        assertTrue(screen.mouseScrolled(rowX + 4, y, 0, -0.5));
        assertEquals(expected.subList(1, first.size() + 1), visible(screen));
        assertTrue(button(screen, "palette_previous").active);
        assertEquals(original, workspace.snapshot());

        int track = rowX + (int)get(screen, "paletteWidth") + 3;
        int bottom = button(screen, "palette_next").getY() - 3;
        assertTrue(screen.mouseClicked(track, y, 0), "Start dragging on the palette track");
        assertTrue(screen.mouseReleased(track, y, 1), "The other button must not end the active left drag");
        assertTrue(screen.mouseDragged(track + 20, bottom, 0, 20, bottom - y));
        assertTrue(screen.mouseReleased(track + 20, bottom, 0));
        assertEquals(expected.getLast(), visible(screen).getLast());
        assertFalse(button(screen, "palette_next").active);

        click(screen, "category_control");
        click(screen, "category_operators");
        assertEquals(first, visible(screen));
        assertEquals(original, workspace.snapshot());
        assertFalse(button(screen, "undo").active);
    }

    private static List<String> visible(ComputerFlowScreen screen) throws Exception {
        return ((Map<?, ?>)get(screen, "hintCatalog")).values().stream()
            .map(e -> ((FlowBlockCatalog.Entry)e).id()).toList();
    }
}
