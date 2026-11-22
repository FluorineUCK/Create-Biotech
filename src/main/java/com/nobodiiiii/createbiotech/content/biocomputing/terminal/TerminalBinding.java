package com.nobodiiiii.createbiotech.content.biocomputing.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ValuePath;
import java.util.Objects;
import java.util.Set;

/** Read-only value binding; paths never evaluate computation or read a world. */
public sealed interface TerminalBinding {
    DataValue resolve(DataValue.RecordValue roots);
    record Literal(DataValue value) implements TerminalBinding {
        public Literal { Objects.requireNonNull(value); }
        @Override public DataValue resolve(DataValue.RecordValue roots) { return value; }
    }
    record Path(ValuePath path) implements TerminalBinding {
        public Path {
            Objects.requireNonNull(path);
            if (!Set.of("memory", "output", "row", "tasks").contains(path.input()))
                throw new IllegalArgumentException("Terminal path root must be memory, output, row or tasks");
        }
        @Override public DataValue resolve(DataValue.RecordValue roots) { return path.resolve(roots); }
    }
}
