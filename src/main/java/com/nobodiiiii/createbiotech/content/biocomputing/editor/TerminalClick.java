package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Set;

/** Closed, bounded client view intent. Entry, enabled state and payload remain server-owned. */
public record TerminalClick(long view, String instance) {
    public static final int MAX_INSTANCE = 4096;
    public TerminalClick {
        if (view <= 0 || instance == null || instance.isBlank() || instance.length() > MAX_INSTANCE)
            throw new IllegalArgumentException("Invalid terminal click intent");
    }
    public DataValue.RecordValue encode() { return new DataValue.RecordValue(java.util.Map.of(
        "view", new DataValue.IntegerValue(view), "instance", new DataValue.TextValue(instance))); }
    public static TerminalClick decode(DataValue.RecordValue value) {
        if (!value.fields().keySet().equals(Set.of("view", "instance"))
            || !(value.fields().get("view") instanceof DataValue.IntegerValue view)
            || !(value.fields().get("instance") instanceof DataValue.TextValue instance))
            throw new IllegalArgumentException("Terminal click requires only integer view and text instance");
        return new TerminalClick(view.value(), instance.value());
    }
}
