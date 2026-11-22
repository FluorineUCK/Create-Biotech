package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.junit.jupiter.api.*;

class SerialWorldTicksTest {
    @BeforeAll static void setup() throws Exception { bootstrap(); }
    @Test void loadingControllerStateCannotReuseAnEarlierPhysicalTickWitness() throws Exception {
        var controller = fixture().controller(); controller.tick();
        assertTrue(controller.physicalIoTickAvailable());
        var saved = controller.saveWithFullMetadata(RegistryAccess.EMPTY);
        controller.loadWithComponents(saved, RegistryAccess.EMPTY);
        assertFalse(controller.physicalIoTickAvailable(), "Loaded state must wait for an actual controller tick");
    }
    @Test void duplicateAliasesRejectEveryClaimAndDistinctMembersStillShareOneCollisionDomain() throws Exception {
        var f = fixture(); var world = f.world(); var controller = f.controller();
        for (var pos : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(4, 3, 2)))
            if (pos.getX() == 4 || pos.getY() == 0 || pos.getY() == 3 || pos.getZ() == 0 || pos.getZ() == 2)
                world.states.put(pos.immutable(), WorldEnclosureViewTest.casing.defaultBlockState());
        world.states.remove(new BlockPos(2, 1, 1)); world.states.remove(new BlockPos(2, 2, 1));
        var a = add(world, MEMBER); var b = add(world, new BlockPos(3, 1, 1)); var external = add(world, CONTROLLER.west(2));
        world.states.put(CONTROLLER.west(), new DataCableBlock(BlockBehaviour.Properties.of()).defaultBlockState());
        for (var c : List.of(a, b)) {
            c.serialDevice().configureGateway("s0", Optional.of(new SerialGatewayBinding(controller.controllerId(), CONTROLLER, Direction.WEST, "same")));
            c.serialDevice().configure("s0", Direction.WEST, true);
        }
        external.serialDevice().configure("s0", Direction.EAST, true);
        for (var c : List.of(a, b, external)) c.initialize();
        Runnable tick = () -> { world.clock++; controller.tick(); a.tick(); b.tick(); external.tick(); SerialWorldTicks.endTick(new LevelTickEvent.Post(() -> true, world)); };
        var record = new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(37)));
        try {
            tick.run();
            for (var c : List.of(a, b)) assertEquals("gateway_conflict", c.serialDevice().status("s0").connection());
            b.serialDevice().configureGateway("s0", Optional.of(new SerialGatewayBinding(controller.controllerId(), CONTROLLER, Direction.WEST, "other")));
            b.serialDevice().configure("s0", Direction.WEST, true); tick.run();
            a.serialDevice().send("s0", record); b.serialDevice().send("s0", record);
            for (int i = 0; i < 100; i++) tick.run();
            for (var c : List.of(a, b)) assertEquals("collision", c.serialDevice().status("s0").transmit());
            assertTrue(external.serialDevice().poll("s0").isEmpty());
            external.serialDevice().send("s0", record);
            for (int i = 0; i < 100; i++) tick.run();
            assertEquals(record, a.serialDevice().poll("s0").orElseThrow()); assertEquals(record, b.serialDevice().poll("s0").orElseThrow());
        } finally { for (var c : List.of(a, b, external)) c.unload(); SerialWorldTicks.unload(world); }
    }
    @Test void sealedControllerRoutesOnlyExplicitMemberInterfaceAndStopsWhenControllerDoesNotTick() throws Exception {
        var f = fixture(); var world = f.world(); var controller = f.controller();
        var member = add(world, MEMBER); var external = add(world, CONTROLLER.west(2));
        world.states.put(CONTROLLER.west(), new DataCableBlock(BlockBehaviour.Properties.of()).defaultBlockState());
        var binding = new SerialGatewayBinding(controller.controllerId(), CONTROLLER, Direction.WEST, "sensor");
        member.serialDevice().configureGateway("s0", Optional.of(binding));
        member.serialDevice().configure("s0", Direction.WEST, true); external.serialDevice().configure("s0", Direction.EAST, true);
        var record = new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(37)));
        member.initialize(); external.initialize();
        try {
            member.serialDevice().send("s0", record);
            for (int i = 0; i < 100 && external.serialDevice().status("s0").receive().frames() == 0; i++) {
                world.clock++; controller.tick(); member.tick(); external.tick(); SerialWorldTicks.endTick(new LevelTickEvent.Post(() -> true, world));
            }
            assertEquals(record, external.serialDevice().poll("s0").orElseThrow());
            assertTrue(member.document().isEmpty()); assertTrue(member.flowSnapshot().isEmpty());
            member.serialDevice().send("s0", record);
            world.clock++; member.tick(); external.tick(); SerialWorldTicks.endTick(new LevelTickEvent.Post(() -> true, world));
            assertEquals("gateway_unavailable", member.serialDevice().status("s0").connection());
            assertEquals("interrupted", member.serialDevice().status("s0").transmit());
            for (int i = 0; i < 100; i++) {
                world.clock++; controller.tick(); member.tick(); external.tick(); SerialWorldTicks.endTick(new LevelTickEvent.Post(() -> true, world));
            }
            assertTrue(external.serialDevice().poll("s0").isEmpty(), "Controller recovery does not replay member transmissions");
            world.states.remove(BlockPos.ZERO);
            world.clock++; controller.tick(); member.tick(); external.tick(); SerialWorldTicks.endTick(new LevelTickEvent.Post(() -> true, world));
            assertEquals("gateway_unformed", member.serialDevice().status("s0").connection());
            assertEquals(Optional.of(binding), member.serialDevice().view().ports().get("s0").gateway());
        } finally { member.unload(); external.unload(); SerialWorldTicks.unload(world); }
    }
    @Test void normalWorldTicksUseResidentSerialHardwareWithoutInstallingAProgramOrOpeningLegacyMessages() throws Exception {
        var f = fixture(); var world = f.world();
        var aPos = new BlockPos(10, 1, 0); var bPos = new BlockPos(12, 1, 0);
        var a = add(world, aPos); var b = add(world, bPos);
        world.states.put(aPos.east(), new DataCableBlock(BlockBehaviour.Properties.of()).defaultBlockState());
        assertTrue(a.advertisement().isEmpty()); assertTrue(b.advertisement().isEmpty());
        a.serialDevice().configure("s0", Direction.EAST, true); b.serialDevice().configure("s0", Direction.WEST, true);
        var record = new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(37)));
        assertEquals("queued", a.serialDevice().send("s0", record));
        a.initialize(); b.initialize();
        for (int i = 0; i < 100 && b.serialDevice().status("s0").receive().frames() == 0; i++) {
            world.clock++; a.tick(); b.tick(); SerialWorldTicks.endTick(new LevelTickEvent.Post(() -> true, world));
            var before = b.serialDevice().write();
            SerialWorldTicks.endTick(new LevelTickEvent.Post(() -> true, world));
            assertEquals(before, b.serialDevice().write(), "One medium interval per world tick");
        }
        assertEquals("transmitted", a.serialDevice().status("s0").transmit());
        assertTrue(codes(a).contains("serial_transmitted"));
        assertFalse(codes(a).contains("serial_received"), "Emission is not an acknowledgement");
        assertTrue(codes(b).contains("serial_received"));
        assertFalse(world.markers.isEmpty(), "Normal physical ticks must publish actual world feedback");
        assertEquals(record, b.serialDevice().poll("s0").orElseThrow());
        assertTrue(a.snapshot().isEmpty()); assertTrue(b.snapshot().isEmpty());
        assertTrue(a.flowSnapshot().isEmpty()); assertTrue(b.flowSnapshot().isEmpty());
        var saved = new CompoundTag(); a.write(saved, RegistryAccess.EMPTY, false);
        assertTrue(saved.contains(ComputerBehaviour.SERIAL_ROOT));
        a.write(saved, RegistryAccess.EMPTY, true);
        assertFalse(saved.contains(ComputerBehaviour.SERIAL_ROOT), "Normal client sync must not expose hardware payloads or grants");
        a.unload(); b.unload(); SerialWorldTicks.unload(world);
    }
    @Test void cosmeticFailureCannotStopTransmissionAndConcurrentSendersStillCollide() throws Exception {
        var world = fixture().world();
        var aPos = new BlockPos(10, 1, 0); var bPos = aPos.east(2);
        var a = add(world, aPos); var b = add(world, bPos);
        world.states.put(aPos.east(), new DataCableBlock(BlockBehaviour.Properties.of()).defaultBlockState());
        a.serialDevice().configure("s0", Direction.EAST, true); b.serialDevice().configure("s0", Direction.WEST, true);
        a.initialize(); b.initialize(); world.particleFailure = true;
        var record = new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(37)));
        try {
            assertEquals("queued", a.serialDevice().send("s0", record));
            for (int i = 0; i < 100 && b.serialDevice().status("s0").receive().frames() == 0; i++) {
                world.clock++; a.tick(); b.tick(); SerialWorldTicks.endTick(new LevelTickEvent.Post(() -> true, world));
            }
            assertEquals(record, b.serialDevice().poll("s0").orElseThrow());
            assertEquals("transmitted", a.serialDevice().status("s0").transmit());
            world.particleFailure = false;
            assertEquals("queued", a.serialDevice().send("s0", record));
            assertEquals("queued", b.serialDevice().send("s0", record));
            for (int i = 0; i < 100 && !a.serialDevice().status("s0").transmit().equals("collision"); i++) {
                world.clock++; a.tick(); b.tick(); SerialWorldTicks.endTick(new LevelTickEvent.Post(() -> true, world));
            }
            assertEquals("collision", a.serialDevice().status("s0").transmit());
            assertEquals("collision", b.serialDevice().status("s0").transmit());
            assertTrue(a.serialDevice().poll("s0").isEmpty()); assertTrue(b.serialDevice().poll("s0").isEmpty());
            assertTrue(codes(a).contains("serial_collision")); assertTrue(codes(b).contains("serial_collision"));
            assertFalse(world.markers.isEmpty());
        } finally { a.unload(); b.unload(); SerialWorldTicks.unload(world); }
    }
    private static List<String> codes(ComputerBehaviour computer) {
        return ((DataValue.ListValue)computer.recentEvents().field("entries")).values().stream()
            .map(e -> ((DataValue.TextValue)((DataValue.RecordValue)e).field("code")).value()).toList();
    }
    static ComputerBehaviour add(World world, BlockPos pos) throws Exception {
        addComputer(world, pos, null, "legacy");
        var host = (Host)world.entities.get(pos);
        var constructor = ComputerBehaviour.class.getDeclaredConstructor(SmartBlockEntity.class, java.util.function.BooleanSupplier.class, java.util.function.Supplier.class);
        constructor.setAccessible(true);
        var config = com.nobodiiiii.createbiotech.registry.CBConfigs.SERVER.biocomputing;
        var resident = new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:none"), 1);
        host.computer = (ComputerBehaviour)constructor.newInstance(host, (java.util.function.BooleanSupplier)() -> true,
            (java.util.function.Supplier<HardwareSelection>)() -> config.select(resident, false));
        return host.computer;
    }
}
