package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.button;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.click;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.*;

class ScratchDisplacedReporterPlacementTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();

    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void start() throws Exception { client.client(); }
    @AfterEach void stop() throws Exception { client.restore(); }

    @Test
    void aDisplacedValueDoesNotCoverTheLoopOrMoveTheScript() throws Exception {
        var old = new ComputationGraph(Map.of(), Map.of(
            "root", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "left", "right"),
            "left", new ComputationGraph.Constant(new DataValue.IntegerValue(2)),
            "right", new ComputationGraph.Constant(new DataValue.IntegerValue(3))), Map.of("value", "root"));
        var steps = List.<FlowProgram.Step>of(new FlowProgram.Repeat("loop", new FlowInput.Expression("loop", "value", Map.of())),
            new FlowProgram.Sleep("wait", new FlowInput.Literal(new DataValue.IntegerValue(3))),
            new FlowProgram.EndRepeat("end"));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), steps, new DataValue.RecordValue(Map.of()),
            FlowBlockLayout.DEFAULT, Map.of("loop", old), FlowProgram.Execution.REGISTER_V1)));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        var before = workspace.snapshot();
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        var target = canvas.inlineSlots().stream().filter(s -> "root".equals(s.node())).findFirst().orElseThrow();
        double x = target.x() + 8, y = target.y() + target.height() / 2;
        assertEquals("root", canvas.inlineHit(x, y).node());
        ((EditBox)get(screen, "catalogSearch")).setValue("random_integer");
        var tile = button(screen, "inline_random_integer");
        assertTrue(screen.mouseClicked(tile.getX() + 8, tile.getY() + 8, 0));
        assertTrue(screen.mouseReleased(x, y, 0));

        var after = workspace.snapshot();
        var edited = after.flow().orElseThrow().canvas();
        assertEquals(before.flow().orElseThrow().canvas().main(), edited.main());
        assertEquals(1, edited.values().parked().size());
        var loose = edited.values().parked().getFirst();
        assertEquals(old, loose.graph());
        var updated = (FlowBlockCanvas)get(screen, "canvas");
        var row = updated.blocks().stream().filter(b -> b.step().sourceId().equals("loop")).findFirst().orElseThrow();
        double bottom = updated.blocks().stream().mapToDouble(b -> b.y() + b.height()).max().orElseThrow();
        assertTrue(loose.position().y() >= bottom + 5 || loose.position().x() >= row.x() + row.width(),
            () -> "The loose expression must not cover the C-shaped stack: " + loose.position() + ", bottom=" + bottom);
        click(screen, "undo");
        assertEquals(before, workspace.snapshot());
        click(screen, "redo");
        assertEquals(after, workspace.snapshot());
    }
}
