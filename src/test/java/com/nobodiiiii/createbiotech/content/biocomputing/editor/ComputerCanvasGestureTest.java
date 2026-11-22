package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.*;

class ComputerCanvasGestureTest {
    private final ComputerProgramScreenTest fixture = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { fixture.client(); }
    @AfterEach void restore() throws Exception { fixture.restore(); }

    @Test void missingMotionCallbacksStillMoveExistingNodeToReleasePositionAtCurrentZoom() throws Exception {
        var screen = screen(); var draft = (ProgramDraft) get(screen, "draft");
        String id = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(5)), 20, 20); draft.output("answer", id);
        var viewport = (CanvasViewport) get(screen, "viewport"); viewport.zoomAt(0, 0, 8); viewport.pan(7, 9);
        double x = coordinate(screen, "canvasX") + viewport.screenX(28);
        double y = coordinate(screen, "canvasY") + viewport.screenY(25);
        var before = draft.snapshot();
        assertTrue(screen.mouseClicked(x, y, 0));
        assertTrue(screen.mouseReleased(x + 84, y + 36, 0));
        assertEquals(62, draft.node(id).x()); assertEquals(38, draft.node(id).y());
        assertNull(get(screen, "editing"), "a drag must not open the node configuration");
        assertEquals(before, draft.snapshot(), "only layout changes, not executable facts");
        assertFalse((boolean) get(screen, "requested"));
    }

    @Test void partialOrCompleteMotionDoesNotDoubleCountReleaseAndDocumentsKeepConnections() throws Exception {
        for (double fraction : List.of(0.25, 1.0)) {
            var screen = screen(); var workspace = (ProgramWorkspace) get(screen, "workspace"); var draft = workspace.root();
            String source = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(5)), 20, 20);
            String list = draft.add(new ComputationGraph.MakeList(List.of("source")), 240, 20);
            draft.connect(list, "0", source); draft.output("answer", list);
            var before = workspace.snapshot();
            double x = coordinate(screen, "canvasX") + 28, y = coordinate(screen, "canvasY") + 25;
            screen.mouseClicked(x, y, 0);
            screen.mouseDragged(x + 60 * fraction, y + 40 * fraction, 0, 60 * fraction, 40 * fraction);
            screen.mouseReleased(x + 60, y + 40, 0);
            assertEquals(80, draft.node(source).x()); assertEquals(60, draft.node(source).y());
            assertNull(get(screen, "editing"));
            var after = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
            assertEquals(before.archive(), after.archive());
            var imported = new ProgramWorkspace(after).root();
            assertEquals(80, imported.node(source).x()); assertEquals(60, imported.node(source).y());
            assertEquals(Map.of("0", source), imported.node(list).connections());
            assertFalse((boolean) get(screen, "requested"));
        }
    }

    @Test void stationaryClickStillOpensConfigurationWithoutChangingPosition() throws Exception {
        var screen = screen(); var draft = (ProgramDraft) get(screen, "draft");
        String id = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(5)), 20, 20);
        double x = coordinate(screen, "canvasX") + 28, y = coordinate(screen, "canvasY") + 25;
        screen.mouseClicked(x, y, 0); screen.mouseReleased(x, y, 0);
        assertEquals(id, get(screen, "editing")); assertEquals(20, draft.node(id).x()); assertEquals(20, draft.node(id).y());
    }

    @Test void releasingAnotherButtonDoesNotEndOrMoveTheOwnedGesture() throws Exception {
        var screen = screen(); var draft = (ProgramDraft) get(screen, "draft");
        String id = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(5)), 20, 20);
        double x = coordinate(screen, "canvasX") + 28, y = coordinate(screen, "canvasY") + 25;
        screen.mouseClicked(x, y, 0); screen.mouseReleased(x + 50, y + 20, 2);
        assertEquals(20, draft.node(id).x()); assertNull(get(screen, "editing"));
        screen.mouseReleased(x + 30, y + 10, 0);
        assertEquals(50, draft.node(id).x()); assertEquals(30, draft.node(id).y()); assertNull(get(screen, "editing"));
    }

    @Test void rebuildingScreenCancelsUnreleasedGestureRatherThanMovingAnOldNode() throws Exception {
        var screen = screen(); var draft = (ProgramDraft) get(screen, "draft");
        String id = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(5)), 20, 20);
        double x = coordinate(screen, "canvasX") + 28, y = coordinate(screen, "canvasY") + 25;
        screen.mouseClicked(x, y, 0); set(screen, "width", 620); screen.init();
        screen.mouseReleased(x + 50, y + 20, 0);
        assertEquals(20, draft.node(id).x()); assertEquals(20, draft.node(id).y()); assertNull(get(screen, "editing"));
    }

    @Test void coalescedMiddlePanPreservesNodeAndUnappliedInlineText() throws Exception {
        for (boolean inline : List.of(false, true)) {
            var screen = screen(); var draft = (ProgramDraft) get(screen, "draft");
            String id = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(5)), 20, 20);
            EditBox field = null;
            if (inline) {
                double ix = coordinate(screen, "canvasX") + 28, iy = coordinate(screen, "canvasY") + 44;
                screen.mouseClicked(ix, iy, 0); screen.mouseReleased(ix, iy, 0);
                field = screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
                field.setValue("not-yet-valid");
            }
            var viewport = (CanvasViewport) get(screen, "viewport");
            double x = coordinate(screen, "canvasX") + 200, y = coordinate(screen, "canvasY") + 110;
            double oldX = viewport.screenX(0), oldY = viewport.screenY(0);
            screen.mouseClicked(x, y, 2); screen.mouseReleased(x + 25, y - 14, 2);
            assertEquals(oldX + 25, viewport.screenX(0)); assertEquals(oldY - 14, viewport.screenY(0));
            assertEquals(20, draft.node(id).x()); assertEquals(20, draft.node(id).y()); assertNull(get(screen, "editing"));
            if (inline) { assertNotNull(get(screen, "inline")); assertEquals("not-yet-valid", field.getValue()); }
            assertEquals(new ComputationGraph.Constant(new DataValue.IntegerValue(5)), draft.node(id).definition());
        }
    }

    @Test void coalescedWireGestureOnlyBindsPortsAndDoesNotMoveEitherNode() throws Exception {
        var screen = screen(); var draft = (ProgramDraft) get(screen, "draft");
        String source = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(5)), 20, 20);
        String list = draft.add(new ComputationGraph.MakeList(List.of("value")), 240, 20);
        var from = ComputerGraphPorts.position(draft, new ComputerGraphPorts.Socket(source, null));
        var to = ComputerGraphPorts.position(draft, new ComputerGraphPorts.Socket(list, "0"));
        int left = coordinate(screen, "canvasX"), top = coordinate(screen, "canvasY");
        screen.mouseClicked(left + from.x(), top + from.y(), 0); screen.mouseReleased(left + to.x(), top + to.y(), 0);
        assertEquals(Map.of("0", source), draft.node(list).connections());
        assertEquals(20, draft.node(source).x()); assertEquals(240, draft.node(list).x()); assertNull(get(screen, "editing"));
    }

    private static int coordinate(ComputerProgramScreen screen, String name) throws Exception {
        var method = ComputerProgramScreen.class.getDeclaredMethod(name); method.setAccessible(true); return (int) method.invoke(screen);
    }
}
