package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession;
import java.util.*;
import org.junit.jupiter.api.*;

class ComputerSignalControlTest {
    @BeforeAll static void bootstrap() throws Exception { ComputerBehaviourTest.bootstrap(); }
    @Test void clusterSignalConfigurationIsADedicatedAuthorizedMenuAction() {
        var computer = new ComputerBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        var serial = computer.serialStatus();
        var action = ComputerControlSession.Action.valueOf("CONFIGURE_SIMPLE_IO_GATEWAY");
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        assertTrue(action.signal()); assertFalse(action.serial());
        var config = new com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections.GatewayConfiguration("p0", "status");
        long revision = computer.programRevision();
        var denied = session.apply(nonce, 1, revision, action, config::value, computer).orElseThrow();
        assertEquals("open_from_controller", denied.detail()); assertTrue(computer.signalConnections().bindings().isEmpty());
        var binding = new com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterPortBinding(UUID.randomUUID(), net.minecraft.core.BlockPos.ZERO,
            net.minecraft.core.Direction.EAST, "status");
        var answer = session.apply(nonce, 2, revision, action, config::value, computer, () -> {},
            ignored -> { throw new AssertionError("SIO must not use serial authorization"); }, ignored -> binding).orElseThrow();
        assertEquals("ok", answer.code());
        assertEquals(Optional.of(binding), computer.signalConnections().bindings().get("p0").gateway());
        assertFalse(computer.signalConnections().bindings().get("p0").enabled());
        assertTrue(computer.advertisement().isEmpty()); assertTrue(computer.document().isEmpty());
        assertEquals(serial, computer.serialStatus());
    }
    @Test void physicalMenuCanPublishOneExplicitSignalWithoutOpeningAProtocolEndpoint() {
        var block = new ComputerBehaviourTest.Harness();
        var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var read = ComputerControlSession.Action.valueOf("READ_SIMPLE_IO");
        var configure = ComputerControlSession.Action.valueOf("CONFIGURE_SIMPLE_IO");
        long revision = computer.programRevision();
        var initial = session.apply(nonce, 1, revision, read, () -> ComputerBehaviourTest.EMPTY, computer).orElseThrow();
        assertEquals("ok", initial.code());
        var ports = (DataValue.RecordValue)initial.dedicatedStatus().orElseThrow().field("ports");
        assertEquals(new DataValue.BooleanValue(false), ((DataValue.RecordValue)ports.field("p0")).field("enabled"));
        var payload = new DataValue.RecordValue(Map.of("pin", new DataValue.TextValue("p0"),
            "side", new DataValue.TextValue("east"), "enabled", new DataValue.BooleanValue(true)));
        var answer = session.apply(nonce, 2, revision, configure, () -> payload, computer).orElseThrow();
        assertEquals("ok", answer.code());
        var configured = (DataValue.RecordValue)((DataValue.RecordValue)answer.dedicatedStatus().orElseThrow().field("ports")).field("p0");
        assertEquals(new DataValue.BooleanValue(true), configured.field("enabled"));
        assertEquals(new DataValue.TextValue("east"), configured.field("side"));
        assertTrue(computer.advertisement().isEmpty(), "SIO wiring does not grant the legacy messaging endpoint");
        assertTrue(computer.snapshot().isEmpty(), "Configuring a pin does not install or run a program");
        assertTrue(session.apply(nonce, 2, revision, configure, () -> payload, computer).isEmpty());
        var saved = new net.minecraft.nbt.CompoundTag(); computer.write(saved, net.minecraft.core.RegistryAccess.EMPTY, false);
        var restored = new ComputerBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        restored.read(saved, net.minecraft.core.RegistryAccess.EMPTY, false);
        assertEquals(computer.signalConnections().write(), restored.signalConnections().write());
        computer.write(saved, net.minecraft.core.RegistryAccess.EMPTY, true);
        assertFalse(saved.contains(ComputerBehaviour.SIGNAL_ROOT), "Physical grants never ride the public BE packet");
    }
    @Test void authorityRevisionAndPinChecksRunBeforeChangingAnyGrant() {
        var block = new ComputerBehaviourTest.Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var action = ComputerControlSession.Action.CONFIGURE_SIMPLE_IO; long revision = computer.programRevision();
        var denied = session.apply(nonce, 1, revision, action, () -> { throw new AssertionError("Denied input decoded"); }, computer,
            () -> { throw new com.nobodiiiii.createbiotech.content.biocomputing.network.PeerDiscovery.DiscoveryException("invalid", "access_denied"); }).orElseThrow();
        assertEquals("access_denied", denied.detail()); assertTrue(denied.dedicatedStatus().orElseThrow().fields().isEmpty());
        var unknown = new com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections.Configuration("not_a_pin", net.minecraft.core.Direction.EAST, true);
        assertEquals("invalid", session.apply(nonce, 2, revision, action, unknown::value, computer).orElseThrow().code());
        assertNull(computer.signalConnections().write());
        var settings = new com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections.Configuration("p0", net.minecraft.core.Direction.EAST, true);
        assertEquals("ok", session.apply(nonce, 3, revision, action, settings::value, computer).orElseThrow().code());
        var before = computer.signalConnections().write();
        assertEquals("stale", session.apply(nonce, 4, revision, action, () -> { throw new AssertionError("Stale input decoded"); }, computer).orElseThrow().code());
        block.available = false;
        assertNotEquals("ok", session.apply(nonce, 5, computer.programRevision(), action, settings::value, computer).orElseThrow().code());
        assertEquals(before, computer.signalConnections().write());
    }
}
