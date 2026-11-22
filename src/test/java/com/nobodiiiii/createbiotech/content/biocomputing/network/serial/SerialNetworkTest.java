package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDeviceTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SerialNetworkTest {
    @Test void feedbackDescribesThePhysicalIntervalWithoutPayloadsOrPretendingEmissionIsReception() {
        var world = new World(); var a = world.add(A, Direction.EAST); world.add(B, Direction.WEST);
        var network = new SerialNetwork(); assertTrue(network.tick(world, world.devices, DISCOVERY, MEDIUM).isEmpty());
        a.send("s0", RECORD);
        var first = network.tick(world, world.devices, DISCOVERY, MEDIUM);
        assertEquals(1, first.size()); assertEquals(SerialNetwork.Phase.TRANSMITTING, first.getFirst().phase());
        assertEquals(List.of(CABLE), first.getFirst().cables()); assertEquals(2, first.getFirst().terminals().size());
        world.cables.clear(); var broken = network.tick(world, world.devices, DISCOVERY, MEDIUM);
        assertEquals(SerialNetwork.Phase.INTERRUPTED, broken.getFirst().phase());
        assertTrue(broken.getFirst().cables().isEmpty(), "Do not render an old cable after its topology was invalidated");
        assertTrue(network.tick(world, world.devices, DISCOVERY, MEDIUM).isEmpty());
        world.cables.add(CABLE); a.send("s0", RECORD);
        var instant = new SharedSerialChannel.Limits(16, 65536, Long.MAX_VALUE, 0);
        var fastHardware = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware(Map.of("s0",
            new com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware.Port(Long.MAX_VALUE, 65536, 2, 131072)));
        var fast = new SerialDevice(() -> fastHardware, () -> true, () -> CODEC, () -> {});
        fast.configure("s0", Direction.EAST, true); world.devices.put(A, fast); fast.send("s0", RECORD);
        var shortFrame = network.tick(world, world.devices, DISCOVERY, instant);
        assertEquals(SerialNetwork.Phase.TRANSMITTED, shortFrame.getLast().phase(), "One-tick emissions still produce visible feedback");
        assertTrue(network.tick(world, world.devices, DISCOVERY, instant).isEmpty(), "Completion is not announced again each tick");
    }
    static final SharedSerialChannel.Limits MEDIUM = new SharedSerialChannel.Limits(16, 65536, 64, 32);
    static final CommunicationNetworkLimits DISCOVERY = new CommunicationNetworkLimits(1024, 16);
    static final BlockPos A = new BlockPos(0, 0, 0), B = new BlockPos(2, 0, 0), CABLE = new BlockPos(1, 0, 0);
    static class World implements CableDiscovery.World {
        final Set<BlockPos> cables = new HashSet<>(Set.of(CABLE));
        final Map<BlockPos, SerialDevice> devices = new LinkedHashMap<>();
        final Set<BlockPos> unloaded = new HashSet<>();
        public boolean loaded(BlockPos pos) { return !unloaded.contains(pos); }
        public CableDiscovery.Cell cell(BlockPos pos) { return new CableDiscovery.Cell(cables.contains(pos) ? CableDiscovery.Kind.CABLE
            : devices.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null); }
        SerialDevice add(BlockPos pos, Direction side) {
            var device = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
            device.configure("s0", side, true); devices.put(pos, device); return device;
        }
    }
    @Test void actualSegmentUsesOneSharedClockAndDeliversOnlyAfterAllEncodedBits() {
        var world = new World(); var a = world.add(A, Direction.EAST); var b = world.add(B, Direction.WEST);
        var network = new SerialNetwork();
        assertEquals("queued", a.send("s0", RECORD));
        int ticks = (SerialRecordCodec.encode(RECORD, CODEC).length * 8 + 32 + 63) / 64;
        for (int i = 0; i < ticks - 1; i++) {
            network.tick(world, world.devices, DISCOVERY, MEDIUM);
            assertTrue(b.poll("s0").isEmpty(), "No whole record before the medium finishes its bits");
            assertEquals("transmitting", a.status("s0").transmit());
        }
        network.tick(world, world.devices, DISCOVERY, MEDIUM);
        assertEquals("transmitted", a.status("s0").transmit());
        assertEquals(RECORD, b.poll("s0").orElseThrow());
        assertTrue(a.poll("s0").isEmpty());
        network.tick(world, world.devices, DISCOVERY, MEDIUM);
        assertTrue(b.poll("s0").isEmpty(), "No automatic resend or hidden ACK");
    }
    @Test void anUnwiredSecondPortDoesNotInterruptTheFirstOrBridgeSeparateCables() {
        var world = new World();
        var specs = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware(
            Map.of("s0", HARDWARE.ports().get("s0"), "s1", HARDWARE.ports().get("s0")));
        var a = new SerialDevice(() -> specs, () -> true, () -> CODEC, () -> {});
        a.configure("s0", Direction.EAST, true); a.configure("s1", Direction.WEST, true); world.devices.put(A, a);
        var b = world.add(B, Direction.WEST); var network = new SerialNetwork();
        a.send("s0", RECORD);
        for (int i = 0; i < 200 && b.status("s0").receive().frames() == 0; i++) network.tick(world, world.devices, DISCOVERY, MEDIUM);
        assertEquals(RECORD, b.poll("s0").orElseThrow());
        assertEquals("disconnected", a.status("s1").connection());
        assertTrue(a.poll("s1").isEmpty());
    }
    @Test void allOverlappingSendersCollideRegardlessOfDeviceEnumerationAndNoAutomaticRetryOccurs() {
        for (boolean reverse : List.of(false, true)) {
            var world = new World(); var a = world.add(A, Direction.EAST); var b = world.add(B, Direction.WEST);
            if (reverse) { world.devices.clear(); world.devices.put(B, b); world.devices.put(A, a); }
            var network = new SerialNetwork(); a.send("s0", RECORD); b.send("s0", RECORD);
            for (int i = 0; i < 100; i++) network.tick(world, world.devices, DISCOVERY, MEDIUM);
            for (var device : List.of(a, b)) {
                assertEquals("collision", device.status("s0").transmit());
                assertTrue(device.status("s0").receive().faults().contains(SerialReceiveBuffer.Fault.COLLISION));
                assertTrue(device.poll("s0").isEmpty());
            }
        }
    }
    @Test void brokenCableInterruptsOnlyThePartialFrameAndReconnectionDoesNotReplayIt() {
        var world = new World(); var a = world.add(A, Direction.EAST); var b = world.add(B, Direction.WEST);
        var network = new SerialNetwork(); a.send("s0", RECORD);
        for (int i = 0; i < 100; i++) network.tick(world, world.devices, DISCOVERY, MEDIUM);
        a.send("s0", RECORD); network.tick(world, world.devices, DISCOVERY, MEDIUM);
        world.cables.clear(); network.tick(world, world.devices, DISCOVERY, MEDIUM);
        assertEquals("interrupted", a.status("s0").transmit());
        assertEquals("disconnected", a.status("s0").connection());
        world.cables.add(CABLE);
        for (int i = 0; i < 100; i++) network.tick(world, world.devices, DISCOVERY, MEDIUM);
        assertEquals(1, b.status("s0").receive().frames(), "Completed receive survives, partial second frame never becomes data");
        assertEquals("ready", a.status("s0").connection());
        assertEquals("topology_changed", a.status("s0").failure(), "Recovery must not erase the reason the previous operation failed");
        assertEquals(RECORD, b.poll("s0").orElseThrow());
        assertTrue(b.poll("s0").isEmpty());
    }
    @Test void replacingOrClosingTheReceiverCannotReceiveAnEarlierPartialFrame() {
        for (boolean replace : List.of(false, true)) {
            var world = new World(); var a = world.add(A, Direction.EAST); var b = world.add(B, Direction.WEST);
            var network = new SerialNetwork(); a.send("s0", RECORD); network.tick(world, world.devices, DISCOVERY, MEDIUM);
            if (replace) {
                var saved = b.write(); b = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
                b.read(saved); world.devices.put(B, b);
            } else b.configure("s0", Direction.WEST, false);
            for (int i = 0; i < 100; i++) network.tick(world, world.devices, DISCOVERY, MEDIUM);
            assertEquals("interrupted", a.status("s0").transmit()); assertTrue(b.poll("s0").isEmpty());
        }
    }
    @Test void rawCableAdjacencyAcrossSpacesDoesNotConnectOrPoisonTheLocalRoute() {
        var remoteCable = B.east();
        var remotePort = remoteCable.east();
        var localBranch = CABLE.north();
        var world = new World() {
            @Override public java.util.function.Predicate<BlockPos> traversalScope(BlockPos origin) {
                return candidate -> (origin.getX() <= CABLE.getX()) == (candidate.getX() <= CABLE.getX());
            }
        };
        world.cables.addAll(List.of(B, remoteCable));
        var sender = world.add(A, Direction.EAST);
        var local = world.add(localBranch, Direction.SOUTH);
        var remote = world.add(remotePort, Direction.WEST);
        var network = new SerialNetwork();
        assertEquals("queued", sender.send("s0", RECORD));
        for (int i = 0; i < 100; i++) network.tick(world, world.devices, DISCOVERY, MEDIUM);
        assertEquals(RECORD, local.poll("s0").orElseThrow(), "Local communication is not blocked by a branch on a plot seam");
        assertTrue(remote.poll("s0").isEmpty(), "Touching raw plots do not share a serial medium");

        assertEquals("queued", remote.send("s0", RECORD));
        for (int i = 0; i < 100; i++) network.tick(world, world.devices, DISCOVERY, MEDIUM);
        assertTrue(sender.poll("s0").isEmpty(), "Isolation also applies in the reverse direction");
        assertTrue(local.poll("s0").isEmpty());

        world.unloaded.add(B);
        assertEquals(List.of(new CableDiscovery.Connection(localBranch, Direction.SOUTH)),
            CableDiscovery.scanEndpointConnections(world, A, DISCOVERY.maxVisited(), DISCOVERY.maxEndpoints()),
            "Authorization ignores an out-of-space neighbor before probing its chunk");
    }


    @Test void sameDeviceDoesNotBridgeTwoIndependentlyWiredFaces() {
        var world = new World(); var specs = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware(
            Map.of("s0", HARDWARE.ports().get("s0"), "s1", HARDWARE.ports().get("s0")));
        var a = new SerialDevice(() -> specs, () -> true, () -> CODEC, () -> {});
        a.configure("s0", Direction.EAST, true); a.configure("s1", Direction.WEST, true); world.devices.put(A, a);
        var b = world.add(B, Direction.WEST); var c = world.add(new BlockPos(-2, 0, 0), Direction.EAST);
        world.cables.add(new BlockPos(-1, 0, 0)); var network = new SerialNetwork();
        b.send("s0", RECORD);
        for (int i = 0; i < 100; i++) network.tick(world, world.devices, DISCOVERY, MEDIUM);
        assertEquals(RECORD, a.poll("s0").orElseThrow());
        assertTrue(a.poll("s1").isEmpty()); assertTrue(c.poll("s0").isEmpty());
    }
}
