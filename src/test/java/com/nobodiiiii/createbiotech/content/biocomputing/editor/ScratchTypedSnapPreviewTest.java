package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.button;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.set;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.*;

class ScratchTypedSnapPreviewTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void start() throws Exception { client.client(); }
    @AfterEach void stop() throws Exception { client.restore(); }

    @Test void toolboxPreviewAgreesWithTypedDropAndDoesNotEditUntilRelease() throws Exception {
        var workspace = new ProgramWorkspace();
        var condition = new FlowInput.Literal(new DataValue.BooleanValue(true));
        var count = new FlowInput.Literal(new DataValue.IntegerValue(10));
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(
            new FlowProgram.Repeat("count", count), new FlowProgram.EndRepeat("end_count"),
            new FlowProgram.If("check", condition), new FlowProgram.EndIf("end_check")),
            new DataValue.RecordValue(Map.of()), FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        set(screen, "height", 360);
        screen.init();
        var before = workspace.snapshot();
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        var slot = canvas.inlineSlots().stream().filter(s -> "count".equals(s.call())).findFirst().orElseThrow();
        var booleanSlot = canvas.inlineSlots().stream().filter(s -> "check".equals(s.call())).findFirst().orElseThrow();
        double x = slot.x() + slot.width() / 2, y = slot.y() + slot.height() / 2;
        double bx = booleanSlot.x() + booleanSlot.width() / 2, by = booleanSlot.y() + booleanSlot.height() / 2;
        assertEquals(slot, canvas.inlineHit(x, y));
        assertEquals(booleanSlot, canvas.inlineHit(bx, by));
        take(screen, "and_boolean");
        assertNull(screen.reporterSnapTarget(x, y), "An incompatible value must not advertise an attachment");
        assertEquals(booleanSlot, screen.reporterSnapTarget(bx, by));
        assertEquals(booleanSlot, screen.reporterSnapTarget(bx + 1, by));
        assertEquals(before, workspace.snapshot());
        assertFalse(button(screen, "undo").active);
        assertTrue(screen.mouseReleased(x, y, 0));
        assertEquals(before, workspace.snapshot());
        take(screen, "random_integer");
        assertEquals(slot, screen.reporterSnapTarget(x, y), "Switching the carried block must invalidate the old preview");
        assertEquals(before, workspace.snapshot());
        assertTrue(screen.mouseReleased(x, y, 0));
        assertNotEquals(before, workspace.snapshot());
    }

    @Test void anExistingParkedBooleanOnlyPreviewsTheBooleanSocket() throws Exception {
        var bool = new DataValue.BooleanValue(true);
        var one = new FlowInput.Literal(new DataValue.IntegerValue(1));
        var graph = new ComputationGraph(Map.of(), Map.of(
            "lhs", new ComputationGraph.Constant(bool), "rhs", new ComputationGraph.Constant(bool),
            "and", new ComputationGraph.Scalar(ScalarOperation.AND_BOOLEAN, Map.of("left", "lhs", "right", "rhs"))),
            Map.of("value", "and"));
        var reporter = new ScratchValueLayout.Reporter(new ProgramDocument.Position(265, 42), graph, Map.of(), Map.of());
        var layout = FlowBlockLayout.DEFAULT.withValues(new ScratchValueLayout(Map.of(), List.of(reporter)));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(
            new FlowProgram.Repeat("loop", one), new FlowProgram.EndRepeat("loop_end"),
            new FlowProgram.If("guard", new FlowInput.Literal(bool)), new FlowProgram.EndIf("guard_end")),
            new DataValue.RecordValue(Map.of()), layout, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        set(screen, "height", 360);
        screen.init();
        var before = workspace.snapshot();
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        var parked = canvas.parkedSlotHit((int)get(canvas, "x") + 270, (int)get(canvas, "y") + 47);
        assertNotNull(parked);
        assertEquals("and", parked.slot().node());
        assertTrue(screen.mouseClicked(parked.slot().x() + 5, parked.slot().y() + 5, 0));
        var loop = canvas.inlineSlots().stream().filter(s -> "loop".equals(s.call())).findFirst().orElseThrow();
        var guard = canvas.inlineSlots().stream().filter(s -> "guard".equals(s.call())).findFirst().orElseThrow();
        assertNull(screen.reporterSnapTarget(loop.x() + 7, loop.y() + 9));
        assertEquals(guard, screen.reporterSnapTarget(guard.x() + 7, guard.y() + 9));
        assertEquals(before, workspace.snapshot());
        assertTrue(screen.mouseReleased(guard.x() + 7, guard.y() + 9, 0));
        assertTrue(workspace.flow().orElseThrow().canvas().values().parked().isEmpty());
        assertNotEquals(before, workspace.snapshot());
    }

    private static void take(ComputerFlowScreen screen, String name) throws Exception {
        ((EditBox)get(screen, "catalogSearch")).setValue(name);
        var tile = button(screen, "inline_" + name);
        assertTrue(screen.mouseClicked(tile.getX() + 8, tile.getY() + 8, 0));
    }
}
