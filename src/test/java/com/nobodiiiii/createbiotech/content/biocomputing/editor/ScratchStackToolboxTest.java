package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScratchStackToolboxTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void openClient() throws Exception { environment.client(); }
    @AfterEach void closeClient() throws Exception { environment.restore(); }

    @ParameterizedTest @ValueSource(ints = {320, 640})
    void droppingIntoFlyoutRemovesOnlyCarriedBranchSuffixAndOneUndoRestoresEverything(int width) throws Exception {
        var workspace = workspace();
        var screen = FlowCallScreenTest.editor(workspace, width);
        var before = workspace.snapshot();
        var clipboard = new FlowDraft(workspace.flow().orElseThrow()).copyStatements("otherwise_body");
        workspace.copyScratch(clipboard);
        var block = grab(screen, "erase");
        int[] target = toolbox(screen);
        assertTrue(screen.mouseDragged(target[0], target[1], 0, target[0] - block[0], target[1] - block[1]));
        assertEquals(before, workspace.snapshot(), "Pointer movement is only a preview");
        assertTrue(screen.mouseReleased(target[0], target[1], 0));
        var after = workspace.snapshot();
        assertEquals(List.of("loop", "branch", "keep", "else", "otherwise_body", "end_if", "end_loop", "after"),
            after.flow().orElseThrow().steps().stream().map(FlowProgram.Step::sourceId).toList());
        assertFalse(after.flow().orElseThrow().inline().containsKey("erase"));
        assertTrue(after.flow().orElseThrow().canvas().values().forBlock("erase").isEmpty());
        assertSame(clipboard, workspace.scratchClipboard(), "Deleting is not cutting");
        assertEquals(after, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(after)));
        clickHistory(screen, "undo");
        assertEquals(before, workspace.snapshot(), "A single undo restores slots, children and structure");
        clickHistory(screen, "redo");
        assertEquals(after, workspace.snapshot());
    }

    @Test void releasesOutsideTheFlyoutAndProtectedProcedureRootsDoNotDelete() throws Exception {
        var workspace = workspace();
        var screen = FlowCallScreenTest.editor(workspace, 640);
        var before = workspace.snapshot();
        grab(screen, "erase");
        assertTrue(screen.mouseReleased(0, 0, 0));
        assertEquals(before, workspace.snapshot());
        grab(screen, "definition");
        var target = toolbox(screen);
        assertTrue(screen.mouseReleased(target[0], target[1], 0));
        assertEquals(before, workspace.snapshot(), "A fixed definition is not an ordinary dragged statement");
        assertNull(workspace.scratchClipboard());
    }

    @Test void aParkedStackCanBeDiscardedEvenWhenNoIntermediateMotionArrives() throws Exception {
        var workspace = workspace();
        var screen = FlowCallScreenTest.editor(workspace, 640);
        var before = workspace.snapshot();
        grab(screen, "parked");
        var target = toolbox(screen);
        assertTrue(screen.mouseReleased(target[0], target[1], 0));
        assertEquals(List.of("definition"), workspace.flow().orElseThrow().canvas().detached().stream()
            .map(stack -> stack.steps().getFirst().sourceId()).toList());
        clickHistory(screen, "undo");
        assertEquals(before, workspace.snapshot());
    }

    private static int[] grab(ComputerFlowScreen screen, String id) throws Exception {
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        canvas.selected(id);
        canvas.focus(id);
        var b = canvas.blocks().stream().filter(row -> row.step().sourceId().equals(id)).findFirst().orElseThrow();
        var view = (CanvasViewport)get(canvas, "viewport");
        int x = (int)((int)get(canvas, "x") + view.screenX(b.x() + 7));
        int y = (int)((int)get(canvas, "y") + view.screenY(b.y() + 7));
        assertTrue(screen.mouseClicked(x, y, 0));
        assertEquals(id, canvas.selected(), "Grab the actual statement surface, not its slots");
        return new int[]{x,y};
    }

    private static void clickHistory(ComputerFlowScreen screen, String name) throws Exception {
        var button = screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance)
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast).filter(widget -> widget.getMessage().getContents()
                instanceof net.minecraft.network.chat.contents.TranslatableContents text
                && text.getKey().endsWith("." + name)).findFirst().orElseThrow();
        assertNotNull(button);
        assertTrue(button.active);
        screen.mouseClicked(button.getX() + 6, button.getY() + 6, 0);
        screen.mouseReleased(button.getX() + 6, button.getY() + 6, 0);
    }

    private static int[] toolbox(ComputerFlowScreen screen) throws Exception {
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        return new int[]{(int)get(canvas, "x") - 13, (int)get(canvas, "y") + 13};
    }

    private static ProgramWorkspace workspace() {
        var shadow = new DataValue.IntegerValue(19);
        var graph = new ComputationGraph(Map.of(),
            Map.of("left", new ComputationGraph.Constant(new DataValue.IntegerValue(40)),
                "right", new ComputationGraph.Constant(new DataValue.IntegerValue(2)),
                "value", new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER, Map.of("left","left","right","right"))),
            Map.of("value", "value"));
        List<FlowProgram.Step> steps = List.of(
            new FlowProgram.Repeat("loop", number(2)),
            new FlowProgram.If("branch", new FlowInput.Literal(new DataValue.BooleanValue(false))),
            new FlowProgram.Sleep("keep", number(1)),
            new FlowProgram.Evaluate("erase", "inline_erase", new FlowCallBindings(Map.of(), Map.of("value", "result"))),
            new FlowProgram.Sleep("suffix", number(3)),
            new FlowProgram.Otherwise("else"),
            new FlowProgram.Sleep("otherwise_body", number(4)),
            new FlowProgram.EndIf("end_if"), new FlowProgram.EndRepeat("end_loop"),
            new FlowProgram.Sleep("after", number(5)));
        var definition = new FlowProgram.Procedure("definition", new ModuleDefinition.Key("def", 1), Map.of(), Map.of(), Map.of());
        var layout = FlowBlockLayout.DEFAULT.arranged(FlowBlockLayout.DEFAULT.main(), List.of(
            new FlowBlockLayout.Stack(new ProgramDocument.Position(258,24),
                List.of(new FlowProgram.Sleep("parked", number(5)), new FlowProgram.Sleep("parked_tail", number(6)))),
            new FlowBlockLayout.Stack(new ProgramDocument.Position(258,126),
                List.of(definition, new FlowProgram.Sleep("definition_body", number(8)), new FlowProgram.EndProcedure("definition_end")))))
            .withValues(new ScratchValueLayout(Map.of("erase", Map.of("value", shadow)), List.of()));
        var ws = new ProgramWorkspace();
        ws.setFlow(Optional.of(new FlowDocumentBody(Map.of(), steps, new DataValue.RecordValue(Map.of()), layout,
            Map.of("erase", graph), FlowProgram.Execution.REGISTER_V1)));
        return ws;
    }
    private static FlowInput.Literal number(int value) { return new FlowInput.Literal(new DataValue.IntegerValue(value)); }
}



