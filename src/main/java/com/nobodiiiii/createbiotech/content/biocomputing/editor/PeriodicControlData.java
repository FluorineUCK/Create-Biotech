package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit player configuration, not an automatically refreshed world binding. */
public record PeriodicControlData(long intervalTicks, DataValue.RecordValue inputs) {
    public PeriodicControlData {
        if (intervalTicks <= 0) throw new IllegalArgumentException("Interval must be a positive whole number of ticks");
        Objects.requireNonNull(inputs);
    }
    public DataValue.RecordValue encode() {
        return new DataValue.RecordValue(Map.of("interval_ticks", new DataValue.IntegerValue(intervalTicks), "inputs", inputs));
    }
    public static PeriodicControlData decode(DataValue.RecordValue value) {
        if (!value.fields().keySet().equals(Set.of("interval_ticks", "inputs"))
            || !(value.fields().get("interval_ticks") instanceof DataValue.IntegerValue interval)
            || !(value.fields().get("inputs") instanceof DataValue.RecordValue inputs))
            throw new IllegalArgumentException("Periodic settings require integer interval_ticks and named inputs only");
        return new PeriodicControlData(interval.value(), inputs);
    }
}
