package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.bounds;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.button;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScratchCanvasZoomControlsTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void close() throws Exception { environment.restore(); }

    @ParameterizedTest @ValueSource(ints = {320, 426, 640})
    void canvasZoomButtonsChangeOnlyTheViewAndPreserveTheVisibleCenter(int width) throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var original = workspace.snapshot();
        var editor = FlowCallScreenTest.editor(workspace, width);
        var canvas = (FlowBlockCanvas) get(editor, "canvas");
        var viewport = (CanvasViewport) get(canvas, "viewport");
        double cx = (int) get(canvas, "width") / 2.0;
        double cy = (int) get(canvas, "height") / 2.0;
        viewport.pan(35, -24);
        double worldX = viewport.worldX(cx);
        double worldY = viewport.worldY(cy);

        var in = zoomButton(editor, "+");
        var out = zoomButton(editor, "−");
        var reset = button(editor, "reset_view");
        bounds(editor);
        for (var control : new CreateTextButton[]{in, out}) {
            assertTrue(control.getX() >= reset.getX()+reset.getWidth(),
                "Keep zoom controls to the right of the corner overlay");
            assertFalse(canvas.contains(control.getX()+control.getWidth()/2.0, control.getY()+control.getHeight()/2.0),
                "Zoom controls must not consume a drop target");
        }
        clickAt(editor, in);
        assertTrue(viewport.scale() > 1);
        assertEquals(worldX, viewport.worldX(cx), 1e-9);
        assertEquals(worldY, viewport.worldY(cy), 1e-9);
        clickAt(editor, out);
        assertEquals(1, viewport.scale(), 1e-9);
        assertEquals(worldX, viewport.worldX(cx), 1e-9);
        assertEquals(worldY, viewport.worldY(cy), 1e-9);
        clickAt(editor, reset);
        assertEquals(cx, viewport.worldX(cx), 1e-9);
        assertEquals(cy, viewport.worldY(cy), 1e-9);
        assertEquals(original, workspace.snapshot());
        assertFalse(button(editor, "undo").active, "View changes must not add undo entries");
    }

    private static CreateTextButton zoomButton(ComputerFlowScreen editor, String sign) {
        return editor.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.visible && b.getMessage().getString().equals(sign))
            .findFirst().orElseThrow(() -> new AssertionError("No visible canvas zoom control: " + sign));
    }

    private static void clickAt(ComputerFlowScreen editor, CreateTextButton button) {
        int x = button.getX()+button.getWidth()/2, y = button.getY()+button.getHeight()/2;
        assertTrue(editor.mouseClicked(x, y, 0), button.getMessage().getString());
        editor.mouseReleased(x,y,0);
    }
}
