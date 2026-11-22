package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.TerminalLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ValueTree;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalProjection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Read-only ASCII projection for character terminals; does not give them native widget semantics. */
final class TerminalCharacterView {
    private static final int WIDTH_MAX = 256, HEIGHT_MAX = 128;
    private static final int UNITS_PER_COLUMN = 7; // Maps TerminalLayout's minimum cell and gutter to a character grid.
    record Frame(List<String> rows, int totalRows, boolean clipped, boolean substituted) {
        Frame { rows = List.copyOf(rows); }
    }

    private TerminalCharacterView() {}

    static Frame render(TerminalUiState.Page page, int width, int height, int rowOffset, Map<String, Integer> pages) {
        if (width < 1 || width > WIDTH_MAX || height < 1 || height > HEIGHT_MAX || rowOffset < 0)
            throw new IllegalArgumentException("invalid_character_viewport");
        var view = TerminalProjection.project(page.layout(), page.roots(), pages, Math.max(1, height - 1),
            TerminalUiState.PAGE_FORMAT.values().maxNodes());
        var layout = TerminalLayout.layout(view, width * UNITS_PER_COLUMN);
        int totalRows = layout.height() / TerminalLayout.ROW_HEIGHT;
        int visible = Math.min(height, Math.max(0, totalRows - rowOffset));
        char[][] rows = new char[visible][width];
        for (var row : rows) Arrays.fill(row, ' ');
        boolean clipped = false, substituted = false;
        for (var cell : layout.cells()) {
            int row = cell.y() / TerminalLayout.ROW_HEIGHT - rowOffset;
            if (row < 0 || row >= visible) continue;
            int start = Math.min(width, (cell.x() + UNITS_PER_COLUMN - 1) / UNITS_PER_COLUMN);
            int end = Math.min(width, (cell.x() + cell.width()) / UNITS_PER_COLUMN);
            String text = label(cell.view());
            int column = start;
            for (int i = 0; i < text.length();) {
                if (column >= end) {
                    clipped = true;
                    if (column > start) rows[row][column - 1] = '~';
                    break;
                }
                int c = text.codePointAt(i);
                i += Character.charCount(c);
                if (c < 0x20 || c > 0x7e) { c = '?'; substituted = true; }
                rows[row][column++] = (char)c;
            }
        }
        var rendered = new ArrayList<String>(visible);
        for (var row : rows) rendered.add(new String(row).stripTrailing());
        return new Frame(rendered, totalRows, clipped, substituted);
    }

    private static String label(TerminalProjection.View view) {
        if (view.problem().isPresent()) return "! " + view.problem().orElseThrow().code();
        if (view.page().isPresent()) {
            var page = view.page().orElseThrow();
            return (page.index() + 1) + "/" + page.count();
        }
        if (view.button().isPresent()) return "[" + view.button().orElseThrow().title() + "]";
        if (view.input().isPresent()) return "> " + view.input().orElseThrow().text();
        if (view.definition() instanceof TerminalDefinition.Item && view.value().orElse(null) instanceof DataValue.RecordValue item)
            return scalar(item.fields().get("item")) + " x" + scalar(item.fields().get("count"));
        return view.value().map(TerminalCharacterView::scalar).orElse("");
    }

    private static String scalar(DataValue value) {
        return switch (value) {
            case DataValue.TextValue v -> v.value();
            case DataValue.IntegerValue v -> Long.toString(v.value());
            case DataValue.DecimalValue v -> Double.toString(v.value());
            case DataValue.BooleanValue v -> Boolean.toString(v.value());
            case DataValue.ErrorValue v -> "! " + v.code();
            case DataValue.NullValue ignored -> "null";
            case null -> "?";
            default -> "<" + ValueTree.kind(value) + ">";
        };
    }
}
