package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** One-use selection in a server-owned frozen task list; clients never submit an execution UUID. */
public record TerminalTaskClaim(long view, int index) {
    public TerminalTaskClaim { if (view <= 0 || index < 0) throw new IllegalArgumentException("Invalid task claim"); }
    public DataValue.RecordValue encode() {
        return new DataValue.RecordValue(Map.of("view", new DataValue.IntegerValue(view), "index", new DataValue.IntegerValue(index)));
    }
    public static TerminalTaskClaim decode(DataValue.RecordValue value) {
        if (!value.fields().keySet().equals(Set.of("view", "index")) || !(value.field("view") instanceof DataValue.IntegerValue view)
                || !(value.field("index") instanceof DataValue.IntegerValue index) || index.value() < 0 || index.value() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Task claim requires only view and row index");
        return new TerminalTaskClaim(view.value(), Math.toIntExact(index.value()));
    }
    UUID resolve(DataValue.RecordValue roots) {
        if (!(roots.fields().get("tasks") instanceof DataValue.ListValue tasks) || index >= tasks.values().size()
                || !(tasks.values().get(index) instanceof DataValue.RecordValue task)
                || !(task.fields().get("handle") instanceof DataValue.TextValue handle)) throw new IllegalArgumentException("Unknown published task row");
        return UUID.fromString(handle.value());
    }
}
