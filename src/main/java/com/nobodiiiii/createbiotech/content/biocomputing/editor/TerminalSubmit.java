package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Set;

/** Closed client intent; the server owns entry, bindings and frozen extra inputs. */
public record TerminalSubmit(long view, String instance, String text) {
    /** Reserved system row; cannot be a layout instance (which starts with a length and colon). */
    public static final String QUESTION = "@question";
    public TerminalSubmit {
        if (view <= 0 || instance == null || instance.isBlank() || instance.length() > TerminalClick.MAX_INSTANCE
            || text == null || text.length() > ProgramEditorWire.MAX_BYTES) throw new IllegalArgumentException("Invalid terminal submit intent");
    }
    public DataValue.RecordValue encode() { return new DataValue.RecordValue(Map.of("view", new DataValue.IntegerValue(view), "instance", new DataValue.TextValue(instance), "text", new DataValue.TextValue(text))); }
    public static TerminalSubmit decode(DataValue.RecordValue value) {
        if (!value.fields().keySet().equals(Set.of("view", "instance", "text")) || !(value.fields().get("view") instanceof DataValue.IntegerValue view)
            || !(value.fields().get("instance") instanceof DataValue.TextValue instance) || !(value.fields().get("text") instanceof DataValue.TextValue text)) throw new IllegalArgumentException("Invalid terminal submit payload");
        return new TerminalSubmit(view.value(), instance.value(), text.value());
    }
}
