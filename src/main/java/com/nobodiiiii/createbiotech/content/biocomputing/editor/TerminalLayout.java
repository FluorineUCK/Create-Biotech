package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;

/** Iterative responsive row/column/grid placement; long rows wrap instead of creating unreachable controls. */
public final class TerminalLayout {
    public static final int ROW_HEIGHT = 24;
    private static final int MIN_CELL_WIDTH = 80;
    public record Cell(TerminalProjection.View view, int x, int y, int width, int height) {}
    public record Layout(List<Cell> cells, int height) { public Layout { cells = List.copyOf(cells); } }
    private static final class Box {
        final TerminalProjection.View view;
        final int width, columns;
        final List<Box> children = new ArrayList<>();
        int height;
        Box(TerminalProjection.View view, int width) {
            this.view = view; this.width = width;
            int requested = view.definition() instanceof TerminalDefinition.Row ? Math.max(1, view.children().size())
                : view.definition() instanceof TerminalDefinition.Grid grid ? grid.columns() : 1;
            columns = Math.min(requested, Math.max(1, width / MIN_CELL_WIDTH));
        }
        boolean visible() { return view.page().isPresent() || view.problem().isPresent() || view.definition() instanceof TerminalDefinition.Text
            || view.definition() instanceof TerminalDefinition.Item || view.definition() instanceof TerminalDefinition.Button || view.definition() instanceof TerminalDefinition.Input; }
    }
    private record Placement(Box box, int x, int y) {}
    private TerminalLayout() {}
    public static Layout layout(TerminalProjection.View view, int width) {
        if (width <= 0) throw new IllegalArgumentException("Terminal width must be positive");
        var root = new Box(view, width); var order = new ArrayList<Box>(); var pending = new ArrayDeque<Box>(); pending.add(root);
        while (!pending.isEmpty()) {
            var box = pending.removeFirst(); order.add(box);
            int childWidth = Math.max(1, (box.width - (box.columns - 1) * 4) / box.columns);
            for (var child : box.view.children()) { var next = new Box(child, childWidth); box.children.add(next); pending.addLast(next); }
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            var box = order.get(i); box.height = box.visible() ? ROW_HEIGHT : 0;
            for (int first = 0; first < box.children.size(); first += box.columns) {
                int height = 0;
                for (int child = first; child < Math.min(first + box.columns, box.children.size()); child++) height = Math.max(height, box.children.get(child).height);
                box.height += height;
            }
        }
        var cells = new ArrayList<Cell>(); var placements = new ArrayDeque<Placement>(); placements.add(new Placement(root, 0, 0));
        while (!placements.isEmpty()) {
            var position = placements.removeFirst(); var box = position.box(); int y = position.y();
            if (box.visible()) { cells.add(new Cell(box.view, position.x(), y, box.width, ROW_HEIGHT)); y += ROW_HEIGHT; }
            for (int first = 0; first < box.children.size(); first += box.columns) {
                int height = 0, x = position.x();
                for (int child = first; child < Math.min(first + box.columns, box.children.size()); child++) {
                    var next = box.children.get(child); placements.addLast(new Placement(next, x, y)); x += next.width + 4; height = Math.max(height, next.height);
                }
                y += height;
            }
        }
        cells.sort(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::x)); return new Layout(cells, root.height);
    }
}
