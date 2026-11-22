package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.MEMORY_LIMIT;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.PROGRAM_LIMIT;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.INTEGER;
import static org.junit.jupiter.api.Assertions.*;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

class HardwareProfileConfigTest {
    @Test void storedZombieOptOutAndOldProgramCeilingAreRespected() throws ReflectiveOperationException {
        var fixture = fixture();
        fixture.values.set("biocomputing.hardware.entityProfiles", List.of("minecraft:zombie_villager=disabled"));
        fixture.values.set("biocomputing.programUnits", 256L);
        load(fixture);
        try {
            var zombie = new ResidentHardwareIdentity(ZOMBIE_VILLAGER, id("minecraft:none"), 1);
            assertEquals(HardwareSelection.Status.DISABLED, fixture.config.select(zombie, true).status());
            assertEquals(256L, fixture.config.select(villager("minecraft:armorer", 5), true).limits().hardware().programUnits());
        } finally { fixture.spec.acceptConfig(null); }
    }
    @Test void repeatedHardwareChecksReuseOnlyUnchangedInterfaceDescriptions() throws ReflectiveOperationException {
        var fixture = fixture(); var resident = villager("minecraft:armorer", 3);
        var first = fixture.config.select(resident, false);
        var repeated = fixture.config.select(resident, false);
        // These immutable descriptions are checked many times per instruction round, not player RAM.
        assertSame(first.limits().pins(), repeated.limits().pins());
        assertSame(first.limits().serial(), repeated.limits().serial());
        fixture.values.set("biocomputing.hardware.profiles.program.serialBitsPerTick", List.of(32L, 64L, 96L, 128L, 160L));
        fixture.values.set("biocomputing.hardware.profiles.program.xbusPins", List.of("data"));
        load(fixture);
        try {
            var changed = fixture.config.select(resident, true);
            assertEquals(List.of("data"), changed.limits().pins().xbus());
            assertEquals(96, changed.limits().serial().ports().get("s0").bitsPerTick());
            assertEquals(160, fixture.config.select(villager("minecraft:armorer", 5), true).limits().serial().ports().get("s0").bitsPerTick());
            assertSame(changed.limits().serial(), fixture.config.select(resident, true).limits().serial());
            fixture.values.set("biocomputing.hardware.profiles.program.simpleIoPins", List.of("data")); fixture.spec.afterReload();
            assertEquals(HardwareSelection.Status.INVALID_PINS, fixture.config.select(resident, true).status());
            fixture.values.set("biocomputing.hardware.profiles.program.simpleIoPins", List.of("sense"));
            fixture.values.set("biocomputing.hardware.profiles.program.serialReceiveBytes", List.of(1L, 1L, 1L, 1L, 1L)); fixture.spec.afterReload();
            assertEquals(HardwareSelection.Status.INVALID_SERIAL, fixture.config.select(resident, true).status());
            fixture.values.set("biocomputing.hardware.profiles.program.serialReceiveBytes", List.of(8192L, 8192L, 8192L, 8192L, 8192L));
            fixture.values.set("biocomputing.hardware.profiles.program.serialPorts", List.of("sense")); fixture.spec.afterReload();
            assertEquals(HardwareSelection.Status.INVALID_SERIAL, fixture.config.select(resident, true).status());
            fixture.values.set("biocomputing.hardware.profiles.program.serialPorts", List.of()); fixture.spec.afterReload();
            assertTrue(fixture.config.select(resident, true).limits().serial().ports().isEmpty());
        } finally { fixture.spec.acceptConfig(null); }
        var defaults = fixture.config.select(resident, false);
        assertEquals(first.limits(), defaults.limits());
        assertEquals(List.of("x0", "x1"), first.limits().pins().xbus());
        assertEquals(128, first.limits().serial().ports().get("s0").bitsPerTick());
    }
    @Test void multiplierAppliesOnceToExistingRowsAndReloadsWithoutChangingDeviceSpeed() throws ReflectiveOperationException {
        var f = fixture();
        f.values.remove("biocomputing.executionWorkMultiplier"); // Existing world, before this key existed.
        f.spec.correct(f.values);
        f.values.set("biocomputing.workUnitsPerTick", 3L);
        f.values.set("biocomputing.hardware.profiles.balanced.workUnitsPerTick", List.of(4L, 6L, 8L, 12L, 16L));
        load(f);
        try {
            var accelerated = f.config.select(villager("minecraft:farmer", 1), true).limits();
            assertEquals(24, accelerated.hardware().workPerSlice());
            f.values.set("biocomputing.executionWorkMultiplier", 1); f.spec.afterReload();
            var original = f.config.select(villager("minecraft:farmer", 1), true).limits();
            assertEquals(3, original.hardware().workPerSlice());
            assertEquals(original.serial(), accelerated.serial());
            assertEquals(original.hardware().memoryUnits(), accelerated.hardware().memoryUnits());
            assertEquals(original.hardware().programUnits(), accelerated.hardware().programUnits());
            f.values.set("biocomputing.executionWorkMultiplier", 8);
            f.values.set("biocomputing.workUnitsPerTick", Long.MAX_VALUE);
            f.values.set("biocomputing.hardware.profiles.balanced.workUnitsPerTick", java.util.Collections.nCopies(5, Long.MAX_VALUE));
            f.spec.afterReload();
            assertEquals(Long.MAX_VALUE, f.config.select(villager("minecraft:farmer", 1), true).limits().hardware().workPerSlice());
        } finally { f.spec.acceptConfig(null); }
    }

    @Test void parserCapabilityFollowsOnlyAnExplicitClericBindingAndSurvivesConfigReload() throws ReflectiveOperationException {
        var fixture = fixture();
        assertEquals("parser", fixture.config.select(villager("minecraft:cleric", 5), false).profile());
        assertTrue(fixture.config.select(villager("minecraft:cleric", 5), false).limits().isa1a());
        assertFalse(fixture.config.select(villager("minecraft:librarian", 5), false).limits().isa1a());
        fixture.values.set("biocomputing.hardware.villagerProfiles", List.of("minecraft:cleric=memory"));
        load(fixture);
        assertFalse(fixture.config.select(villager("minecraft:cleric", 5), true).limits().isa1a(), "Do not overwrite old custom binding");
        fixture.values.set("biocomputing.hardware.villagerProfiles", List.of("minecraft:cleric=parser"));
        fixture.values.set("biocomputing.hardware.profiles.parser.programUnits", List.of(50L, 60L, 70L, 80L, 90L));
        load(fixture);
        var parser = fixture.config.select(villager("minecraft:cleric", 5), true);
        assertTrue(parser.limits().isa1a()); assertEquals(90, parser.limits().hardware().programUnits());
        assertInvalid("biocomputing.hardware.villagerProfiles", List.of("minecraft:mason=parser"));
        assertInvalid("biocomputing.hardware.entityProfiles", List.of("minecraft:wandering_trader=parser"));
    }
    @Test void explicitServerSerialPortSurvivesParserDefaultChange() throws ReflectiveOperationException {
        var f = fixture();
        var cleric = villager("minecraft:cleric", 5);
        assertTrue(f.config.select(cleric, false).limits().serial().ports().isEmpty());
        f.values.set("biocomputing.hardware.profiles.parser.serialPorts", List.of("s0"));
        load(f);
        try {
            assertEquals(java.util.Set.of("s0"), f.config.select(cleric, true).limits().serial().ports().keySet());
        } finally { f.spec.acceptConfig(null); }
    }

    @Test void ordinaryComputersHaveBoundedTasksAndLibrarianHasAnIndependentControlProfile() throws ReflectiveOperationException {
        var fixture = fixture();
        var ordinary = fixture.config.select(villager("minecraft:none", 1), false);
        var librarian = fixture.config.select(villager("minecraft:librarian", 1), false);
        var armorer = fixture.config.select(villager("minecraft:armorer", 1), false);
        assertTrue(ordinary.limits().taskSlots() >= 2);
        assertEquals("control", librarian.profile()); assertEquals("program", armorer.profile());
        assertTrue(librarian.limits().taskSlots() > armorer.limits().taskSlots());
        assertEquals(armorer.limits().hardware().workPerSlice(), librarian.limits().hardware().workPerSlice(), "More tasks share the same CPU");
        fixture.values.set("biocomputing.hardware.villagerProfiles", List.of("minecraft:librarian=program"));
        fixture.values.set("biocomputing.hardware.profiles.program.taskSlots", List.of(2L, 3L, 4L, 5L, 6L));
        fixture.values.set("biocomputing.taskSlots", 4);
        load(fixture);
        try {
            var custom = fixture.config.select(villager("minecraft:librarian", 5), true);
            assertEquals("program", custom.profile(), "Existing explicit bindings must not be rewritten");
            assertEquals(4, custom.limits().taskSlots(), "Server ceiling still applies");
        } finally { fixture.spec.acceptConfig(null); }
    }
    @Test void sharedSerialMediumConfigurationIsIndependentOfResidentCpuAndInterfaceCapacity() throws ReflectiveOperationException {
        var fixture = fixture();
        fixture.values.set("biocomputing.serial.mediumBitsPerTick", 24L);
        fixture.values.set("biocomputing.serial.frameBytes", 2048);
        fixture.values.set("biocomputing.serial.framingBits", 80L);
        fixture.values.set("biocomputing.serial.decodeBytes", 123456L);
        load(fixture);
        try {
            assertEquals(24, fixture.config.serialMedium(true).bitsPerStep());
            assertEquals(2048, fixture.config.serialMedium(true).maxFrameBytes());
            assertEquals(80, fixture.config.serialMedium(true).framingBits());
            assertEquals(123456, fixture.config.serialCodec(true).maxDecodeHeapBytes());
            assertEquals(128, fixture.config.select(villager("minecraft:none", 1), true).limits().serial().ports().get("s0").bitsPerTick());
            fixture.values.set("biocomputing.serial.mediumBitsPerTick", 48L); load(fixture);
            assertEquals(48, fixture.config.serialMedium(true).bitsPerStep());
            assertInvalid("biocomputing.serial.mediumBitsPerTick", 0L);
            assertInvalid("biocomputing.serial.framingBits", Long.MAX_VALUE);
        } finally { fixture.spec.acceptConfig(null); }
    }
    @Test void selectedResidentSpecificationsDriveRealChannelTimingAndIndependentBuffers() throws ReflectiveOperationException {
        var fixture = fixture(); String base = "biocomputing.hardware.profiles.program.";
        fixture.values.set(base + "serialBitsPerTick", List.of(8L, 8L, 8L, 8L, 8L));
        fixture.values.set(base + "serialReceiveFrames", List.of(1L, 1L, 1L, 1L, 1L));
        fixture.values.set(base + "serialReceiveBytes", List.of(4096L, 4096L, 4096L, 4096L, 4096L));
        load(fixture);
        try {
            var slow = fixture.config.select(villager("minecraft:armorer", 1), true).limits().serial().ports().get("s0");
            var fast = fixture.config.select(villager("minecraft:none", 1), true).limits().serial().ports().get("s0");
            var a = new SharedSerialChannel.Port(new java.util.UUID(0, 1), "s0");
            var b = new SharedSerialChannel.Port(new java.util.UUID(0, 2), "s0");
            var c = new SharedSerialChannel.Port(new java.util.UUID(0, 3), "s0");
            var receiveB = new SerialReceiveBuffer(slow.receiveLimits());
            var receiveC = new SerialReceiveBuffer(fast.receiveLimits());
            var records = new SerialRecordCodec.Limits(fast.maxFrameBytes(), 100_000, fixture.config.current(true).archive());
            var record = new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(12)));
            byte[] payload = SerialRecordCodec.encode(record, records);
            var limits = new SharedSerialChannel.Limits(3, 4096, 64, 16);
            var channel = new SharedSerialChannel(limits, Map.of(a, fast.transmitter(), b, slow.transmitter(), c, fast.transmitter()), Map.of(b, receiveB, c, receiveC));
            long ticks = Math.ceilDiv(payload.length * 8L + limits.framingBits(), Math.min(fast.bitsPerTick(), limits.bitsPerStep()));
            for (int frame = 0; frame < 2; frame++) {
                assertEquals(SharedSerialChannel.Admission.STARTED, channel.transmit(a, payload));
                for (long tick = 1; tick < ticks; tick++) {
                    assertTrue(channel.advance().isEmpty()); assertEquals(frame, receiveC.status().frames());
                }
                assertEquals(SharedSerialChannel.Outcome.TRANSMITTED, channel.advance().getFirst().outcome());
            }
            assertEquals(1, receiveB.status().frames()); assertEquals(2, receiveC.status().frames());
            assertEquals(java.util.Set.of(SerialReceiveBuffer.Fault.BUFFER_FULL), receiveB.status().faults());
            assertTrue(receiveC.status().faults().isEmpty());
            assertEquals(record, SerialRecordCodec.decode(receiveB.poll().orElseThrow(), records));
            assertEquals(2, receiveC.status().frames(), "Members never share the receive buffer");
            var slowChannel = new SharedSerialChannel(limits, Map.of(b, slow.transmitter()));
            assertEquals(SharedSerialChannel.Admission.STARTED, slowChannel.transmit(b, payload));
            long slowTicks = Math.ceilDiv(payload.length * 8L + limits.framingBits(), slow.bitsPerTick());
            assertTrue(slowTicks > ticks);
            for (long tick = 1; tick < slowTicks; tick++) assertTrue(slowChannel.advance().isEmpty());
            assertEquals(SharedSerialChannel.Outcome.TRANSMITTED, slowChannel.advance().getFirst().outcome());
            assertEquals(SharedSerialChannel.Admission.PAYLOAD_TOO_LARGE, slowChannel.transmit(b, new byte[slow.maxFrameBytes() + 1]));
        } finally { fixture.spec.acceptConfig(null); }
    }
    @Test void serialRowsReloadPerProfileAndLevelWithoutScalingWithCpuOrMailbox() throws ReflectiveOperationException {
        var fixture = fixture();
        String base = "biocomputing.hardware.profiles.program.";
        fixture.values.set(base + "serialPorts", List.of("uplink", "other"));
        fixture.values.set(base + "serialBitsPerTick", List.of(8L, 16L, 24L, 32L, 40L));
        fixture.values.set(base + "serialFrameBytes", List.of(128L, 256L, 384L, 512L, 640L));
        fixture.values.set(base + "serialReceiveFrames", List.of(1L, 2L, 3L, 4L, 5L));
        fixture.values.set(base + "serialReceiveBytes", List.of(256L, 512L, 768L, 1024L, 1280L));
        load(fixture);
        try {
            var selected = fixture.config.select(villager("minecraft:armorer", 4), true);
            var serial = selected.limits().serial();
            assertEquals(List.of("uplink", "other"), new ArrayList<>(serial.ports().keySet()));
            assertEquals(new SerialHardware.Port(32, 512, 4, 1024), serial.ports().get("uplink"));
            assertEquals(new SerialHardware.Port(8, 128, 1, 256), fixture.config.select(villager("minecraft:armorer", 1), true).limits().serial().ports().get("uplink"));
            var unchanged = fixture.config.select(villager("minecraft:farmer", 4), true);
            assertEquals(List.of("s0"), new ArrayList<>(unchanged.limits().serial().ports().keySet()));
            fixture.values.set(base + "workUnitsPerTick", List.of(32L, 32L, 32L, 32L, 32L));
            fixture.values.set("biocomputing.communication.inboxMessages", 128);
            fixture.spec.afterReload();
            assertEquals(serial, fixture.config.select(villager("minecraft:armorer", 4), true).limits().serial());
            fixture.values.set(base + "serialBitsPerTick", List.of(1L, 2L, 3L, 4L, 5L)); fixture.spec.afterReload();
            assertEquals(4, fixture.config.select(villager("minecraft:armorer", 4), true).limits().serial().ports().get("uplink").bitsPerTick());
            assertEquals(32, serial.ports().get("uplink").bitsPerTick(), "Captured hardware facts are immutable");
            assertEquals(unchanged.limits().serial(), fixture.config.select(villager("minecraft:farmer", 4), true).limits().serial());
        } finally { fixture.spec.acceptConfig(null); }
    }

    @Test void invalidSerialCapacityAndOverlappingNamesHaveExplicitProfileScopedDiagnosis() throws ReflectiveOperationException {
        for (var change : Map.<String, Object>of("serialPorts", List.of("x0"), "serialReceiveBytes", List.of(1L, 1L, 1L, 1L, 1L)).entrySet()) {
            var fixture = fixture(); String base = "biocomputing.hardware.profiles.program.";
            fixture.values.set(base + change.getKey(), change.getValue()); load(fixture);
            try {
                var invalid = fixture.config.select(villager("minecraft:armorer", 1), true);
                assertEquals(HardwareSelection.Status.INVALID_SERIAL, invalid.status());
                assertTrue(invalid.limits().serial().ports().isEmpty(), "No fallback serial capabilities");
                assertTrue(fixture.config.select(villager("minecraft:farmer", 1), true).ready());
                fixture.values.set(base + "serialPorts", List.of("p0")); fixture.spec.afterReload();
                assertEquals(HardwareSelection.Status.INVALID_SERIAL, fixture.config.select(villager("minecraft:armorer", 1), true).status());
                fixture.values.set(base + "serialPorts", List.of("s0"));
                fixture.values.set(base + "serialReceiveBytes", List.of(8192L, 8192L, 8192L, 8192L, 8192L)); fixture.spec.afterReload();
                assertTrue(fixture.config.select(villager("minecraft:armorer", 1), true).ready());
            } finally { fixture.spec.acceptConfig(null); }
        }
        for (String key : List.of("serialBitsPerTick", "serialFrameBytes", "serialReceiveFrames", "serialReceiveBytes")) {
            assertInvalid("biocomputing.hardware.profiles.program." + key, List.of(1L, 2L, 3L, 4L));
            assertInvalid("biocomputing.hardware.profiles.program." + key, List.of(1L, 2L, 0L, 4L, 5L));
            assertInvalid("biocomputing.hardware.profiles.program." + key, List.of(1L, 2L, 3.5, 4L, 5L));
        }
        for (String key : List.of("serialFrameBytes", "serialReceiveFrames"))
            assertInvalid("biocomputing.hardware.profiles.program." + key, List.of(1L, 2L, Long.MAX_VALUE, 4L, 5L));
        assertInvalid("biocomputing.hardware.profiles.program.serialPorts", List.of("s0", "s0"));
    }
    @Test void onlyExplicitPhysicalPortsGetIndependentDuplexCircuits() throws ReflectiveOperationException {
        var fixture = fixture();
        var resident = villager("minecraft:armorer", 2);
        assertTrue(fixture.config.select(resident, false).limits().serial().ports().get("s0").duplex().isEmpty());
        String base = "biocomputing.hardware.profiles.program.";
        fixture.values.set(base + "serialPorts", List.of("s0", "s1"));
        fixture.values.set(base + "serialDuplexPorts", List.of("s1"));
        fixture.values.set(base + "serialDuplexTransmitBitsPerTick", List.of(228L, 256L, 384L, 512L, 768L));
        fixture.values.set(base + "serialDuplexReceiveBitsPerTick", List.of(64L, 96L, 128L, 160L, 192L));
        load(fixture);
        try {
            var ports = fixture.config.select(resident, true).limits().serial().ports();
            assertTrue(ports.get("s0").duplex().isEmpty());
            assertEquals(128, ports.get("s0").bitsPerTick());
            assertEquals(new SerialHardware.IndependentDuplex(256, 96), ports.get("s1").duplex().orElseThrow());
            fixture.values.set(base + "serialDuplexPorts", List.of("s0"));
            fixture.spec.afterReload();
            var updated = fixture.config.select(resident, true).limits().serial().ports();
            assertTrue(updated.get("s1").duplex().isEmpty());
            assertEquals(new SerialHardware.IndependentDuplex(256, 96), updated.get("s0").duplex().orElseThrow());
            fixture.values.set(base + "serialDuplexPorts", List.of("uninstalled"));
            fixture.spec.afterReload();
            assertEquals(HardwareSelection.Status.INVALID_SERIAL, fixture.config.select(resident, true).status());
        } finally { fixture.spec.acceptConfig(null); }
    }

    @Test void removingSerialInterfacesDoesNotDisableCpuForUnusedCrossFieldCapacity() throws ReflectiveOperationException {
        var fixture = fixture();
        fixture.values.set("biocomputing.hardware.profiles.program.serialPorts", List.of());
        fixture.values.set("biocomputing.hardware.profiles.program.serialReceiveBytes", List.of(1L, 1L, 1L, 1L, 1L));
        load(fixture);
        try {
            var selected = fixture.config.select(villager("minecraft:armorer", 3), true);
            assertTrue(selected.ready()); assertTrue(selected.limits().serial().ports().isEmpty());
            assertEquals(new ResourceLimits(160, 32768, 32,32), selected.limits().hardware());
            assertEquals(HardwarePins.DEFAULT, selected.limits().pins());
        } finally { fixture.spec.acceptConfig(null); }
    }
	@Test void onlyEquippedVillagersExposeWiredPortsRegardlessOfLevel() {
		var config = fixture().config;
		for (String profession : List.of("none", "nitwit", "armorer", "butcher", "cartographer", "cleric", "farmer",
			"fisherman", "fletcher", "leatherworker", "librarian", "mason", "shepherd", "toolsmith", "weaponsmith")) {
			for (int level = 1; level <= 5; level++) {
				var selected = config.select(villager("minecraft:" + profession, level), false);
				assertTrue(selected.ready());
                if (List.of("fletcher","cartographer","cleric").contains(profession)) {
                    assertTrue(selected.limits().serial().ports().isEmpty());assertTrue(selected.limits().pins().hasXBus("x0"));
                    continue;
                }
				assertEquals(List.of("butcher","leatherworker","librarian").contains(profession) ? List.of("s0","s1") : List.of("s0"),new ArrayList<>(selected.limits().serial().ports().keySet()));
				var port = selected.limits().serial().ports().get("s0");
				assertEquals(128, port.bitsPerTick()); assertEquals(4096, port.maxFrameBytes());
				assertEquals(2, port.receiveFrames()); assertEquals(8192, port.receiveBytes());
			}
		}
		assertTrue(config.select(null, false).limits().serial().ports().isEmpty());
		assertFalse(config.select(new ResidentHardwareIdentity(ZOMBIE_VILLAGER, id("minecraft:none"), 1), false)
			.limits().serial().ports().isEmpty());
	}
	@Test void overlappingInterfaceNamesDisableOnlyThatProfileWithoutCrashingTheServer() throws ReflectiveOperationException {
		var fixture = fixture();
		fixture.values.set("biocomputing.hardware.profiles.program.simpleIoPins", List.of("x0"));
		load(fixture);
		try {
			var invalid = assertDoesNotThrow(() -> fixture.config.select(villager("minecraft:armorer", 1), true));
			assertFalse(invalid.ready()); assertEquals("INVALID_PINS", invalid.status().name());
			assertEquals("program", invalid.profile());
			assertTrue(fixture.config.select(villager("minecraft:farmer", 1), true).ready());
			fixture.values.set("biocomputing.hardware.profiles.program.simpleIoPins", List.of("sense")); fixture.spec.afterReload();
			assertTrue(fixture.config.select(villager("minecraft:armorer", 1), true).ready());
		} finally { fixture.spec.acceptConfig(null); }
	}
	@Test void signalPinsAreIndependentPerProfileAndCanBeRemovedWithoutChangingXBus() throws ReflectiveOperationException {
		var fixture = fixture();
		assertEquals(List.of("p0", "p1"), fixture.config.select(villager("minecraft:armorer", 1), false).limits().pins().simpleIo());
		fixture.values.set("biocomputing.hardware.profiles.program.simpleIoPins", List.of("sense", "drive"));
		fixture.values.set("biocomputing.hardware.profiles.speed.simpleIoPins", List.of());
		load(fixture);
		try {
			var pins = fixture.config.select(villager("minecraft:armorer", 5), true).limits().pins();
			assertEquals(List.of("sense", "drive"), pins.simpleIo()); assertEquals(List.of("x0", "x1"), pins.xbus());
			assertEquals(List.of("p0", "p1", "p2", "p3"), fixture.config.select(villager("minecraft:farmer", 1), true).limits().pins().simpleIo());
			assertTrue(fixture.config.select(villager("minecraft:fletcher", 1), true).limits().pins().simpleIo().isEmpty());
		} finally { fixture.spec.acceptConfig(null); }
	}
	@Test void physicalPinsAreConfiguredPerProfileRatherThanCreatedByPrograms() throws ReflectiveOperationException {
		var fixture = fixture();
		assertEquals(List.of("x0", "x1"), fixture.config.select(villager("minecraft:armorer", 1), false).limits().pins().xbus());
		fixture.values.set("biocomputing.hardware.profiles.program.xbusPins", List.of("x0", "x1", "x2", "x3"));
		fixture.values.set("biocomputing.hardware.profiles.speed.xbusPins", List.of());
		load(fixture);
		try {
			assertEquals(List.of("x0", "x1", "x2", "x3"), fixture.config.select(villager("minecraft:armorer", 1), true).limits().pins().xbus());
			assertEquals(List.of("x0", "x1"), fixture.config.select(villager("minecraft:farmer", 5), true).limits().pins().xbus());
			assertTrue(fixture.config.select(villager("minecraft:fletcher", 1), true).limits().pins().xbus().isEmpty());
		} finally { fixture.spec.acceptConfig(null); }
		for (Object invalid : List.of(List.of("x0", "x0"), List.of(" "), List.of("x\n0"), List.of(" x0"), List.of(1), List.of("x".repeat(65))))
			assertInvalid("biocomputing.hardware.profiles.program.xbusPins", invalid);
	}
	@Test void mailboxCapsAreServerConfiguredAndRetainedByResidentSelection() throws ReflectiveOperationException {
		var fixture = fixture();
		assertEquals(16, fixture.config.current(false).mailbox().messages());
		assertEquals(64, fixture.config.current(false).mailbox().sources());
		fixture.values.set("biocomputing.communication.inboxMessages", 3);
		fixture.values.set("biocomputing.communication.trackedSources", 5);
		load(fixture);
		try {
			var expected = new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits(3, 5);
			assertEquals(expected, fixture.config.current(true).mailbox());
			var resident = new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:armorer"), 1);
			assertEquals(expected, fixture.config.select(resident, true).limits().mailbox());
		} finally { fixture.spec.acceptConfig(null); }
	}
	@Test void serializedBlockPolicyUsesDefaultAndCanBeDisabledByServer() throws ReflectiveOperationException {
		var fixture = fixture();
		assertTrue(fixture.config.serializedBlockDataAllowed(false));
		fixture.values.set("biocomputing.peripherals.allowSerializedBlockData", false);
		load(fixture);
		try { assertFalse(fixture.config.serializedBlockDataAllowed(true)); }
		finally { fixture.spec.acceptConfig(null); }
		assertTrue(fixture.config.serializedBlockDataAllowed(false));
	}
	@Test void peripheralCeilingsUseDefaultsBeforeLoadAndRespectServerConfiguration() throws ReflectiveOperationException {
		var fixture = fixture(); var defaults = fixture.config.peripherals(false);
		assertEquals(2048, defaults.maxVisited()); assertEquals(8, defaults.maxReaders());
		assertEquals(4096, defaults.snapshot().maxEntries());
		fixture.values.set("biocomputing.peripherals.visitedPositions", 90);
		fixture.values.set("biocomputing.peripherals.readers", 2);
		fixture.values.set("biocomputing.peripherals.entries", 80);
		fixture.values.set("biocomputing.peripherals.stringChars", 100L);
		fixture.values.set("biocomputing.peripherals.valueUnits", 200L);
		load(fixture); var configured = fixture.config.peripherals(true);
		assertEquals(90, configured.maxVisited()); assertEquals(2, configured.maxReaders());
		assertEquals(80, configured.snapshot().maxEntries());
		assertEquals(100, configured.snapshot().maxStringChars()); assertEquals(200, configured.snapshot().maxValueUnits());
		assertEquals(defaults, fixture.config.peripherals(false));
	}
	private static final ResourceLocation VILLAGER = id("minecraft:villager");
	private static final ResourceLocation WANDERING_TRADER = id("minecraft:wandering_trader");
	private static final ResourceLocation ZOMBIE_VILLAGER = id("minecraft:zombie_villager");

	@Test
	void defaultsSelectLiteralProfileRowsByVillagerLevel() {
		var fixture = fixture();
		assertHardware(fixture.config.select(villager("minecraft:farmer", 1), false), "balanced", 32, 32768, 4);
		assertHardware(fixture.config.select(villager("minecraft:armorer", 5), false), "program", 320, 65536, 8);
		assertHardware(fixture.config.select(villager("minecraft:cartographer", 4), false), "memory", 72, 196608, 6);
		assertHardware(fixture.config.select(villager("minecraft:fletcher", 3), false), "speed", 32, 32768, 16);
		assertEquals(fixture.config.current(false).archive(),
			fixture.config.select(villager("minecraft:cartographer", 4), false).limits().archive());
	}

	@Test
	void specialResidentsUseTheirUnrankedDefault() {
		var config = fixture().config;
		assertHardware(config.select(new ResidentHardwareIdentity(WANDERING_TRADER, id("minecraft:none"), 5), false),
			"trader", 32, 32768, 4);
		var zombie = config.select(new ResidentHardwareIdentity(ZOMBIE_VILLAGER, id("minecraft:none"), 1), false);
		assertHardware(zombie,"zombie",24,16384,2);
	}

	@Test
	void selectionDistinguishesMissingInvalidAndUnsupportedResidentsWithExactNamespaceMatching() {
		var config = fixture().config;
		assertEquals(HardwareSelection.Status.NO_RESIDENT, config.select(null, false).status());
		assertEquals(HardwareSelection.Status.INVALID_RESIDENT,
			config.select(villager("minecraft:farmer", 0), false).status());
		assertEquals(HardwareSelection.Status.UNSUPPORTED_RESIDENT,
			config.select(new ResidentHardwareIdentity(id("minecraft:pig"), id("minecraft:farmer"), 1), false).status());
		assertEquals(HardwareSelection.Status.UNSUPPORTED_RESIDENT,
			config.select(villager("example:farmer", 1), false).status());
		assertEquals(HardwareSelection.Status.UNSUPPORTED_RESIDENT,
			config.select(villager("minecraft:unregistered_profession", 1), false).status());
	}

	@Test
	void serverCeilingsClampEveryProfileComponentWithoutScalingArchiveLimits() throws ReflectiveOperationException {
		var fixture = fixture();
		fixture.values.set("biocomputing.programUnits", 40L);
		fixture.values.set("biocomputing.memoryUnits", 20000L);
		fixture.values.set("biocomputing.workUnitsPerTick", 5L);
		load(fixture);
		try {
			var selection = fixture.config.select(villager("minecraft:armorer", 5), true);
			assertHardware(selection, "program", 40, 20000, 5);
			assertEquals(fixture.config.current(true).archive(), selection.limits().archive());
		} finally {
			fixture.spec.acceptConfig(null);
		}
	}

	@Test
	void rejectsMalformedBudgetRowsAndAssignmentsAsWholeConfigValues() {
		assertInvalid("biocomputing.hardware.profiles.balanced.programUnits", List.of(1L, 2L, 3L, 4L));
		assertInvalid("biocomputing.hardware.profiles.balanced.programUnits", List.of(1L, 2L, 3L, 4L, 5.0));
		assertInvalid("biocomputing.hardware.profiles.balanced.memoryUnits", List.of(1L, 2L, -1L, 4L, 5L));
		assertInvalid("biocomputing.hardware.profiles.balanced.workUnitsPerTick", List.of(1L, 2L, 0L, 4L, 5L));
		assertInvalid("biocomputing.hardware.villagerProfiles", List.of("farmer=balanced"));
		assertInvalid("biocomputing.hardware.villagerProfiles", List.of("minecraft:=balanced"));
		assertInvalid("biocomputing.hardware.villagerProfiles", List.of("minecraft:farmer=balanced", "minecraft:farmer=speed"));
		assertInvalid("biocomputing.hardware.villagerProfiles", List.of("minecraft:farmer=turbo"));
		assertInvalid("biocomputing.hardware.entityProfiles", List.of("minecraft:pig=balanced"));
	}

	@Test
	void loadedIntegralRowsDoNotDependOnTheConcreteNumberWrapper() throws ReflectiveOperationException {
		var fixture = fixture();
		fixture.values.set("biocomputing.hardware.profiles.balanced.programUnits", List.of(11, 12, 13, 14, 15));
		load(fixture);
		try {
			assertHardware(fixture.config.select(villager("minecraft:farmer", 3), true),
				"balanced", 13, 65536, 8);
		} finally {
			fixture.spec.acceptConfig(null);
		}
	}

	@Test
	void actualLoaderReloadsBothBindingAndBudgetsWithoutCachingTheOldSelection() throws ReflectiveOperationException {
		var fixture = fixture();
		fixture.values.set("biocomputing.hardware.villagerProfiles", List.of("example:farmer=memory"));
		fixture.values.set("biocomputing.hardware.profiles.memory.programUnits", List.of(3L, 4L, 5L, 6L, 7L));
		load(fixture);
		try {
			assertHardware(fixture.config.select(villager("example:farmer", 2), true), "memory", 4, 98304, 3);
			assertEquals(HardwareSelection.Status.UNSUPPORTED_RESIDENT,
				fixture.config.select(villager("minecraft:farmer", 2), true).status());

			fixture.values.set("biocomputing.hardware.villagerProfiles", List.of("example:farmer=speed"));
			fixture.values.set("biocomputing.hardware.profiles.speed.workUnitsPerTick", List.of(19L, 20L, 21L, 22L, 23L));
			fixture.spec.afterReload();
			assertHardware(fixture.config.select(villager("example:farmer", 2), true), "speed", 24, 24576, 20);
		} finally {
			fixture.spec.acceptConfig(null);
		}

		assertEquals(HardwareSelection.Status.UNSUPPORTED_RESIDENT,
			fixture.config.select(villager("example:farmer", 2), fixture.spec.isLoaded()).status());
		assertHardware(fixture.config.select(villager("minecraft:farmer", 2), fixture.spec.isLoaded()),
			"balanced", 48, 49152, 6);
	}

	@Test
	void programProfileCanInstallAGraphThatAProgramSmallerProfileRejects() {
		var config = fixture().config;
		var graph = connectedConstants(20);
		assertDoesNotThrow(() -> ProgramCompiler.compile(program(graph), Map.of(),
			config.select(villager("minecraft:armorer", 1), false).limits().hardware()));
		assertCode(PROGRAM_LIMIT, () -> ProgramCompiler.compile(program(graph), Map.of(),
			config.select(villager("minecraft:fletcher", 1), false).limits().hardware()));
	}

	@Test
	void memoryProfileCanInstallALiteralThatASmallMemoryProfileRejects() {
		var config = fixture().config;
		var graph = new ComputationGraph(Map.of(),
			Map.of("literal", new ComputationGraph.Constant(new DataValue.TextValue("x".repeat(20000)))),
			Map.of("value", "literal"));
		assertDoesNotThrow(() -> ProgramCompiler.compile(program(graph), Map.of(),
			config.select(villager("minecraft:cartographer", 1), false).limits().hardware()));
		assertCode(MEMORY_LIMIT, () -> ProgramCompiler.compile(program(graph), Map.of(),
			config.select(villager("minecraft:fletcher", 1), false).limits().hardware()));
	}

	@Test
	void speedProfileAdvancesMoreRealTransitionsForTheSameEvent() {
		var config = fixture().config;
		var graph = mapGraph();
		var modules = Map.of(DOUBLE, doubleModule());
		var slow = new ComputationMachine(ProgramCompiler.compile(program(graph), modules,
			config.select(villager("minecraft:farmer", 1), false).limits().hardware()));
		var fast = new ComputationMachine(ProgramCompiler.compile(program(graph), modules,
			config.select(villager("minecraft:fletcher", 1), false).limits().hardware()));
		var input = new DataValue.RecordValue(Map.of("samples", numbers(java.util.stream.LongStream.range(0, 100).toArray())));
		slow.begin(input);
		fast.begin(input);
		slow.tick();
		fast.tick();
		assertEquals(32, slow.report().workUnits());
		assertEquals(64, fast.report().workUnits());
		assertEquals(ComputationMachine.Status.RUNNING, slow.report().status());
	}

	private static Fixture fixture() {
		var builder = new ModConfigSpec.Builder();
		var config = new BiocomputingConfig(builder);
		var spec = builder.build();
		var values = CommentedConfig.inMemory();
		spec.correct(values);
		return new Fixture(config, spec, values);
	}

	private static void assertInvalid(String path, Object value) {
		var fixture = fixture();
		fixture.values.set(path, value);
		assertFalse(fixture.spec.isCorrect(fixture.values), path + " must reject " + value);
	}

	private static void load(Fixture fixture) throws ReflectiveOperationException {
		assertTrue(fixture.spec.isCorrect(fixture.values));
		var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
			.getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
		constructor.setAccessible(true);
		fixture.spec.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(fixture.values, null, null));
	}

	private static void assertHardware(HardwareSelection selection, String profile,
		long program, long memory, long work) {
		assertEquals(HardwareSelection.Status.READY, selection.status());
		assertTrue(selection.ready());
		assertEquals(profile, selection.profile());
		assertEquals(new ResourceLimits(program, memory, work * 8, selection.limits().hardware().registerSlots()), selection.limits().hardware());
	}

	private static void assertCode(ComputationException.Code code,
		org.junit.jupiter.api.function.Executable body) {
		assertEquals(code, assertThrows(ComputationException.class, body).code());
	}

	private static ResidentHardwareIdentity villager(String profession, int level) {
		return new ResidentHardwareIdentity(VILLAGER, id(profession), level);
	}

	private static ResourceLocation id(String value) {
		return ResourceLocation.parse(value);
	}

	private static ProgramDefinition program(ComputationGraph graph) {
		return new ProgramDefinition(graph, Map.of(), List.of());
	}

	private static ComputationGraph connectedConstants(int count) {
		var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
		var outputs = new LinkedHashMap<String, String>();
		for (int i = 0; i < count; i++) {
			String name = "n" + i;
			nodes.put(name, new ComputationGraph.Constant(new DataValue.IntegerValue(i)));
			outputs.put(name, name);
		}
		return new ComputationGraph(Map.of(), nodes, outputs);
	}

	private static final ModuleDefinition.Key DOUBLE = new ModuleDefinition.Key("hardware-double", 1);

	private static ModuleDefinition doubleModule() {
		return new ModuleDefinition(DOUBLE, new ComputationGraph(Map.of("value", INTEGER),
			Map.of("x", new ComputationGraph.Input("value"),
				"twice", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "x", "x")),
			Map.of("result", "twice")));
	}

	private static ComputationGraph mapGraph() {
		return new ComputationGraph(Map.of("samples", new DataType.ListType(INTEGER)),
			Map.of("input", new ComputationGraph.Input("samples"),
				"map", new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP, "input", DOUBLE,
					"value", Map.of(), "result")), Map.of("samples", "map"));
	}

	private static DataValue.ListValue numbers(long... values) {
		var result = new ArrayList<DataValue>();
		for (long value : values)
			result.add(new DataValue.IntegerValue(value));
		return new DataValue.ListValue(result);
	}

	private record Fixture(BiocomputingConfig config, ModConfigSpec spec, CommentedConfig values) {}
}
