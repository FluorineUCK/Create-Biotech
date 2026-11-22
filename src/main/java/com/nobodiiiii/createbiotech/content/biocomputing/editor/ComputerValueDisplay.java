package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure, lossless debugger value rendering and bounded pagination. */
public final class ComputerValueDisplay {
    public record Page(int index, int count, List<String> lines) {
        public Page { lines = List.copyOf(lines); }
    }
    private ComputerValueDisplay() {}
    public static List<String> lines(DataValue value, int width) {
        if (width < 1) throw new IllegalArgumentException("width must be positive");
        String rendered = render(value);
        var result = new ArrayList<String>();
        for (int offset = 0; offset < rendered.length(); offset += width)
            result.add(rendered.substring(offset, Math.min(rendered.length(), offset + width)));
        if (result.isEmpty()) result.add("");
        return List.copyOf(result);
    }
    public static Page page(List<String> lines, int requested, int pageSize) {
        if (pageSize < 1) throw new IllegalArgumentException("pageSize must be positive");
        int count = Math.max(1, (lines.size() + pageSize - 1) / pageSize);
        int index = Math.max(0, Math.min(requested, count - 1));
        int from = Math.min(lines.size(), index * pageSize);
        return new Page(index, count, lines.subList(from, Math.min(lines.size(), from + pageSize)));
    }
    private static String render(DataValue value) {
        return com.nobodiiiii.createbiotech.content.biocomputing.computation.DiagnosticValueText.render(value,Integer.MAX_VALUE).orElseThrow();
    }
}
