package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Owned continuous outputs. An absent pin is in input mode; output zero is still output mode. */
public record SimpleIoState(Map<String, Integer> outputs) {
    /** Redstone-compatible electrical domain, not a configurable throughput/balance parameter. */
    public static final int MIN = 0, MAX = 15;
    public static final SimpleIoState EMPTY = new SimpleIoState(Map.of());
    public SimpleIoState {
        outputs = Map.copyOf(outputs);
        outputs.forEach((pin, level) -> {
            FlowProgram.name(pin);
            if (level < MIN || level > MAX) throw new IllegalArgumentException("Invalid saved Simple I/O level");
        });
    }
    public SimpleIoState write(String pin, long value) {
        var next = new LinkedHashMap<>(outputs);
        next.put(pin, (int)Math.clamp(value, MIN, MAX));
        return new SimpleIoState(next);
    }
    public SimpleIoState input(String pin) {
        var next = new LinkedHashMap<>(outputs); next.remove(pin); return new SimpleIoState(next);
    }
    public DataValue.RecordValue value() {
        var fields = new LinkedHashMap<String, DataValue>();
        outputs.forEach((pin, value) -> fields.put(pin, new DataValue.IntegerValue(value)));
        return new DataValue.RecordValue(fields);
    }
}
