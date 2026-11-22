package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.SimpleIoWire;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Shared strict configuration representation for saves and the existing bounded menu protocol. */
public final class SimpleIoWireCodec {
    private SimpleIoWireCodec() {}
    public static DataValue value(Map<String, SimpleIoWire> wires) {
        return named(wires, wire -> obj("left", pin(wire.left()), "right", pin(wire.right()), "enabled", wire.enabled()));
    }
    public static Map<String, SimpleIoWire> read(DataValue value) {
        return readNamed(value, wire -> {
            exact(wire, "left", "right", "enabled");
            return new SimpleIoWire(readPin(at(wire, "left")), readPin(at(wire, "right")), bool(at(wire, "enabled")));
        });
    }
    private static DataValue pin(SimpleIoWire.Pin pin) { return obj("member", pin.member(), "endpoint", pin.endpoint(), "pin", pin.pin()); }
    private static SimpleIoWire.Pin readPin(DataValue value) {
        exact(value, "member", "endpoint", "pin");
        return new SimpleIoWire.Pin(string(at(value, "member")), string(at(value, "endpoint")), string(at(value, "pin")));
    }
    private static void exact(DataValue value, String... keys) {
        if (!fields(value).keySet().equals(Set.of(keys))) throw ArchiveException.invalid("Unexpected Simple I/O wire fields");
    }
}
