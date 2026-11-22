package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.ArrayDeque;
import java.util.Map;

/** Shared aggregate accounting; field names, errors and container nodes consume budget too. */
public final class SnapshotLimits {
    private SnapshotLimits() {}

    public static void validate(DataValue value, ReadLimits limits) {
        if (value.storageUnits() > limits.maxValueUnits()) throw exceeded();
        Budget budget = new Budget(limits);
        countValue(value, budget);
    }

    static void countValue(DataValue value, Budget budget) {
        var pending = new ArrayDeque<DataValue>();
        pending.push(value);
        while (!pending.isEmpty()) {
            DataValue next = pending.pop();
            budget.node();
            if (next instanceof DataValue.TextValue text) budget.text(text.value());
            else if (next instanceof DataValue.ErrorValue error) {
                budget.text(error.code());
                budget.text(error.message());
            } else if (next instanceof DataValue.ListValue list) {
                budget.children(list.values().size() + pending.size());
                pending.addAll(list.values());
            } else if (next instanceof DataValue.RecordValue record) {
                enqueue(record.fields(), budget, pending);
            } else if (next instanceof DataValue.MapValue map) {
                enqueue(map.entries(), budget, pending);
            }
        }
    }

    private static void enqueue(Map<String, DataValue> values, Budget budget, ArrayDeque<DataValue> pending) {
        budget.children(values.size() + pending.size());
        for (var entry : values.entrySet()) {
            budget.text(entry.getKey());
            pending.push(entry.getValue());
        }
    }

    static LimitExceeded exceeded() { return new LimitExceeded(); }

    public static final class LimitExceeded extends IllegalArgumentException {
        private LimitExceeded() { super("Snapshot exceeds configured limits"); }
    }

    static final class Budget {
        private final ReadLimits limits;
        private long nodes;
        private long chars;
        Budget(ReadLimits limits) { this.limits = limits; }
        void node() { if (++nodes > limits.maxEntries()) throw exceeded(); }
        void children(int count) {
            if (count < 0) throw new IllegalArgumentException("Negative provider entry count");
            if (count > limits.maxEntries() - nodes) throw exceeded();
        }
        void text(String value) {
            textLength(value.length());
        }
        void textLength(long length) {
            if (length < 0 || length > limits.maxStringChars() - chars) throw exceeded();
            chars += length;
        }
    }
}
