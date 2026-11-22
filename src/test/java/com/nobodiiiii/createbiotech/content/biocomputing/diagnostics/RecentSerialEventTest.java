package com.nobodiiiii.createbiotech.content.biocomputing.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class RecentSerialEventTest {
    @Test void firstObservationOfSavedCompletionDoesNotInventANewTransmissionOrReception() {
        var log = new RecentEventLog(() -> 40, () -> 8);
        log.observeSerial(view("transmitted", "ready", 1, Set.of()));
        assertFalse(codes(log).contains("serial_transmitted"));
        assertFalse(codes(log).contains("serial_received"));
        log.observeSerial(view("queued", "ready", 1, Set.of()));
        log.observeSerial(view("transmitted", "ready", 2, Set.of()));
        assertTrue(codes(log).contains("serial_transmitted"));
        assertTrue(codes(log).contains("serial_received"));
    }
    @Test void serialTransitionsAreBoundedLocalMetadataAndNeverManufactureAReceipt() {
        var log = new RecentEventLog(() -> 40, () -> 8);
        var ready = view("idle", "ready", 0, Set.of()); log.observeSerial(ready);
        var initial = log.value(); log.observeSerial(ready); assertEquals(initial, log.value());
        log.observeSerial(view("queued", "ready", 0, Set.of()));
        log.observeSerial(view("transmitting", "ready", 0, Set.of()));
        log.observeSerial(view("transmitted", "ready", 0, Set.of()));
        assertTrue(codes(log).contains("serial_transmitted")); assertFalse(codes(log).contains("serial_received"));
        var complete = log.value(); log.observeSerial(view("transmitted", "ready", 0, Set.of())); assertEquals(complete, log.value());
        log.observeSerial(view("idle", "ready", 1, Set.of())); assertTrue(codes(log).contains("serial_received"));
        log.observeSerial(view("idle", "ready", 1, Set.of(SerialReceiveBuffer.Fault.BUFFER_FULL)));
        assertTrue(codes(log).contains("serial_rx_buffer_full"));
        log.observeSerial(view("idle", "disconnected", 1, Set.of())); log.observeSerial(ready);
        assertEquals("serial_restored", codes(log).getLast());
        for (int i = 0; i < 100; i++) { log.observeSerial(view("collision", "ready", 0, Set.of())); log.observeSerial(ready); }
        assertEquals(8, codes(log).size()); assertTrue(((DataValue.IntegerValue)log.value().field("omitted")).value() > 0);
        log.observeSerial(new SerialDevice.View(Optional.empty(), Map.of())); var before = log.value();
        log.observeSerial(ready); assertFalse(codes(log).getLast().equals("serial_restored"), "Removed ports are not stale recovery witnesses");
        assertNotEquals(before, log.value());
    }
    static SerialDevice.View view(String tx, String connection, int frames, Set<SerialReceiveBuffer.Fault> faults) {
        return new SerialDevice.View(Optional.empty(), Map.of("s0", new SerialDevice.PortView(Optional.of(Direction.EAST), true,
            new SerialDevice.Status(tx, connection, "", new SerialReceiveBuffer.Status(frames, frames * 30L, faults)))));
    }
    static List<String> codes(RecentEventLog log) {
        return ((DataValue.ListValue)log.value().field("entries")).values().stream()
            .map(e -> ((DataValue.TextValue)((DataValue.RecordValue)e).field("code")).value()).toList();
    }
}
