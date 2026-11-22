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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScratchOccupiedValueDropTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();

    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void start() throws Exception { client.client(); }
    @AfterEach void stop() throws Exception { client.restore(); }

    @ParameterizedTest
    @ValueSource(strings = {"replace", "wrap", "reject"})
    void draggingFromThePaletteOntoAnOccupiedInlineSocketPreservesTheOldExpressionOnce(String action) throws Exception {
        var old = new ComputationGraph(Map.of(), Map.of(
            "root", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "left", "right"),
            "left", new ComputationGraph.Scalar(ScalarOperation.MULTIPLY_INTEGER, Map.of("left", "a", "right", "b")),
            "right", new ComputationGraph.Constant(new DataValue.IntegerValue(7)),
            "a", new ComputationGraph.Constant(new DataValue.IntegerValue(2)),
            "b", new ComputationGraph.Constant(new DataValue.IntegerValue(3))),
            Map.of("value", "root"));
        var outerDefault = new DataValue.IntegerValue(42);
        var innerDefault = new DataValue.IntegerValue(9);
        var values = new ScratchValueLayout(Map.of("assign", Map.of("root", outerDefault, "left", innerDefault)), List.of());
        var write = new FlowProgram.Shared("assign", "counter", new FlowInput.Expression("assign", "value", Map.of()), false);
        var doc = new FlowDocumentBody(Map.of(), List.of(write), new DataValue.RecordValue(Map.of()),
            FlowBlockLayout.DEFAULT.withValues(values), Map.of("assign", old), FlowProgram.Execution.REGISTER_V1);
        var workspace = new ProgramWorkspace();
        workspace.root().putCell("counter", DataType.Scalar.INTEGER, new DataValue.IntegerValue(0));
        workspace.setFlow(Optional.of(doc));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        var before = workspace.snapshot();
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        var target = canvas.inlineSlots().stream().filter(s -> "root".equals(s.node())).findFirst().orElseThrow();
        double x = target.x() + 8, y = target.y() + target.height() / 2;
        assertEquals("root", canvas.inlineHit(x, y).node());

        String reporter = switch (action) {
            case "replace" -> "inline_random_integer";
            case "wrap" -> "inline_multiply_integer";
            case "reject" -> "inline_and_boolean";
            default -> throw new AssertionError(action);
        };
        ((EditBox)get(screen, "catalogSearch")).setValue(reporter.substring("inline_".length()));
        var tile = button(screen, reporter);
        assertTrue(screen.mouseClicked(tile.getX() + 8, tile.getY() + 8, 0));
        assertTrue(screen.mouseReleased(x, y, 0));

        if (action.equals("reject")) {
            assertEquals(before, workspace.snapshot(), "A rejected drop must not park anything or edit the draft");
            assertFalse(button(screen, "undo").active);
            return;
        }
        var changed = workspace.snapshot();
        var body = changed.flow().orElseThrow();
        assertNotEquals(old, body.inline().get("assign"));
        assertEquals(outerDefault, body.canvas().values().forBlock("assign").get("root"));
        var displaced = body.canvas().values().parked();
        var incoming = body.inline().get("assign");
        if (action.equals("wrap")) {
            assertTrue(displaced.isEmpty(), "Do not duplicate an expression that was nested as an operand");
            var multiply = assertInstanceOf(ComputationGraph.Scalar.class, incoming.nodes().get("root"));
            assertEquals(ScalarOperation.MULTIPLY_INTEGER, multiply.operation());
            assertEquals(old.nodes().get("root"), incoming.nodes().get(multiply.arguments().get("left")));
            assertEquals(innerDefault, body.canvas().values().forBlock("assign").get("left"));
        } else {
            assertInstanceOf(ComputationGraph.RandomRange.class, incoming.nodes().get("root"));
            assertEquals(1, displaced.size(), "The old expression must remain as a single detached value block");
            assertEquals(old, displaced.getFirst().graph());
            assertEquals(Map.of("left", innerDefault), displaced.getFirst().shadows());
        }
        assertEquals(changed, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(changed)));
        click(screen, "undo");
        assertEquals(before, workspace.snapshot(), "A single undo restores the original slot, including its inner default");
        click(screen, "redo");
        assertEquals(changed, workspace.snapshot());
    }
}
