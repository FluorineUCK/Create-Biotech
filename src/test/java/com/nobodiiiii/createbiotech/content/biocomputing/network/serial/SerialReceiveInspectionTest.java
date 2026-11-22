package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDeviceTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SerialReceiveInspectionTest {
    private static SerialReceiveProof.Cursor cursor(SerialDevice.Receive head) {
        return new SerialReceiveProof.Cursor(head.device(), head.face(), head.head().identity(), UUID.randomUUID(),
            "0".repeat(64), 0, 0, 1, head.head().size(), 0, 0, false, "s0", head.limits(), head.configuration(), "");
    }

    @Test void inspectionAuthenticatesWithoutConsumingFramesOrChangingPendingTransmission() {
        var dirty = new AtomicInteger();
        var device = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, dirty::incrementAndGet);
        device.configure("s0", Direction.EAST, true);
        var receiver = device.attachments().getFirst().receiver();
        assertEquals(SerialReceiveBuffer.Admission.ACCEPTED, receiver.offer(SerialRecordCodec.encode(RECORD, CODEC)));
        assertEquals("queued", device.send("s0", RECORD));
        var head = device.beginReceive("s0"); var proof = cursor(head); var certificate = device.signReceiveCursor(proof);
        var before = device.write(); int changes = dirty.get();
        var inspection = device.inspectReceive().orElseThrow();
        assertTrue(inspection.current()); assertTrue(head.sameHead(inspection.beginReceive("s0")));
        assertTrue(inspection.verifyReceiveCursor(proof, certificate));
        assertFalse(inspection.verifyReceiveCursor(proof, "f".repeat(64)));
        assertEquals(certificate, inspection.signReceiveCursor(proof)); assertNull(inspection.receiveFailure(proof));
        assertEquals("missing_port", inspection.beginReceive("s1").error());
        assertEquals(before, device.write()); assertEquals(changes, dirty.get());
        var version = device.receiveObservationVersion(); receiver.poll();
        assertFalse(inspection.current()); assertNotEquals(version, device.receiveObservationVersion());
        assertEquals(head.head().identity(), inspection.beginReceive("s0").head().identity(), "Borrowed head remains immutable");
        assertEquals("stale", device.inspectReceive().orElseThrow().receiveFailure(proof));
    }

    @Test void opaqueAndChangedHardwareAreNotRestoredByInspection() {
        var selected = new AtomicReference<>(HARDWARE); var dirty = new AtomicInteger();
        var device = new SerialDevice(selected::get, () -> true, () -> CODEC, dirty::incrementAndGet);
        device.configure("s0", Direction.EAST, true);
        device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(RECORD, CODEC));
        assertEquals("queued", device.send("s0", RECORD)); var saved = device.write();
        var cold = new SerialDevice(selected::get, () -> true, () -> CODEC, () -> fail("Inspection must not dirty hardware"));
        cold.read(saved); var version = cold.receiveObservationVersion();
        assertTrue(cold.inspectReceive().isEmpty()); assertEquals(version, cold.receiveObservationVersion());
        assertEquals(saved, cold.write(), "Do not normalize a saved queued transmission during inspection");
        cold.beginReceive("s0"); // Explicit ordinary lifecycle, not maintenance.
        assertTrue(cold.inspectReceive().isPresent()); assertNotEquals(version, cold.receiveObservationVersion());
        var inspection = device.inspectReceive().orElseThrow(); int changes = dirty.get();
        selected.set(new SerialHardware(Map.of("s0", new SerialHardware.Port(256, 4096, 2, 8192))));
        assertFalse(inspection.current()); assertTrue(device.inspectReceive().isEmpty());
        assertEquals(saved, device.write()); assertEquals(changes, dirty.get());
    }

    @Test void configurationAvailabilityCodecAndReceiveFaultsInvalidateSnapshotsWithoutPolling() {
        var available = new AtomicBoolean(true); var codec = new AtomicReference<>(CODEC);
        var device = new SerialDevice(() -> HARDWARE, available::get, codec::get, () -> {});
        device.configure("s0", Direction.EAST, true); var receiver = device.attachments().getFirst().receiver();
        receiver.offer(SerialRecordCodec.encode(RECORD, CODEC)); var proof = cursor(device.beginReceive("s0"));
        var initial = device.inspectReceive().orElseThrow(); available.set(false);
        assertFalse(initial.current()); assertEquals("unavailable", device.inspectReceive().orElseThrow().receiveFailure(proof));
        available.set(true); initial = device.inspectReceive().orElseThrow();
        codec.set(new SerialRecordCodec.Limits(CODEC.maxEncodedBytes() / 2, CODEC.maxDecodeHeapBytes(), CODEC.values()));
        assertFalse(initial.current()); assertEquals("hardware_changed", device.inspectReceive().orElseThrow().receiveFailure(proof));
        codec.set(CODEC); initial = device.inspectReceive().orElseThrow();
        device.configure("s0", Direction.WEST, true);
        assertFalse(initial.current()); assertEquals("configuration_changed", device.inspectReceive().orElseThrow().receiveFailure(proof));
        initial = device.inspectReceive().orElseThrow(); receiver.collision(); assertFalse(initial.current());
        assertEquals(1, receiver.status().frames());
    }
}
