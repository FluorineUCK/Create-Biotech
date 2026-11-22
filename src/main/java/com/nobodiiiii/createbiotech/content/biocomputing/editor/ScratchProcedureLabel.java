package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.List;
import java.util.Map;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.GraphLayouts.*;

/** Literal, display-only phrase before a parameter index; the last index is after all parameters. */
record ScratchProcedureLabel(int before, String text) {
    static final int MAX_PARTS = 24, MAX_CHARS = 80;
    ScratchProcedureLabel {
        text = text.strip();
        if (before < 0 || text.isEmpty() || text.length() > MAX_CHARS || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid procedure label");
    }
    record Layout(List<String> inputs, List<ScratchProcedureLabel> labels) {
        List<String> order(java.util.Set<String> supplied) {
            // A stale call still shows its actual sockets; it may not reinterpret old inputs.
            return supplied.equals(java.util.Set.copyOf(inputs)) ? inputs : supplied.stream().sorted().toList();
        }
        List<ScratchProcedureLabel> matching(java.util.Set<String> supplied) {
            return supplied.equals(java.util.Set.copyOf(inputs)) ? labels : List.of();
        }
    }
    static DataValue encode(List<ScratchProcedureLabel> labels) {
        return new DataValue.ListValue(labels.stream().<DataValue>map(label -> new DataValue.RecordValue(Map.of(
            "before", new DataValue.IntegerValue(label.before()), "text", new DataValue.TextValue(label.text())))).toList());
    }
    static List<ScratchProcedureLabel> decode(DataValue value) {
        var parts = list(value);
        if (parts.isEmpty() || parts.size() > MAX_PARTS) throw invalid("Invalid procedure label count");
        return parts.stream().map(part -> {
            var fields = exactRecord(part, java.util.Set.of("before", "text"));
            long position = integer(fields.get("before"));
            if (position < 0 || position > Integer.MAX_VALUE) throw invalid("Invalid procedure label position");
            return new ScratchProcedureLabel((int)position, string(fields.get("text")));
        }).toList();
    }
}
