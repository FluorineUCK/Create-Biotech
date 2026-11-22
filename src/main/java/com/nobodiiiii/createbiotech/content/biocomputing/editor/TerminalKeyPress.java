package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalKey;
import java.util.Map;
import java.util.Set;

/** A bounded key and one-use terminal view; the client does not choose the task. */
record TerminalKeyPress(long view, String key) {
    TerminalKeyPress {
        if (view <= 0) throw new IllegalArgumentException("Invalid terminal key view");
        key = TerminalKey.input(key);
    }
    DataValue.RecordValue encode() {
        return new DataValue.RecordValue(Map.of("view", new DataValue.IntegerValue(view), "key", new DataValue.TextValue(key)));
    }
    static TerminalKeyPress decode(DataValue.RecordValue value) {
        if (!value.fields().keySet().equals(Set.of("view", "key"))
            || !(value.field("view") instanceof DataValue.IntegerValue view)
            || !(value.field("key") instanceof DataValue.TextValue key) || key.value().length() > 5)
            throw new IllegalArgumentException("Invalid terminal key intent");
        return new TerminalKeyPress(view.value(), key.value());
    }
}
