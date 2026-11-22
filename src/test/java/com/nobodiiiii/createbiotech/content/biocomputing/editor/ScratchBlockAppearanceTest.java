package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockSkin.Shape;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScratchBlockAppearanceTest {
    @Test void reporterShapeDependsOnTheResultNotTheFirstArgument() {
        assertEquals(Shape.BOOLEAN, FlowBlockSkin.shape(new InlineGraphEdits.Operator(ScalarOperation.LESS_INTEGER)));
        assertEquals(Shape.BOOLEAN, FlowBlockSkin.shape(new InlineGraphEdits.TextOperator(TextOperation.CONTAINS)));
        assertEquals(Shape.BOOLEAN, FlowBlockSkin.shape(new InlineGraphEdits.ValueEquality()));
        assertEquals(Shape.REPORTER, FlowBlockSkin.shape(new InlineGraphEdits.Operator(ScalarOperation.ADD_INTEGER)));
        assertEquals(Shape.REPORTER, FlowBlockSkin.shape(new InlineGraphEdits.Operator(ScalarOperation.SELECT)));
    }

    @Test void theBindingNotTheGraphInputSpellingDeterminesParameterColor() {
        var graph = new ComputationGraph(Map.of("in", DataType.Scalar.BOOLEAN), Map.of("a", new ComputationGraph.Input("in")),
            Map.of("value", "a"));
        var bound = InlineGraphView.output(graph, "value", String::length, Map.of(), Map.of("in","flag"), Set.of("flag"));
        assertTrue(bound.bool());
        assertEquals(FlowBlockPalette.Category.CUSTOM, bound.category());
        var local = InlineGraphView.output(graph, "value", String::length, Map.of(), Map.of("in","other"), Set.of("in"));
        assertEquals(FlowBlockPalette.Category.VARIABLES, local.category());
    }

    @Test void anEmptyIfElseHasTwoVisibleBodiesWithDropTargetsInsideThem() {
        var draft = new FlowDraft(new FlowDocumentBody(Map.of(), List.of(
            new FlowProgram.If("if", new FlowInput.Literal(new DataValue.BooleanValue(true))),
            new FlowProgram.Otherwise("else"), new FlowProgram.EndIf("end")), new DataValue.RecordValue(Map.of())));
        var canvas = new FlowBlockCanvas(draft, () -> {});
        var rows = canvas.blocks();
        var joints = canvas.joints();
        for (int i = 1; i < 3; i++) {
            var previous = rows.get(i - 1);
            var row = rows.get(i);
            assertTrue(row.y() > previous.y() + previous.height());
            assertEquals(previous.y() + previous.height(), joints.get(i).y());
            assertEquals(row.x() + FlowBlockCanvas.INDENT, joints.get(i).x());
        }
    }
}
