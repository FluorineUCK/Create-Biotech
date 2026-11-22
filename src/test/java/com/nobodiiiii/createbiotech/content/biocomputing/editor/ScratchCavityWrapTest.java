package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScratchCavityWrapTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void droppingAControlCanWrapTheBranchSuffixOrInsertAnEmptyBlock(boolean wrap) {
        var count = new FlowInput.Literal(new DataValue.IntegerValue(10));
        var flag = new FlowInput.Literal(new DataValue.BooleanValue(true));
        List<FlowProgram.Step> original = List.of(new FlowProgram.Repeat("outer", count),
            new FlowProgram.If("condition", flag), new FlowProgram.Sleep("first", count),
            new FlowProgram.Sleep("second", count), new FlowProgram.Repeat("sibling", count),
            new FlowProgram.Sleep("inside_sibling", count), new FlowProgram.EndRepeat("sibling_end"), new FlowProgram.Otherwise("alternative"),
            new FlowProgram.Sleep("other", count), new FlowProgram.EndIf("condition_end"),
            new FlowProgram.EndRepeat("outer_end"), new FlowProgram.Sleep("outside", count));
        List<FlowProgram.Step> wrapper = List.of(new FlowProgram.Repeat("wrapper", count), new FlowProgram.EndRepeat("wrapper_end"));
        var document = new FlowDocumentBody(Map.of(), original, new DataValue.RecordValue(Map.of()),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1);
        var draft = new FlowDraft(document);
        var canvas = new FlowBlockCanvas(draft, () -> {});
        canvas.bounds(0, 0, 640, 500);
        var target = row(canvas, "second");
        int headerHeight = row(canvas, "outer").height();
        double x = target.x() + 8 - (wrap ? FlowBlockCanvas.INDENT : 0);
        double y = target.y() + 10 - (wrap ? headerHeight : 0);
        canvas.takePrototype(wrapper, 1, 1);
        assertTrue(canvas.drag(x, y, 0, 0, 0));
        assertEquals(document, draft.snapshot(), "Drag previews must not edit the document");
        assertTrue(canvas.release(x, y, 0));

        var updated = draft.snapshot();
        var ids = updated.steps().stream().map(FlowProgram.Step::sourceId).toList();
        assertEquals(wrap ? List.of("outer", "condition", "first", "wrapper", "second", "sibling", "inside_sibling", "sibling_end", "wrapper_end", "alternative",
                          "other", "condition_end", "outer_end", "outside")
                        : List.of("outer", "condition", "first", "wrapper", "wrapper_end", "second", "sibling", "inside_sibling", "sibling_end", "alternative",
                          "other", "condition_end", "outer_end", "outside"), ids);
        assertTrue(updated.canvas().detached().isEmpty());
        assertEquals(document.canvas().main(), updated.canvas().main());
        draft.undo();
        assertEquals(document, draft.snapshot());
        draft.redo();
        assertEquals(updated, draft.snapshot());
    }

    @Test
    void anExistingEmptyControlWrapsRatherThanDuplicatingItself() {
        var n = new FlowInput.Literal(new DataValue.IntegerValue(2));
        var waiting = new FlowProgram.Sleep("wait", n);
        var wrapper = new FlowProgram.Forever("forever");
        var end = new FlowProgram.EndForever("forever_end");
        var layout = FlowBlockLayout.DEFAULT.arranged(FlowBlockLayout.DEFAULT.main(),
            List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(300, 80), List.of(wrapper, end))));
        var document = new FlowDocumentBody(Map.of(), List.of(waiting), new DataValue.RecordValue(Map.of()), layout,
            Map.of(), FlowProgram.Execution.REGISTER_V1);
        var draft = new FlowDraft(document);
        var canvas = new FlowBlockCanvas(draft, () -> {});
        canvas.bounds(0, -20, 640, 500);
        var original = row(canvas, "forever");
        var target = row(canvas, "wait");
        assertTrue(canvas.click(original.x() + 8, original.y() - 20 + 10, 0));
        assertTrue(canvas.release(target.x() - FlowBlockCanvas.INDENT + 8, target.y() - original.height() - 20 + 10, 0));
        var updated = draft.snapshot();
        assertEquals(List.of(wrapper, waiting, end), updated.steps());
        assertTrue(updated.canvas().detached().isEmpty());
        draft.undo();
        assertEquals(document, draft.snapshot());
    }

    private static FlowBlockCanvas.Block row(FlowBlockCanvas canvas, String id) {
        return canvas.blocks().stream().filter(block -> Objects.equals(id, block.step().sourceId())).findFirst().orElseThrow();
    }
}
