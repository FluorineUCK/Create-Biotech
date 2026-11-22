package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScratchHatStackLayoutTest {
    private static final ProgramDocument.Position AT = new ProgramDocument.Position(36, 104);
    private static final ModuleDefinition.Key ENTRY = new ModuleDefinition.Key("entry", 1);

    @ParameterizedTest @ValueSource(strings = {"startup", "broadcast", "key", "watch", "definition"})
    void hatAndStatementsShareSpineWhileRealLoopKeepsItsCavity(String mode) {
        var draft = draft(mode, true);
        var canvas = new FlowBlockCanvas(draft, () -> {});
        canvas.bounds(0, 0, 440, 340);
        var hat = block(canvas, mode.equals("watch") ? "observe" : "head");
        var emptyCanvas = new FlowBlockCanvas(draft(mode, false), () -> {});
        var emptyHat = block(emptyCanvas, hat.step().sourceId());
        var first = block(canvas, "first");
        var loop = block(canvas, "loop");
        var inner = block(canvas, "inside");
        var foot = block(canvas, "end_loop");
        var after = block(canvas, "after");
        assertAll(
            () -> assertEquals(hat.x(), first.x(), "A Flow procedure root is not an outer C block"),
            () -> assertEquals(hat.y() + hat.height(), first.y(), "The first statement fits the hat connector"),
            () -> assertEquals(hat.x(), loop.x(), "Loop is attached to the same top-level stack"),
            () -> assertEquals(1, inner.depth(), "Exactly one real control cavity"),
            () -> assertEquals(loop.x() + FlowBlockCanvas.INDENT, inner.x()),
            () -> assertEquals(loop.x(), foot.x()),
            () -> assertEquals(loop.width(), foot.width()),
            () -> assertEquals(emptyHat.width(), hat.width(), "A wide loop must not stretch the independent hat"),
            () -> assertEquals(hat.x(), after.x(), "The next statement is outside the loop"));
        int last = draft.snapshot().canvas().detached().getFirst().steps().size() - 1;
        var end = joint(canvas, last);
        assertEquals(hat.x(), end.x(), "The invisible EndProcedure is a stack connection, not a C mouth");
        assertEquals(after.y() + after.height(), end.y());
        assertEquals(inner.x(), joint(canvas,last - 2).x(), "A loop's end still accepts statements inside its opening");
    }

    @ParameterizedTest @ValueSource(strings = {"startup", "broadcast", "key", "watch", "definition"})
    void draggingAtTheHatConnectorAttachesToAnEmptyScriptAndCanBeUndone(String mode) {
        var draft = draft(mode, false);
        var before = draft.snapshot();
        var canvas = new FlowBlockCanvas(draft, () -> {});
        canvas.bounds(0, 0, 440, 340);
        int end = before.canvas().detached().getFirst().steps().size() - 1;
        var hat = block(canvas, mode.equals("watch") ? "observe" : "head");
        var socket = joint(canvas, end);
        assertEquals(hat.x(), socket.x());
        assertEquals(hat.y() + hat.height(), socket.y());
        canvas.takePrototype(List.of(wait("attached")), -40, 50);
        assertTrue(canvas.release(hat.x() + 8, hat.y() + hat.height() + 10, 0));
        var stacks = draft.snapshot().canvas().detached();
        assertEquals(1, stacks.size(), "An aligned drop must not become a second parked stack");
        assertEquals("attached", stacks.getFirst().steps().get(end).sourceId());
        assertTrue(draft.snapshot().steps().isEmpty(), "Inserting in a separate script must not touch the main script");
        draft.undo();
        assertEquals(before, draft.snapshot());
    }

    private static FlowDraft draft(String mode, boolean body) {
        var head = switch (mode) {
            case "startup" -> FlowProgram.Procedure.startupHat("head", ENTRY);
            case "broadcast" -> new FlowProgram.Procedure("head", ENTRY, Map.of(), Map.of(), Map.of(), FlowBlockRules.NONE,
                Optional.empty(), Optional.of("batch"));
            case "key" -> FlowProgram.Procedure.keyHat("head", ENTRY, "space");
            default -> new FlowProgram.Procedure("head", ENTRY, Map.of(), Map.of(), Map.of());
        };
        var steps = new ArrayList<FlowProgram.Step>();
        steps.add(head);
        if (mode.equals("watch"))
            steps.add(new FlowProgram.Watch("observe", Optional.empty(), new FlowInput.Literal(new DataValue.BooleanValue(false))));
        if (body)
            steps.addAll(List.of(wait("first"), new FlowProgram.Repeat("loop", integer(2)), wait("inside"), new FlowProgram.EndRepeat("end_loop"), wait("after")));
        steps.add(new FlowProgram.EndProcedure("finish"));
        var layout = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(), List.of(new FlowBlockLayout.Stack(AT, steps)));
        return new FlowDraft(new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()), layout, Map.of(), FlowProgram.Execution.REGISTER_V1));
    }
    private static FlowBlockCanvas.Block block(FlowBlockCanvas canvas, String id) {
        return canvas.blocks().stream().filter(b -> b.step().sourceId().equals(id)).findFirst().orElseThrow();
    }
    private static FlowBlockCanvas.Joint joint(FlowBlockCanvas canvas, int index) {
        var target = new FlowDraft.Socket("head", index);
        return canvas.joints().stream().filter(j -> j.socket().equals(target)).findFirst().orElseThrow();
    }
    private static FlowInput.Literal integer(long value) { return new FlowInput.Literal(new DataValue.IntegerValue(value)); }
    private static FlowProgram.Sleep wait(String source) { return new FlowProgram.Sleep(source, integer(1)); }
}
