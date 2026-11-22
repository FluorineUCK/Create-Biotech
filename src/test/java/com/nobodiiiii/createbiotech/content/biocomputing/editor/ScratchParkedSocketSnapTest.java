package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.TextOperation;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class ScratchParkedSocketSnapTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void start() throws Exception { client.client(); }
    @AfterEach void stop() throws Exception { client.restore(); }

    @Test void composingParkedArgumentsPreservesProcedureIdentityAndRejectsCrossScopeCapture() {
        var number = DataType.Scalar.INTEGER;
        var scope = new ScratchValueLayout.ProcedureScope("definition_a", new ModuleDefinition.Key("local", 1));
        var other = new ScratchValueLayout.ProcedureScope("definition_b", scope.key());
        var parent = new ScratchValueLayout.Reporter(new ProgramDocument.Position(0, 0),
            new ComputationGraph(Map.of("x", number), Map.of(
                "root", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "argument", "socket"),
                "argument", new ComputationGraph.Input("x"),
                "socket", new ComputationGraph.Constant(new DataValue.IntegerValue(0))), Map.of("value", "root")),
            Map.of("x", "x"), Map.of()).scoped(scope);
        var input = new ScratchValueLayout.Reporter(new ProgramDocument.Position(20, 20),
            new ComputationGraph(Map.of("x", number), Map.of("value", new ComputationGraph.Input("x")),
                Map.of("value", "value")), Map.of("x", "x"), Map.of());
        assertThrows(IllegalArgumentException.class, () -> parent.attachChild("socket", input.scoped(other)),
            "A same-named argument from another definition must not capture this procedure's local");
        assertThrows(IllegalArgumentException.class, () -> parent.attachChild("socket", input),
            "A main-run argument must not capture a procedure local either");
        var joined = parent.attachChild("socket", input.scoped(scope));
        assertEquals(Optional.of(scope), joined.subtree("socket").procedureScope());
        assertEquals(parent.graph(), joined.restoreChild("socket", new DataValue.IntegerValue(0)).graph());
        assertEquals(Optional.empty(), parent.subtree("socket").procedureScope(),
            "A literal fragment must not retain an unrelated local scope");
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.editValues(ScratchValueLayout.EMPTY.withParked(List.of(joined)));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(draft.snapshot()));
        var document = workspace.snapshot();
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
    }

    @Test void composeAStandaloneExpressionFromThePaletteThenDetachItsChild() throws Exception {
        var position = new ProgramDocument.Position(36, 94);
        var old = new DataValue.TextValue("saved");
        var parent = new ScratchValueLayout.Reporter(position, new ComputationGraph(Map.of(), Map.of(
            "root", new ComputationGraph.Text(TextOperation.CONCAT, Map.of("left", "socket", "right", "tail")),
            "socket", new ComputationGraph.Constant(old), "tail", new ComputationGraph.Constant(new DataValue.TextValue("outside"))),
            Map.of("value", "root")), Map.of(), Map.of());
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.editValues(ScratchValueLayout.EMPTY.withParked(List.of(parent)));
        var ws = new ProgramWorkspace();
        ws.setFlow(Optional.of(draft.snapshot()));
        var screen = FlowCallScreenTest.editor(ws, 640);
        var before = ws.snapshot();

        FlowPaletteScreenTest.search(screen).setValue("text_concat");
        var tile = button(screen, "inline_text_concat");
        var xy = socketPoint(screen, position, "socket");
        assertTrue(screen.mouseClicked(tile.getX() + 8, tile.getY() + 8, 0));
        screen.mouseDragged(xy[0], xy[1], 0, 40, 0);
        assertTrue(screen.mouseReleased(xy[0], xy[1], 0));
        var joined = ws.snapshot();
        var parked = joined.flow().orElseThrow().canvas().values().parked();
        assertEquals(1, parked.size(), "The value must snap into the outer expression rather than parking twice");
        var edit = parked.getFirst();
        assertEquals(position, edit.position());
        assertInstanceOf(ComputationGraph.Text.class, edit.graph().nodes().get("socket"), "The target now contains the nested reporter");
        assertEquals(old, edit.shadows().get("socket"));
        assertEquals(joined, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(joined)));
        assertEquals(before.flow().orElseThrow().program(before.archive()), joined.flow().orElseThrow().program(joined.archive()),
            "Connected parked expressions still do not run");

        FlowPaletteScreenTest.search(screen).setValue("");
        xy = socketPoint(screen, position, "socket");
        assertTrue(screen.mouseClicked(xy[0], xy[1], 1));
        click(screen, "clipboard_cut");
        assertEquals(parent.graph(), ws.flow().orElseThrow().canvas().values().parked().getFirst().graph());
        click(screen, "undo"); assertEquals(joined, ws.snapshot());
        click(screen, "undo"); assertEquals(before, ws.snapshot());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void movingAnExistingReporterIntoAnOccupiedSocketIsAtomic(boolean compatible) throws Exception {
        var from = new ProgramDocument.Position(36, 45);
        var to = new ProgramDocument.Position(36, 98);
        var sourceGraph = new ComputationGraph(Map.of(), Map.of(
            "root", compatible ? new ComputationGraph.Text(TextOperation.CONCAT, Map.of("left", "a", "right", "b")) : new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "a", "b"),
            "a", new ComputationGraph.Constant(compatible ? new DataValue.TextValue("new") : new DataValue.IntegerValue(1)),
            "b", new ComputationGraph.Constant(compatible ? new DataValue.TextValue(" value") : new DataValue.IntegerValue(2))),
            Map.of("value", "root"));
        var incoming = new ScratchValueLayout.Reporter(from, sourceGraph, Map.of(), Map.of());
        var parent = new ScratchValueLayout.Reporter(to, new ComputationGraph(Map.of(), Map.of(
            "root", new ComputationGraph.Text(TextOperation.CONCAT, Map.of("left", "socket", "right", "tail")),
            "socket", new ComputationGraph.Text(TextOperation.CONCAT, Map.of("left", "old_a", "right", "old_b")),
            "tail", new ComputationGraph.Constant(new DataValue.TextValue("outside")),
            "old_a", new ComputationGraph.Constant(new DataValue.TextValue("old")),
            "old_b", new ComputationGraph.Constant(new DataValue.TextValue(" value"))), Map.of("value", "root")),
            Map.of(), Map.of("socket", new DataValue.TextValue("shadow")));
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.editValues(ScratchValueLayout.EMPTY.withParked(List.of(incoming, parent)));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(draft.snapshot()));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        var before = workspace.snapshot();

        var src = socketPoint(screen, from, "root");
        var dest = socketPoint(screen, to, "socket");
        assertTrue(screen.mouseClicked(src[0], src[1], 0));
        screen.mouseDragged(dest[0], dest[1], 0, 0, 53);
        assertTrue(screen.mouseReleased(dest[0], dest[1], 0));
        if (!compatible) {
            assertEquals(before, workspace.snapshot(), "A rejected drag must preserve both original expressions and coordinates");
            assertFalse(button(screen, "undo").active);
            return;
        }
        var after = workspace.snapshot();
        var parked = after.flow().orElseThrow().canvas().values().parked();
        assertEquals(2, parked.size(), "The old occupied child is parked instead of discarded");
        assertEquals(to, parked.getFirst().position());
        assertEquals(new DataValue.TextValue("shadow"), parked.getFirst().shadows().get("socket"));
        var now = assertInstanceOf(ComputationGraph.Text.class, parked.getFirst().graph().nodes().get("socket"));
        assertEquals(new ComputationGraph.Constant(new DataValue.TextValue("new")),
            parked.getFirst().graph().nodes().get(now.arguments().get("left")));
        var displaced = parked.getLast().graph();
        var retained = assertInstanceOf(ComputationGraph.Text.class, displaced.nodes().get(displaced.outputs().get("value")));
        assertEquals(new ComputationGraph.Constant(new DataValue.TextValue("old")),
            displaced.nodes().get(retained.arguments().get("left")));
        click(screen, "undo"); assertEquals(before, workspace.snapshot());
    }
    private static int[] socketPoint(ComputerFlowScreen screen, ProgramDocument.Position position, String name) throws Exception {
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        var vp = (CanvasViewport)get(canvas, "viewport");
        int x0 = (int)get(canvas, "x") + (int)vp.screenX(position.x());
        int y0 = (int)get(canvas, "y") + (int)vp.screenY(position.y());
        for (int y = y0 + 2; y < y0 + 30; y += 2) for (int x = x0 + 2; x < x0 + 245; x += 2) {
            var slot = canvas.parkedSlotHit(x, y);
            if (slot != null && slot.slot().node().equals(name)) return new int[]{x, y};
        }
        throw new AssertionError("Unable to locate parked socket " + name);
    }

    private static CreateTextButton button(ComputerFlowScreen screen, String suffix) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents key && key.getKey().endsWith("." + suffix))
            .findFirst().orElseThrow();
    }

    private static void click(ComputerFlowScreen screen, String suffix) {
        var button = button(screen, suffix);
        assertTrue(button.active, suffix);
        screen.mouseClicked(button.getX() + 6, button.getY() + 6, 0);
        screen.mouseReleased(button.getX() + 6, button.getY() + 6, 0);
    }
}
