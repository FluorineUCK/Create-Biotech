package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDeviceTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialNetworkTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SerialGatewayTest {
    @Test void gatewayRebindKeepsCompleteFramesAndIdentityButRejectsInvalidSavesAndPreparedWork() {
        var member = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {});
        member.configure("s0", Direction.EAST, true);
        var identity = member.attachments().getFirst().identity();
        member.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(RECORD, CODEC));
        var face=member.beginReceive("s0").face();var head=member.beginReceive("s0").head().identity();
        var prepared = member.prepareReceive("s0");
        var binding = new SerialGatewayBinding(UUID.randomUUID(), A, Direction.WEST, "sensor");
        member.configureGateway("s0", Optional.of(binding)); assertFalse(prepared.publish());
        assertEquals(1, member.status("s0").receive().frames());
        assertThrows(IllegalArgumentException.class, () -> member.configure("s0", Direction.EAST, true));
        member.configure("s0", Direction.WEST, true);
        assertEquals(identity, member.attachments().getFirst().identity());
        for (String key : List.of("Alias", "Side", "Controller", "Position", "extra")) {
            var invalid = (net.minecraft.nbt.CompoundTag)member.write();
            invalid.getCompound("Ports").getCompound("s0").getCompound("Gateway").putInt(key, 5);
            var restored = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {}); restored.read(invalid);
            assertTrue(restored.attachments().isEmpty()); assertTrue(restored.diagnostic().isPresent());
            assertThrows(IllegalArgumentException.class, () -> restored.configureGateway("s0", Optional.empty()));
            assertEquals(invalid, restored.write());
        }
        var downgraded = (net.minecraft.nbt.CompoundTag)member.write(); downgraded.putInt("Version", 1);
        var restored = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {}); restored.read(downgraded);
        assertTrue(restored.attachments().isEmpty()); assertEquals(downgraded, restored.write(), "New routing cannot masquerade as a legacy schema");
        member.configureGateway("s0", Optional.empty());
        var unbound=member.write();var reloaded=new SerialDevice(()->HARDWARE,()->true,()->CODEC,()->{});reloaded.read(unbound);
        assertEquals(identity,reloaded.beginReceive("s0").device());assertEquals(face,reloaded.beginReceive("s0").face());assertEquals(head,reloaded.beginReceive("s0").head().identity());
        assertEquals(Optional.empty(),reloaded.view().ports().get("s0").gateway());assertFalse(reloaded.view().ports().get("s0").enabled());
        assertEquals(RECORD, member.poll("s0").orElseThrow());
        assertEquals(6, ((net.minecraft.nbt.CompoundTag)member.write()).getInt("Version"));
        var drained=new SerialDevice(()->HARDWARE,()->true,()->CODEC,()->{});drained.read(member.write());assertEquals(identity,drained.beginReceive("s0").device());assertEquals(face,drained.beginReceive("s0").face());assertNull(drained.beginReceive("s0").head());assertEquals("closed",drained.beginReceive("s0").error());
    }
    @Test void explicitlyMappedMemberUsesOuterCableAndOwnBufferAndNeverFallsBackWhenGatewayIsUnavailable() {
        var world = new World(); world.add(A, Direction.EAST); // Physical controller terminal, not an automatic participant.
        var external = world.add(B, Direction.WEST);
        var memberPos = new BlockPos(-2, 0, 0);
        var member = world.add(memberPos, Direction.EAST);
        var binding = new SerialGatewayBinding(UUID.randomUUID(), A, Direction.EAST, "sensor");
        member.configureGateway("s0", Optional.of(binding));
        assertFalse(member.view().ports().get("s0").enabled(), "Mapping does not enable an interface");
        member.configure("s0", Direction.EAST, true);
        var devices = new LinkedHashMap<>(world.devices); devices.remove(A);
        var network = new SerialNetwork();
        var ready = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.function.BiFunction<BlockPos, SerialDevice.Attachment, CableDiscovery.Connection> resolve = (pos, port) -> {
            if (port.gateway().isEmpty()) return new CableDiscovery.Connection(pos, port.side());
            if (!ready.get()) throw new CableDiscovery.DiscoveryException("gateway_unavailable");
            return new CableDiscovery.Connection(binding.position(), binding.side());
        };
        external.send("s0", RECORD);
        for (int i = 0; i < 100; i++) network.tick(world, devices, DISCOVERY, MEDIUM, resolve);
        assertEquals(1, member.status("s0").receive().frames());
        assertEquals(RECORD, member.poll("s0").orElseThrow());
        assertEquals("queued", member.send("s0", RECORD));
        var feedback = network.tick(world, devices, DISCOVERY, MEDIUM, resolve);
        assertEquals(Set.of(A, B), new HashSet<>(feedback.getLast().terminals().stream().map(CableDiscovery.Connection::position).toList()));
        ready.set(false); network.tick(world, devices, DISCOVERY, MEDIUM, resolve);
        assertEquals("interrupted", member.status("s0").transmit());
        assertEquals("gateway_unavailable", member.status("s0").connection());
        var saved = member.write();
        var restored = new SerialDevice(() -> HARDWARE, () -> true, () -> CODEC, () -> {}); restored.read(saved);
        assertEquals(Optional.of(binding), restored.view().ports().get("s0").gateway());
        devices.put(memberPos, restored); ready.set(true);
        for (int i = 0; i < 100; i++) network.tick(world, devices, DISCOVERY, MEDIUM, resolve);
        assertTrue(external.poll("s0").isEmpty(), "Recovery never retransmits a partial frame");
        restored.configureGateway("s0", Optional.empty());
        assertFalse(restored.view().ports().get("s0").enabled(), "Removing a gateway does not silently activate a local face");
    }
}
