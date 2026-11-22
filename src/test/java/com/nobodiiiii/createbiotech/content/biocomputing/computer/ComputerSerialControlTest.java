package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.*;

class ComputerSerialControlTest {
    @BeforeAll static void bootstrap() throws Exception { ComputerBehaviourTest.bootstrap(); }
    private static ComputerBehaviourTest.Harness computer() {
        var block = new ComputerBehaviourTest.Harness(); var old = block.selection; var limits = old.limits();
        block.selection = new HardwareSelection(old.status(), old.resident(), old.profile(), new ComputerHostLimits(
            limits.hardware(), limits.archive(), limits.mailbox(), limits.pins(),
            new SerialHardware(Map.of("s0", new SerialHardware.Port(128, 4096, 2, 8192)))));
        return block;
    }
    private static DataValue.RecordValue settings(boolean enabled) {
        return new DataValue.RecordValue(Map.of("port", new DataValue.TextValue("s0"),
            "side", new DataValue.TextValue("east"), "enabled", new DataValue.BooleanValue(enabled)));
    }
    @Test void readingRecentEventsCannotResampleHardwareOrInterruptAnAcceptedSerialFrame() {
        var block = computer(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        computer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
        var serial = computer.serialDevice(); serial.configure("s0", net.minecraft.core.Direction.EAST, true);
        var payload = new DataValue.RecordValue(Map.of("private", new DataValue.TextValue("not-for-diagnostics")));
        assertEquals("queued", serial.send("s0", payload));
        var stored = serial.write(); var runtime = computer.snapshot();
        int samples = block.samples, changes = block.changes; long revision = computer.programRevision();
        var old = block.selection; var limits = old.limits();
        block.selection = new HardwareSelection(old.status(), old.resident(), old.profile(), new ComputerHostLimits(
            limits.hardware(), limits.archive(), limits.mailbox(), limits.pins(),
            new SerialHardware(Map.of("s0", new SerialHardware.Port(64, 4096, 2, 8192)))));

        var observed = computer.recentEvents();
        assertEquals(stored, serial.write(), "Reading a diagnostic must not synchronize hardware or discard a queued frame");
        assertEquals(samples, block.samples); assertEquals(changes, block.changes);
        assertEquals(runtime, computer.snapshot()); assertEquals(revision, computer.programRevision());
        for (var entry : ((DataValue.ListValue)observed.field("entries")).values()) {
            var fields = ((DataValue.RecordValue)entry).fields();
            assertEquals(Set.of("tick", "sequence", "kind", "code", "subject"), fields.keySet());
            assertFalse(fields.values().contains(new DataValue.TextValue("not-for-diagnostics")));
        }
        assertEquals(observed, computer.recentEvents(), "Repeated observation is not a new event");

        // A real device/transport access still detects changed hardware; the log is not an authority cache.
        assertEquals(64, serial.attachments().getFirst().hardware().bitsPerTick());
        assertEquals("interrupted", serial.status("s0").transmit());
        assertEquals("hardware_changed", serial.status("s0").failure());
        assertNotEquals(stored, serial.write());
        assertTrue(((DataValue.ListValue)computer.recentEvents().field("entries")).values().stream()
            .map(entry -> ((DataValue.RecordValue)entry).field("code"))
            .anyMatch(new DataValue.TextValue("serial_hardware_changed")::equals));
    }
    @Test void recentEventsDoNotRestoreRetainedSerialStorageOrChangeItsRecoveryEvidence() {
        var original = computer().getBehaviour(ComputerBehaviour.TYPE);
        original.serialDevice().configure("s0", net.minecraft.core.Direction.EAST, true);
        assertEquals("queued", original.serialDevice().send("s0", new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(7)))));
        var saved = new CompoundTag(); original.write(saved, RegistryAccess.EMPTY, false);
        var block = computer(); var restored = block.getBehaviour(ComputerBehaviour.TYPE);
        restored.read(saved, RegistryAccess.EMPTY, false);
        var retained = restored.serialDevice().write(); int samples = block.samples, changes = block.changes;
        restored.recentEvents(); restored.observeSerial();
        assertEquals(retained, restored.serialDevice().write(), "Opening a log must retain the opaque storage image");
        assertEquals(samples, block.samples); assertEquals(changes, block.changes);
        assertTrue(restored.snapshot().isEmpty());

        var current = restored.serialDevice().view().ports().get("s0").status();
        assertEquals("interrupted", current.transmit()); assertEquals("reloaded", current.failure());
        assertNotEquals(retained, restored.serialDevice().write(), "Real access, not logging, resolves recovery once");
        assertTrue(restored.serialDevice().prepareReceive("s0").result().fields().get("status").equals(new DataValue.TextValue("empty")));
    }
    @Test void gatewayControlsRequireServerMenuContextPreserveMemberResourcesAndCanExplicitlyReturnToClosedLocalPort() {
        var block = computer(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var action = ComputerControlSession.Action.valueOf("CONFIGURE_SERIAL_GATEWAY");
        var clear = ComputerControlSession.Action.valueOf("CLEAR_SERIAL_GATEWAY");
        var request = new DataValue.RecordValue(Map.of("port", new DataValue.TextValue("s0"), "alias", new DataValue.TextValue("sensor")));
        long revision = computer.programRevision();
        var denied = session.apply(nonce, 1, revision, action, () -> request, computer).orElseThrow();
        assertEquals("invalid", denied.code()); assertEquals("open_from_controller", denied.detail());
        assertNull(computer.serialDevice().write());
        var binding = new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayBinding(
            UUID.randomUUID(), new net.minecraft.core.BlockPos(2, 3, 4), net.minecraft.core.Direction.EAST, "sensor");
        var accepted = session.apply(nonce, 2, revision, action, () -> request, computer, () -> {}, ignored -> binding).orElseThrow();
        assertEquals("ok", accepted.code());
        var port = SerialControlData.decode(accepted.dedicatedStatus().orElseThrow()).ports().get("s0");
        assertEquals(Optional.of(binding), port.gateway()); assertFalse(port.enabled());
        assertTrue(computer.document().isEmpty()); assertTrue(computer.advertisement().isEmpty());
        computer.configureSerial(new SerialControlData.Configuration("s0", net.minecraft.core.Direction.EAST, true));
        assertEquals(Optional.of(binding), SerialControlData.decode(computer.serialStatus()).ports().get("s0").gateway());
        var cleared = session.apply(nonce, 3, computer.programRevision(), clear,
            () -> new DataValue.RecordValue(Map.of("port", new DataValue.TextValue("s0"))), computer).orElseThrow();
        assertEquals("ok", cleared.code());
        var local = SerialControlData.decode(cleared.dedicatedStatus().orElseThrow()).ports().get("s0");
        assertTrue(local.gateway().isEmpty()); assertFalse(local.enabled());
    }
    @Test void ordinaryResidentConfiguresRealPortWithoutInstallingAProgramOrOpeningLegacyEndpoint() {
        var block = computer(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var configure = ComputerControlSession.Action.valueOf("CONFIGURE_SERIAL");
        var read = ComputerControlSession.Action.valueOf("READ_SERIAL");
        long revision = computer.programRevision();
        var initial = session.apply(nonce, 1, revision, read, () -> { throw new AssertionError("read decoded payload"); }, computer).orElseThrow();
        assertEquals("ok", initial.code()); assertTrue(initial.snapshot().isEmpty());
        assertNull(computer.serialDevice().write());
        var configured = session.apply(nonce, 2, revision, configure, () -> settings(true), computer).orElseThrow();
        assertEquals("ok", configured.code()); assertTrue(configured.revision() > revision);
        assertEquals(net.minecraft.core.Direction.EAST, computer.serialDevice().attachments().getFirst().side());
        assertTrue(computer.document().isEmpty()); assertTrue(computer.advertisement().isEmpty());
        var saved = new CompoundTag(); computer.write(saved, RegistryAccess.EMPTY, false);
        var restored = computer().getBehaviour(ComputerBehaviour.TYPE); restored.read(saved, RegistryAccess.EMPTY, false);
        assertEquals(computer.serialDevice().write(), restored.serialDevice().write());
        var result = session.apply(nonce, 3, configured.revision(), configure, () -> settings(false), computer).orElseThrow();
        assertEquals("ok", result.code()); assertTrue(computer.serialDevice().attachments().isEmpty());
        assertEquals("closed", computer.serialDevice().status("s0").connection());
    }
    @Test void deniedStaleRepeatedAndMalformedControlsDoNotChangeDeviceOrLeakPrivateState() {
        var block = computer(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var configure = ComputerControlSession.Action.CONFIGURE_SERIAL;
        long revision = computer.programRevision();
        var denied = session.apply(nonce, 1, revision, configure, () -> { throw new AssertionError("denied input"); }, computer,
            () -> { throw new com.nobodiiiii.createbiotech.content.biocomputing.network.PeerDiscovery.DiscoveryException("invalid", "access_denied"); }).orElseThrow();
        assertEquals("invalid", denied.code()); assertEquals("access_denied", denied.detail()); assertTrue(denied.snapshot().isEmpty());
        assertTrue(denied.dedicatedStatus().orElseThrow().fields().isEmpty()); assertNull(computer.serialDevice().write());
        assertTrue(session.apply(nonce, 1, revision, configure, () -> { throw new AssertionError("replay"); }, computer).isEmpty());
        var bad = new LinkedHashMap<>(settings(true).fields()); bad.put("payload", new DataValue.TextValue("not_configuration"));
        assertEquals("invalid", session.apply(nonce, 2, revision, configure, () -> new DataValue.RecordValue(bad), computer).orElseThrow().code());
        assertNull(computer.serialDevice().write()); assertEquals(revision, computer.programRevision());
        var accepted = session.apply(nonce, 3, revision, configure, () -> settings(true), computer).orElseThrow();
        var before = computer.serialDevice().write();
        assertEquals("stale", session.apply(nonce, 4, revision, configure, () -> { throw new AssertionError("stale input"); }, computer).orElseThrow().code());
        assertEquals(before, computer.serialDevice().write());
        block.available = false;
        assertEquals("unavailable", session.apply(nonce, 5, accepted.revision(), configure, () -> settings(false), computer).orElseThrow().code());
        assertEquals(before, computer.serialDevice().write());
    }
    @Test void metadataCannotExposePayloadAndAnUnrepresentableConfigCannotPartiallyApply() {
        var block = computer(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        computer.configureSerial(new SerialControlData.Configuration("s0", net.minecraft.core.Direction.EAST, true));
        var record = new DataValue.RecordValue(Map.of("private", new DataValue.TextValue("never_in_menu")));
        var codec = new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits(4096, 1048576, block.selection.limits().archive());
        computer.serialDevice().attachments().getFirst().receiver().offer(
            com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(record, codec));
        var observed = computer.serialStatus();
        assertFalse(observed.toString().contains("never_in_menu")); assertFalse(observed.toString().contains("Identity"));
        var decoded = SerialControlData.decode(observed);
        assertEquals(1, decoded.ports().get("s0").status().receive().frames());
        assertEquals(observed, SerialControlData.encode(decoded));
        var before = computer.serialDevice().write(); long revision = computer.programRevision();
        var old = block.selection; var limits = old.limits();
        block.selection = new HardwareSelection(old.status(), old.resident(), old.profile(), new ComputerHostLimits(limits.hardware(),
            new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(1, 1, 1), limits.mailbox(), limits.pins(), limits.serial()));
        assertThrows(IllegalArgumentException.class, () -> computer.configureSerial(
            new SerialControlData.Configuration("s0", net.minecraft.core.Direction.WEST, false)));
        assertEquals(before, computer.serialDevice().write()); assertEquals(revision, computer.programRevision());
    }
    @Test void oversizedGatewayDirectoryIsRefusedBeforeMutationRatherThanExpandingTheWireBudget() {
        var block = computer(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var specs = new TreeMap<String, SerialHardware.Port>();
        for (int i = 0; i < HardwarePins.MAX_PINS; i++) specs.put(String.format("%02d", i) + "p".repeat(62), new SerialHardware.Port(128, 4096, 2, 8192));
        var old = block.selection; var limits = old.limits();
        block.selection = new HardwareSelection(old.status(), old.resident(), old.profile(), new ComputerHostLimits(
            limits.hardware(), limits.archive(), limits.mailbox(), limits.pins(), new SerialHardware(specs)));
        int accepted = 0; boolean refused = false;
        for (String name : specs.keySet()) {
            var binding = new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayBinding(UUID.randomUUID(),
                new net.minecraft.core.BlockPos(accepted, 1, 2), net.minecraft.core.Direction.WEST, name);
            var before = computer.serialDevice().write(); long revision = computer.programRevision();
            try { computer.configureSerialGateway(name, Optional.of(binding)); accepted++; }
            catch (IllegalArgumentException tooLarge) {
                refused = true; assertEquals(before, computer.serialDevice().write()); assertEquals(revision, computer.programRevision()); break;
            }
            var bytes = ProgramEditorWire.encodeValue(computer.serialStatus(), computer.hardware().limits().archive());
            assertEquals(computer.serialDevice().view(), SerialControlData.decode((DataValue.RecordValue)ProgramEditorWire.decodeValue(bytes, computer.hardware().limits().archive())));
        }
        assertTrue(accepted > 0); assertTrue(refused, "Oversized full directory has an explicit limit, never a partial update");
    }
}
