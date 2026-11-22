package com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Ordinary immutable local identity; never an object reference or authority over another owner. */
public record TaskHandle(UUID computer, long generation, int slot, long incarnation, UUID flow) {
    public static final DataType.RecordType TYPE = new DataType.RecordType(Map.of(
        "computer", DataType.Scalar.TEXT, "generation", DataType.Scalar.INTEGER,
        "slot", DataType.Scalar.INTEGER, "incarnation", DataType.Scalar.INTEGER, "flow", DataType.Scalar.TEXT));
    public TaskHandle {
        Objects.requireNonNull(computer); Objects.requireNonNull(flow);
        if (generation < 0 || slot <= 0 || incarnation < 0) throw new IllegalArgumentException("Invalid local task identity");
    }
    public DataValue.RecordValue value() {
        return new DataValue.RecordValue(Map.of("computer", new DataValue.TextValue(computer.toString()),
            "generation", new DataValue.IntegerValue(generation), "slot", new DataValue.IntegerValue(slot),
            "incarnation", new DataValue.IntegerValue(incarnation), "flow", new DataValue.TextValue(flow.toString())));
    }
    public static TaskHandle from(DataValue value) {
        if (!TYPE.accepts(value)) throw new IllegalArgumentException("Invalid task handle value");
        var record = (DataValue.RecordValue)value;
        return new TaskHandle(UUID.fromString(((DataValue.TextValue)record.field("computer")).value()),
            ((DataValue.IntegerValue)record.field("generation")).value(), Math.toIntExact(((DataValue.IntegerValue)record.field("slot")).value()),
            ((DataValue.IntegerValue)record.field("incarnation")).value(), UUID.fromString(((DataValue.TextValue)record.field("flow")).value()));
    }
}
