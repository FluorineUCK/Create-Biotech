package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Private ISA activation frames, never legacy Flow locals or another Computer's RAM.
 * excludedWork separates this body's paid instructions from completed child activations. */
public record RegisterCalls(long excludedWork, List<Frame> frames) {
    public static final RegisterCalls EMPTY = new RegisterCalls(0, List.of());
    public record Frame(int pc, int source, Map<Integer, DataValue> registers, long excludedWork, long enteredWork) {
        public Frame {
            if (pc < 0 || source < 0 || excludedWork < 0 || enteredWork <= excludedWork) throw new IllegalArgumentException("Invalid register call frame");
            registers = Collections.unmodifiableMap(new TreeMap<>(registers));
            if (registers.keySet().stream().anyMatch(r -> r < 0)) throw new IllegalArgumentException("Invalid frame register");
            registers.values().forEach(Objects::requireNonNull);
        }
        DataValue value() {
            return new DataValue.RecordValue(Map.of("pc", new DataValue.IntegerValue(pc), "source", new DataValue.IntegerValue(source), "registers", values(registers),
                "excluded", new DataValue.IntegerValue(excludedWork), "entered", new DataValue.IntegerValue(enteredWork)));
        }
    }
    public RegisterCalls {
        if (excludedWork < 0) throw new IllegalArgumentException("Invalid excluded work");
        frames = List.copyOf(frames);
    }
    public DataValue value() {
        return new DataValue.RecordValue(Map.of("excluded", new DataValue.IntegerValue(excludedWork),
            "frames", new DataValue.ListValue(frames.stream().map(Frame::value).toList())));
    }
    private static DataValue values(Map<Integer, DataValue> registers) {
        var result = new LinkedHashMap<String, DataValue>(); registers.forEach((r, v) -> result.put(Integer.toString(r), v));
        return new DataValue.RecordValue(result);
    }
}
