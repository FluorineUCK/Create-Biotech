package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import static com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDeviceTest.RECORD;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialNetworkTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class WiredAttachmentSessionTest {
    static final DataValue.RecordValue NEW = new DataValue.RecordValue(Map.of("latest", new DataValue.IntegerValue(57)));

    @Test void aReceiverReopenedWithinAnIntervalKeepsCompleteDataButCannotReceiveAnEarlierPartialFrame() {
        var world = new World();
        var a = world.add(A, Direction.EAST);
        var b = world.add(B, Direction.WEST);
        var network = new SerialNetwork();

        assertEquals("queued", a.send("s0", NEW));
        advance(network, world);
        assertEquals(1, b.status("s0").receive().frames());
        assertEquals("queued", a.send("s0", RECORD));
        assertEquals(SerialNetwork.Phase.TRANSMITTING, network.tick(world, world.devices, DISCOVERY, MEDIUM).getFirst().phase());

        cycle(b, Direction.WEST);
        var activity = network.tick(world, world.devices, DISCOVERY, MEDIUM);
        advance(network, world);
        assertEquals(java.util.Optional.of(NEW), b.poll("s0"));
        assertTrue(b.poll("s0").isEmpty(), "A frame from before the receiving interface reopened must not appear after an interval");
        assertEquals("interrupted", a.status("s0").transmit());
        assertEquals("topology_changed", a.status("s0").failure());
        assertEquals(SerialNetwork.Phase.INTERRUPTED, activity.getFirst().phase());
        assertTrue(activity.getFirst().cables().isEmpty());

        assertEquals("queued", a.send("s0", NEW));
        advance(network, world);
        assertEquals(java.util.Optional.of(NEW), b.poll("s0"));
    }

    @Test void aSenderReopenedWithinAnIntervalCannotFinishAnAbortedFrame() {
        var world = new World();
        var a = world.add(A, Direction.EAST);
        var b = world.add(B, Direction.WEST);
        var network = new SerialNetwork();

        assertEquals("queued", a.send("s0", RECORD));
        assertEquals(SerialNetwork.Phase.TRANSMITTING, network.tick(world, world.devices, DISCOVERY, MEDIUM).getFirst().phase());
        cycle(a, Direction.EAST);
        assertEquals("configuration_changed", a.status("s0").failure());
        advance(network, world);
        assertTrue(b.poll("s0").isEmpty(), "An explicitly interrupted send must not reach the cable receiver later");
        assertEquals("interrupted", a.status("s0").transmit());
        assertEquals("configuration_changed", a.status("s0").failure());
    }

    @Test void aFreshSendAfterAnExplicitReopenIsNotRetiredAlongWithTheOldFrame() {
        var world = new World();
        var a = world.add(A, Direction.EAST);
        var b = world.add(B, Direction.WEST);
        var network = new SerialNetwork();

        assertEquals("queued", a.send("s0", RECORD));
        network.tick(world, world.devices, DISCOVERY, MEDIUM);
        cycle(a, Direction.EAST);
        assertEquals("queued", a.send("s0", NEW));
        advance(network, world);
        assertEquals(java.util.Optional.of(NEW), b.poll("s0"));
        assertTrue(b.poll("s0").isEmpty());
        assertEquals("transmitted", a.status("s0").transmit());
    }

    private static void cycle(SerialDevice device, Direction side) {
        device.configure("s0", side, false);
        device.configure("s0", side, true);
    }

    private static void advance(SerialNetwork network, World world) {
        for (int tick = 0; tick < 100; tick++) network.tick(world, world.devices, DISCOVERY, MEDIUM);
    }
}
