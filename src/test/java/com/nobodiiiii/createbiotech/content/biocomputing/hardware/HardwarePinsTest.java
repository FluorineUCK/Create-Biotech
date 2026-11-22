package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HardwarePinsTest {
    @Test void interfaceKindsAreDisjointImmutableAndCannotBeCrossUsedByAProgram() {
        var signals = new ArrayList<>(List.of("sense", "drive")); var pins = new HardwarePins(List.of("bus"), signals); signals.clear();
        assertEquals(List.of("sense", "drive"), pins.simpleIo()); assertFalse(pins.hasSimpleIo(null));
        assertThrows(UnsupportedOperationException.class, () -> pins.simpleIo().add("new"));
        assertThrows(IllegalArgumentException.class, () -> new HardwarePins(List.of("p0"), List.of("p0")));
        assertThrows(IllegalArgumentException.class, () -> new HardwarePins(List.of(), List.of("p0", "p0")));
        assertEquals(Map.of("sense", 0, "drive", 0), pins.unconnectedSignals());
        assertThrows(UnsupportedOperationException.class, () -> pins.unconnectedSignals().put("sense", 90));
        var signal = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(), List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.SignalRead("read", "bus", "sample")));
        var bus = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(), List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.AwaitReadable("wait", "sense")));
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPins.Unavailable.class,
            () -> com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPins.validate(signal, pins));
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPins.Unavailable.class,
            () -> com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPins.validate(bus, pins));
    }
    @Test void pinCatalogIsAnImmutableBoundedSetWithStableDisplayOrder() {
        var names = new ArrayList<>(List.of("right", "left")); var pins = new HardwarePins(names); names.clear();
        assertEquals(List.of("right", "left"), pins.xbus()); assertFalse(pins.hasXBus(null));
        assertThrows(UnsupportedOperationException.class, () -> pins.xbus().add("invented"));
        for (var bad : List.of(List.of("x0", "x0"), List.of(""), List.of(" x0"), List.of("x0\t"), List.of("x\n0"),
            List.of("x".repeat(HardwarePins.MAX_NAME + 1)), IntStream.range(0, HardwarePins.MAX_PINS + 1).mapToObj(i -> "x" + i).toList()))
            assertThrows(IllegalArgumentException.class, () -> new HardwarePins(bad));
        assertTrue(new HardwarePins(List.of()).xbus().isEmpty());
    }
}
