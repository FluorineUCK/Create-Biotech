package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import java.util.*;

/** Physical interface names supplied by hardware, never inferred from the installed program. */
public record HardwarePins(List<String> xbus, List<String> simpleIo) {
    /** Encoding bounds, not a default hardware capacity. */
    public static final int MAX_PINS = 64, MAX_NAME = 64;
    public static final HardwarePins DEFAULT = new HardwarePins(List.of("x0", "x1"), List.of("p0", "p1"));
    /** Existing callers declaring only XBus hardware do not implicitly acquire signal pins. */
    public HardwarePins(List<String> xbus) { this(xbus, List.of()); }
    public HardwarePins {
        xbus = validated(xbus); simpleIo = validated(simpleIo);
        if (!Collections.disjoint(xbus, simpleIo)) throw new IllegalArgumentException("XBus and Simple I/O pins overlap");
    }
    static List<String> validated(List<String> names) {
        names = List.copyOf(names);
        if (names.size() > MAX_PINS || new HashSet<>(names).size() != names.size())
            throw new IllegalArgumentException("Too many or duplicate hardware pins");
        for (String name : names)
            if (name.isBlank() || name.length() > MAX_NAME || !name.equals(name.trim())
                || name.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid hardware pin name");
        return names;
    }
    public boolean hasXBus(String name) { return name != null && xbus.contains(name); }
    public boolean hasSimpleIo(String name) { return name != null && simpleIo.contains(name); }
    /** An explicitly unwired device has no other drivers; its own output is never its input. */
    public Map<String, Integer> unconnectedSignals() {
        var values = new LinkedHashMap<String, Integer>(); simpleIo.forEach(pin -> values.put(pin, 0));
        return Map.copyOf(values);
    }
}
