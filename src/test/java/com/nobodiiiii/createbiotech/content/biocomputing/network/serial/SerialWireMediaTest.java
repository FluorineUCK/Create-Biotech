package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDeviceTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialNetworkTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SerialWireMediaTest {
    private static final SerialWireMedium BASIC = SerialWireMedium.shared(MEDIUM);
    private static final SerialWireMedium FAST = new SerialWireMedium(SerialWireMedium.Material.FAST, limits(512, 65536));
    private static final SerialWireMedium DUPLEX = SerialWireMedium.fullDuplex(limits(512, 65536));
    private static final DataValue.RecordValue OTHER = new DataValue.RecordValue(Map.of("reply", new DataValue.IntegerValue(11)));

    @Test void identicalFramesOccupyEachPhysicalSegmentAtItsOwnRateAndStillRespectTheSlowerSender() {
        var bench = new Bench();
        var left = bench.pair(0, BASIC, port(1024, 4096), port(1024, 4096));
        var fast = bench.pair(8, FAST, port(1024, 4096), port(1024, 4096));
        var slowPort = bench.pair(16, FAST, port(32, 4096), port(1024, 4096));
        for (var pair : List.of(left, fast, slowPort)) assertEquals("queued", pair.a.send("s0", RECORD));

        int bits = bits(RECORD);
        for (int tick = 1; tick <= duration(bits, 32); tick++) {
            bench.tick();
            assertEmissionAt(left, RECORD, tick, duration(bits, 64));
            assertEmissionAt(fast, RECORD, tick, duration(bits, 512));
            assertEmissionAt(slowPort, RECORD, tick, duration(bits, 32));
        }
    }

    @Test void fasterMediaDoesNotIncreaseEitherPortsFrameSizeOrReceiveQueue() {
        var bench = new Bench();
        int tooSmall = SerialRecordCodec.encode(RECORD, CODEC).length - 1;
        var senderLimited = bench.pair(0, FAST, port(1024, tooSmall), port(1024, 4096));
        var receiverLimited = bench.pair(8, FAST, port(1024, 4096), port(1024, tooSmall));
        var mediumLimited = bench.pair(16, SerialWireMedium.shared(limits(512, tooSmall)), port(1024, 4096), port(1024, 4096));
        var buffered = bench.pair(24, FAST, port(1024, 4096), new SerialHardware.Port(1024, 4096, 1, 4096));
        assertEquals("payload_too_large", senderLimited.a.send("s0", RECORD));
        assertEquals("queued", receiverLimited.a.send("s0", RECORD));
        assertEquals("queued", mediumLimited.a.send("s0", RECORD));
        assertEquals("queued", buffered.a.send("s0", RECORD));
        bench.advance(20);
        assertEquals("transmitted", receiverLimited.a.status("s0").transmit());
        assertEquals(Set.of(SerialReceiveBuffer.Fault.FRAME_TOO_LARGE), receiverLimited.b.status("s0").receive().faults());
        assertTrue(receiverLimited.b.poll("s0").isEmpty());
        assertEquals("interrupted", mediumLimited.a.status("s0").transmit());
        assertEquals("payload_too_large", mediumLimited.a.status("s0").failure());
        assertTrue(mediumLimited.b.poll("s0").isEmpty());
        assertEquals("queued", buffered.a.send("s0", OTHER));
        bench.advance(20);
        assertEquals(Set.of(SerialReceiveBuffer.Fault.BUFFER_FULL), buffered.b.status("s0").receive().faults());
        assertEquals(RECORD, buffered.b.poll("s0").orElseThrow(), "Full buffers neither overwrite nor enqueue a partial frame");
        assertTrue(buffered.b.poll("s0").isEmpty());
    }

    @Test void simultaneousOppositeEmissionsUseTwoIndependentDirectionsWithDifferentReceiveCeilings() {
        var bench = new Bench();
        var a = duplexPort(512, 128);
        var b = duplexPort(512, 32);
        var shared = bench.pair(0, BASIC, a, b);
        var duplex = bench.pair(8, DUPLEX, a, b);
        for (var pair : List.of(shared, duplex)) {
            assertEquals("queued", pair.a.send("s0", RECORD));
            assertEquals("queued", pair.b.send("s0", OTHER));
        }
        int ab = duration(bits(RECORD), 32), ba = duration(bits(OTHER), 128);
        assertTrue(ba < ab);
        for (int tick = 1; tick <= ab; tick++) {
            bench.tick();
            assertEmissionAt(duplex, RECORD, tick, ab);
            assertEmissionAt(new Pair(duplex.b, duplex.a), OTHER, tick, ba);
        }
        for (var port : List.of(shared.a, shared.b)) {
            assertEquals("collision", port.status("s0").transmit());
            assertTrue(port.poll("s0").isEmpty());
        }
        assertTrue(duplex.a.status("s0").receive().faults().isEmpty());
        assertTrue(duplex.b.status("s0").receive().faults().isEmpty());
    }

    @Test void incompatiblePortsABranchOrMixedCablesDoNotSilentlyTurnIntoWorkingLegacyLines() {
        var bench = new Bench();
        var duplex = duplexPort(512, 512);
        var incompatible = bench.pair(0, DUPLEX, duplex, port(1024, 4096));
        var branch = bench.pair(8, DUPLEX, duplex, duplex);
        bench.media.put(new BlockPos(9, 0, 1), DUPLEX);
        var mixed = bench.pair(16, FAST, duplex, duplex);
        bench.media.put(new BlockPos(17, 0, 1), BASIC);
        for (var pair : List.of(incompatible, branch, mixed)) assertEquals("queued", pair.a.send("s0", RECORD));
        bench.advance(20);
        assertRejected(incompatible, "duplex_hardware");
        assertRejected(branch, "duplex_topology");
        assertRejected(mixed, "incompatible_media");
        // Specific connection failure is valid persistent diagnostic data, not a schema change.
        var restored = device(duplex, Direction.EAST);
        restored.read(incompatible.a.write());
        assertTrue(restored.diagnostic().isEmpty());
        assertEquals("duplex_hardware", restored.status("s0").failure());
    }

    @Test void equalBitratesDoNotBridgeDistinctMaterials() {
        var bench = new Bench();
        var fastAtBasicRate = new SerialWireMedium(SerialWireMedium.Material.FAST, MEDIUM);
        var pair = bench.pair(0, fastAtBasicRate, port(1024, 4096), port(1024, 4096));
        bench.media.put(CABLE.south(), BASIC);
        assertEquals("queued", pair.a.send("s0", RECORD));
        bench.tick();
        assertRejected(pair, "incompatible_media");
    }

    @Test void changingTheMediumInterruptsOnlyUnfinishedDirectionsAndNeverReplaysThem() {
        var bench = new Bench();
        var pair = bench.pair(0, DUPLEX, duplexPort(512, 512), duplexPort(512, 16));
        pair.a.send("s0", RECORD);
        pair.b.send("s0", OTHER);
        int complete = duration(bits(OTHER), 512);
        assertTrue(complete < duration(bits(RECORD), 16));
        bench.advance(complete);
        assertEquals("transmitted", pair.b.status("s0").transmit());
        assertEquals("transmitting", pair.a.status("s0").transmit());
        bench.media.put(CABLE, BASIC);
        bench.tick();
        assertEquals("interrupted", pair.a.status("s0").transmit());
        assertEquals("topology_changed", pair.a.status("s0").failure());
        bench.advance(200);
        assertEquals(OTHER, pair.a.poll("s0").orElseThrow());
        assertTrue(pair.a.poll("s0").isEmpty());
        assertTrue(pair.b.poll("s0").isEmpty());
    }

    @Test void restartPreservesOnlyTheCompleteNativeNbtDirectionAndDoesNotReplayThePartialOne() {
        var portsA = duplexPort(512, 512);
        var portsB = duplexPort(512, 16);
        var bench = new Bench();
        var pair = bench.pair(0, DUPLEX, portsA, portsB);
        var tag = new CompoundTag();
        tag.putByte("enabled", (byte) 1);
        tag.putIntArray("readings", new int[]{3, 5, 8});
        var nativeLimits = new NativeNbtCodec.Limits(CODEC.maxEncodedBytes(), CODEC.maxDecodeHeapBytes(), CODEC.values());
        var nativeValue = NativeNbtCodec.snapshot(tag, nativeLimits);
        var send = pair.b.prepareSend("s0", nativeValue);
        assertEquals(SerialResult.send("queued"), send.result());
        assertTrue(send.publish());
        assertEquals("queued", pair.a.send("s0", RECORD));
        int completedDirection = duration(NativeNbtCodec.encode(nativeValue, nativeLimits).length * 8 + (int) MEDIUM.framingBits(), 512);
        bench.advance(completedDirection);
        assertEquals("transmitted", pair.b.status("s0").transmit());
        assertEquals("transmitting", pair.a.status("s0").transmit());
        var savedA = pair.a.write();
        var savedB = pair.b.write();
        bench.network.unload();

        var reopened = new Bench();
        var restored = reopened.pair(0, DUPLEX, portsA, portsB);
        restored.a.read(savedA);
        restored.b.read(savedB);
        assertEquals("interrupted", restored.a.status("s0").transmit());
        assertEquals("reloaded", restored.a.status("s0").failure());
        reopened.advance(200);
        var received = restored.a.prepareReceive("s0", true);
        assertEquals(SerialResult.receive("received", nativeValue), received.result());
        assertTrue(received.publish());
        assertEquals(0, restored.a.status("s0").receive().frames());
        assertEquals(0, restored.b.status("s0").receive().frames());
    }

    static SharedSerialChannel.Limits limits(long bits, int bytes) {
        return new SharedSerialChannel.Limits(MEDIUM.maxPorts(), bytes, bits, MEDIUM.framingBits());
    }
    private static int bits(DataValue.RecordValue value) { return SerialRecordCodec.encode(value, CODEC).length * 8 + (int) MEDIUM.framingBits(); }
    private static int duration(int bits, int rate) { return (bits + rate - 1) / rate; }
    private static SerialHardware.Port port(int bitRate, int frameBytes) { return new SerialHardware.Port(bitRate, frameBytes, 2, 2L * frameBytes); }
    private static SerialHardware.Port duplexPort(int tx, int rx) {
        return new SerialHardware.Port(1024, 4096, 2, 8192, Optional.of(new SerialHardware.IndependentDuplex(tx, rx)));
    }
    private static SerialDevice device(SerialHardware.Port port, Direction side) {
        var device = new SerialDevice(() -> new SerialHardware(Map.of("s0", port)), () -> true, () -> CODEC, () -> {});
        device.configure("s0", side, true);
        return device;
    }
    private static void assertEmissionAt(Pair pair, DataValue value, int tick, int expected) {
        assertEquals(tick < expected ? "transmitting" : "transmitted", pair.a.status("s0").transmit());
        assertEquals(tick == expected ? Optional.of(value) : Optional.empty(), pair.b.poll("s0"));
    }
    private static void assertRejected(Pair pair, String reason) {
        assertEquals(reason, pair.a.status("s0").connection());
        assertEquals(reason, pair.b.status("s0").connection());
        assertEquals(reason, pair.a.status("s0").failure());
        assertTrue(pair.b.poll("s0").isEmpty());
    }
    private record Pair(SerialDevice a, SerialDevice b) {}

    private static final class Bench implements CableDiscovery.World {
        final Map<BlockPos, SerialWireMedium> media = new HashMap<>();
        final Map<BlockPos, SerialDevice> devices = new LinkedHashMap<>();
        final SerialNetwork network = new SerialNetwork();
        Pair pair(int x, SerialWireMedium medium, SerialHardware.Port left, SerialHardware.Port right) {
            var a = device(left, Direction.EAST);
            var b = device(right, Direction.WEST);
            devices.put(new BlockPos(x, 0, 0), a);
            devices.put(new BlockPos(x + 2, 0, 0), b);
            media.put(new BlockPos(x + 1, 0, 0), medium);
            return new Pair(a, b);
        }
        public boolean loaded(BlockPos pos) { return true; }
        public CableDiscovery.Cell cell(BlockPos pos) {
            return new CableDiscovery.Cell(media.containsKey(pos) ? CableDiscovery.Kind.CABLE
                : devices.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
        }
        void tick() {
            network.tickWired(this, devices, DISCOVERY, MEDIUM, media::get, (position, port) ->
                new CableDiscovery.Connection(position, port.side()));
        }
        void advance(int ticks) { for (int i = 0; i < ticks; i++) tick(); }
    }
}
