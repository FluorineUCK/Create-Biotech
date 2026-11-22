package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ScalarOperation;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockPalette.Category;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authoring shortcuts and diagrams only; the same expression slots remain editable. */
final class ScratchEdgeEditor {
    private ScratchEdgeEditor() {}

    record Addition(InlineGraphEdits.Edit edit, String edge) {}

    static Addition add(ComputationGraph graph, FlowCallBindings bindings, boolean falling,
                                    boolean decimal, ScalarOperation combine) {
        var nodes = new LinkedHashMap<>(graph.nodes());
        String root = graph.outputs().get("value"), edge = root;
        if (!(nodes.get(root) instanceof ComputationGraph.Constant c && c.value().equals(new DataValue.BooleanValue(false)))) {
            String previous = InlineGraphEdits.next(nodes);
            nodes.put(previous, nodes.get(root));
            edge = InlineGraphEdits.next(nodes);
            nodes.put(root, new ComputationGraph.Scalar(combine, Map.of("left", previous, "right", edge)));
        }
        var operation = decimal ? ScalarOperation.SAMPLE_RISING_DECIMAL : ScalarOperation.SAMPLE_RISING_INTEGER;
        nodes.put(edge, new ComputationGraph.Constant(new DataValue.BooleanValue(false))); // reserve the socket
        var edit = InlineGraphEdits.replace(new ComputationGraph(graph.inputs(), nodes, graph.outputs()), bindings, edge,
            new InlineGraphEdits.Operator(falling ? operation.oppositeEdge() : operation));
        return new Addition(edit, edge);
    }

    static void hat(GuiGraphics graphics, Font font, int x, int y, int width, Component label) {
        FlowBlockSkin.block(graphics, x, y, width, FlowBlockCanvas.HAT, Category.EVENTS, FlowBlockSkin.Shape.HAT, false, false);
        graphics.drawString(font, font.plainSubstrByWidth(label.getString(), width - 16),
            x + 8, y + 9, FlowBlockSkin.INK, false);
    }

    static void waveform(GuiGraphics g, int x, int y, int width, boolean falling, boolean enabled) {
        int left = x + 6, right = x + width - 6, edge = (left + right) / 2;
        int high = y + 2, low = y + 13, mid = (high + low) / 2;
        int color = enabled ? 0xffffce58 : 0xffaaaaaa;
        for (int i = left; i < right; i += 5) g.fill(i, mid, Math.min(i + 3, right), mid + 1, 0xff5b6566);
        int before = falling ? high : low, after = falling ? low : high;
        g.fill(left, before, edge, before + 2, color);
        g.fill(edge - 1, high, edge + 1, low + 2, color);
        g.fill(edge, after, right, after + 2, color);
        for (int i = 0; i < 4; i++) {
            int arrow = falling ? low - i : high + i;
            g.fill(edge - i, arrow, edge + i + 1, arrow + 1, color);
        }
    }
}
