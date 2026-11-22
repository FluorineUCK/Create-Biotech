package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.TextOperation;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;
import org.lwjgl.glfw.GLFW;

class ScratchParkedChildInteractionTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void start() throws Exception { client.client(); }
    @AfterEach void stop() throws Exception { client.restore(); }

    @Test void cutOnlyTheSelectedChildOfAnUnattachedReporterAndPasteThatChild() throws Exception {
        var original = new ScratchValueLayout.Reporter(new ProgramDocument.Position(36, 94), new ComputationGraph(Map.of(), Map.of(
            "root", join("child", "outside"), "child", join("left", "right"),
            "outside", literal("!"), "left", literal("hello"), "right", literal(" there")), Map.of("value", "root")),
            Map.of(), Map.of("child", new DataValue.TextValue("old")));
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.editValues(ScratchValueLayout.EMPTY.withParked(List.of(original)));
        var ws = new ProgramWorkspace();
        ws.setFlow(Optional.of(draft.snapshot()));
        var screen = FlowCallScreenTest.editor(ws, 640);
        var before = ws.snapshot();
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        var viewport = (CanvasViewport)get(canvas, "viewport");
        int x0 = (int)get(canvas, "x") + (int)viewport.screenX(original.position().x());
        int y0 = (int)get(canvas, "y") + (int)viewport.screenY(original.position().y());
        assertTrue(openChildMenu(screen, canvas, x0, y0), "Right click must hit the inside reporter's own shape");
        click(screen, "clipboard_cut");
        var cut = ws.snapshot();
        var leftOnCanvas = cut.flow().orElseThrow().canvas().values().parked();
        assertEquals(1, leftOnCanvas.size(), "Cutting a nested reporter must not remove its outer parked expression");
        assertEquals(original.position(), leftOnCanvas.getFirst().position());
        var edited = leftOnCanvas.getFirst().graph();
        assertEquals(join("child", "outside"), edited.nodes().get("root"));
        assertEquals(literal("old"), edited.nodes().get("child"));
        assertFalse(edited.nodes().containsKey("left"));
        assertFalse(edited.nodes().containsKey("right"));
        assertTrue(screen.keyPressed(GLFW.GLFW_KEY_V, 0, GLFW.GLFW_MOD_CONTROL));
        var pasted = ws.flow().orElseThrow().canvas().values().parked();
        assertEquals(2, pasted.size());
        var inner = pasted.getLast().graph();
        assertEquals(join("left", "right"), inner.nodes().get(inner.outputs().get("value")));
        assertFalse(inner.nodes().containsKey("outside"));
        click(screen, "undo");
        assertEquals(cut, ws.snapshot());
        click(screen, "undo");
        assertEquals(before, ws.snapshot());
    }

    private static ComputationGraph.Text join(String l, String r) {
        return new ComputationGraph.Text(TextOperation.CONCAT, Map.of("left", l, "right", r));
    }

    private static ComputationGraph.Constant literal(String text) {
        return new ComputationGraph.Constant(new DataValue.TextValue(text));
    }

    private static boolean openChildMenu(ComputerFlowScreen screen, FlowBlockCanvas canvas, int x0, int y0) {
        for (int y = y0 + 2; y < y0 + 30; y += 2) for (int x = x0 + 2; x < x0 + 246; x += 2) {
            var slot = canvas.parkedSlotHit(x, y);
            if (slot != null && slot.slot().node().equals("child"))
                return screen.mouseClicked(x, y, 1);
        }
        return false;
    }

    private static void click(ComputerFlowScreen screen, String suffix) {
        var button = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents key && key.getKey().endsWith("." + suffix))
            .findFirst().orElseThrow();
        assertTrue(button.active, suffix);
        screen.mouseClicked(button.getX() + 6, button.getY() + 6, 0);
        screen.mouseReleased(button.getX() + 6, button.getY() + 6, 0);
    }
}
