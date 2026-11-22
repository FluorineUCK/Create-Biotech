package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import java.util.*;
import net.minecraft.core.*;
import org.junit.jupiter.api.Test;

class XBusCableRouteTest {
    static final CommunicationNetworkLimits SCAN = new CommunicationNetworkLimits(100, 16);
    // These test programs explicitly send record values; real installations need Parser hardware.
    static final ComputerHostLimits HOST = new ComputerHostLimits(new ResourceLimits(1000, 100000, 1),
        new ArchiveLimits(100000, 1000000, 1000000), MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 1, true);
    static final BlockPos DESTINATION = new BlockPos(2, 0, 0);
    static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
    static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    static final DataValue.RecordValue INPUTS = new DataValue.RecordValue(Map.of("packet", new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12)))));

    @Test void explicitCableResolutionPinsDeviceAndComputerButDoesNotConsumeTheWaitingRecord() {
        var world = new World(); var peer = new Peer(false); world.providers.put(DESTINATION, peer);
        var before = peer.host.flowSnapshot();
        var result = XBusCableRoute.bind(world, BlockPos.ZERO, SCAN, "io", "x1");
        assertEquals("ready", result.code());
        var resolved = result.pin().orElseThrow();
        assertEquals(new XBusCableTarget(DESTINATION, "primary", "io", "x1", peer.device, peer.computer()), resolved.target());
        assertEquals(peer.xbusPort("io", "x1").orElseThrow(), resolved.observed());
        assertEquals(before, peer.host.flowSnapshot());
        assertTrue(peer.host.flowSnapshot().orElseThrow().receipts().isEmpty());
        assertEquals(0, peer.inboxCalls);
    }

    @Test void aCapturedCableAccessCannotTransferAfterDisconnectionAndNeverUsesAnInbox() {
        var world = new World(); var reader = new Peer(false); var writer = new Peer(true); world.providers.put(DESTINATION, reader);
        var resolved = XBusCableRoute.bind(world, BlockPos.ZERO, SCAN, "io", "x1").pin().orElseThrow();
        var left = writer.xbusAccess("io", writer.xbusPort("io", "x0").orElseThrow()).orElseThrow();
        var a = writer.host.flowSnapshot(); var b = reader.host.flowSnapshot();
        world.connected = false;
        assertEquals(XBusExchange.Status.UNAVAILABLE, left.exchange(resolved.access()));
        assertEquals(a, writer.host.flowSnapshot()); assertEquals(b, reader.host.flowSnapshot());
        world.connected = true;
        assertEquals(XBusExchange.Status.EXCHANGED, left.exchange(resolved.access()));
        assertEquals(INPUTS, reader.host.flowSnapshot().orElseThrow().state().locals());
        assertEquals(0, reader.inboxCalls);
        assertEquals(XBusExchange.Status.STALE, left.exchange(resolved.access()));
    }

    @Test void fixedTargetSurvivesNewFlowAndDuplicateNameButNeverRetargetsAReplacementDeviceOrMember() {
        var world = new World(); var reader = new Peer(false); var writer = new Peer(true); world.providers.put(DESTINATION, reader);
        var old = XBusCableRoute.bind(world, BlockPos.ZERO, SCAN, "io", "x1").pin().orElseThrow();
        var left = writer.xbusAccess("io", writer.xbusPort("io", "x0").orElseThrow()).orElseThrow();
        reader.host.reset(HOST, true); reader.host.setPaused(false, HOST, true); reader.host.tick(HOST, true);
        world.providers.put(new BlockPos(1, 1, 0), new Peer(false));
        assertEquals("ambiguous", XBusCableRoute.bind(world, BlockPos.ZERO, SCAN, "io", "x1").code());
        var current = XBusCableRoute.resolve(world, BlockPos.ZERO, SCAN, old.target());
        assertEquals("ready", current.code());
        assertEquals(old.target(), current.pin().orElseThrow().target());
        assertNotEquals(old.observed().flow(), current.pin().orElseThrow().observed().flow());
        assertEquals(XBusExchange.Status.STALE, left.exchange(old.access()));
        var before = reader.host.flowSnapshot();
        var replacement = new Peer(false); world.providers.put(DESTINATION, replacement);
        assertEquals("identity_changed", XBusCableRoute.resolve(world, BlockPos.ZERO, SCAN, old.target()).code());
        replacement.device = reader.device; // Same gateway identity, different final Computer is still not the binding.
        assertEquals("identity_changed", XBusCableRoute.resolve(world, BlockPos.ZERO, SCAN, old.target()).code());
        assertEquals(before, reader.host.flowSnapshot());
        assertTrue(replacement.host.flowSnapshot().orElseThrow().receipts().isEmpty());
    }

    @Test void fixedRoutesClassifyFailuresAndLeaveBothWaitingComputersUntouched() {
        for (String fault : List.of("disconnected", "target_unloaded", "frontier_unloaded", "removed", "closed", "domain", "identity", "denied", "throwing")) {
            var world = new World(); var reader = new Peer(false); var writer = new Peer(true); world.providers.put(DESTINATION, reader);
            var old = XBusCableRoute.bind(world, BlockPos.ZERO, SCAN, "io", "x1").pin().orElseThrow();
            var left = writer.xbusAccess("io", writer.xbusPort("io", "x0").orElseThrow()).orElseThrow();
            switch (fault) {
                case "disconnected" -> world.connected = false;
                case "target_unloaded" -> world.unloaded = DESTINATION;
                case "frontier_unloaded" -> world.unloaded = new BlockPos(1, -1, 0);
                case "removed" -> world.providers.remove(DESTINATION);
                case "closed" -> reader.open = false;
                case "domain" -> reader.domain = "another_domain";
                case "identity" -> reader.device = UUID.randomUUID();
                case "denied" -> world.denied = new BlockPos(1, 0, 0);
                case "throwing" -> reader.throwing = true;
            }
            var expected = switch (fault) {
                case "disconnected" -> "unreachable";
                case "frontier_unloaded" -> "topology_unloaded";
                case "removed" -> "not_found";
                case "domain" -> "endpoint_changed";
                case "identity" -> "identity_changed";
                case "denied" -> "access_denied";
                case "throwing" -> "provider_failure";
                default -> fault;
            };
            var a = writer.host.flowSnapshot(); var b = reader.host.flowSnapshot();
            var result = XBusCableRoute.resolve(world, BlockPos.ZERO, SCAN, old.target());
            assertEquals(expected, result.code(), fault); assertTrue(result.pin().isEmpty(), fault);
            var rejected = left.exchange(old.access());
            assertFalse(Set.of(XBusExchange.Status.EXCHANGED, XBusExchange.Status.READABLE, XBusExchange.Status.PREPARED).contains(rejected), fault);
            assertEquals(a, writer.host.flowSnapshot(), fault); assertEquals(b, reader.host.flowSnapshot(), fault);
        }
    }

    @Test void providerCallbackDisconnectsBeforePublishingAuthorityAndResourceLimitsNeverYieldAPartialRoute() {
        var world = new World(); var reader = new Peer(false); world.providers.put(DESTINATION, reader);
        reader.onAccess = () -> world.connected = false;
        var before = reader.host.flowSnapshot();
        var rejected = XBusCableRoute.bind(world, BlockPos.ZERO, SCAN, "io", "x1");
        assertEquals("unreachable", rejected.code()); assertTrue(rejected.pin().isEmpty());
        assertEquals(before, reader.host.flowSnapshot());
        reader.onAccess = () -> {}; world.connected = true;
        assertEquals("topology_limit", XBusCableRoute.bind(world, BlockPos.ZERO, new CommunicationNetworkLimits(1, 16), "io", "x1").code());
        world.providers.put(new BlockPos(1, 1, 0), new Peer(false));
        assertEquals("endpoint_limit", XBusCableRoute.bind(world, BlockPos.ZERO, new CommunicationNetworkLimits(100, 1), "io", "x1").code());
        assertEquals(before, reader.host.flowSnapshot());
    }

    @Test void legacyProviderCannotAcquireAuthorityAndTargetCoordinatesAreImmutable() {
        var world = new World(); world.providers.put(DESTINATION, () -> Optional.of(new EndpointConfiguration("io", true)));
        assertEquals("unsupported", XBusCableRoute.bind(world, BlockPos.ZERO, SCAN, "io", "x1").code());
        var position = new BlockPos.MutableBlockPos(2, 0, 0);
        var target = new XBusCableTarget(position, "primary", "io", "x1", UUID.randomUUID(), UUID.randomUUID());
        position.set(99, 0, 0); assertEquals(DESTINATION, target.position());
        assertThrows(IllegalArgumentException.class, () -> new XBusCableTarget(DESTINATION, "", "io", "x1", UUID.randomUUID(), UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new XBusCableTarget(DESTINATION, "primary", "io", "", UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test void exchangeTimeProviderAndPeerCallbacksCannotInvalidateAnEarlierPhysicalCheck() {
        for (boolean routeFirst : List.of(false, true)) for (boolean changeReader : List.of(true, false)) for (String stage : List.of("guard", "limits")) {
            var world = new World(); var reader = new Peer(false); var writer = new Peer(true); world.providers.put(DESTINATION, reader);
            var route = XBusCableRoute.bind(world, BlockPos.ZERO, SCAN, "io", "x1").pin().orElseThrow();
            var local = writer.xbusAccess("io", writer.xbusPort("io", "x0").orElseThrow()).orElseThrow();
            var changed = changeReader ? reader : writer;
            if (stage.equals("guard")) changed.onGuard = () -> world.connected = false;
            else changed.onLimits = () -> world.connected = false;
            var a = writer.host.flowSnapshot(); var b = reader.host.flowSnapshot();
            assertEquals(XBusExchange.Status.UNAVAILABLE, routeFirst ? route.access().exchange(local) : local.exchange(route.access()),
                stage + " reader=" + changeReader + " routeFirst=" + routeFirst);
            assertEquals(a, writer.host.flowSnapshot()); assertEquals(b, reader.host.flowSnapshot());
        }
    }

    @Test void exchangeTimeProviderExceptionsDoNotLeakPrivateMessagesOrPublishContinuations() {
        for (boolean changeReader : List.of(true, false)) for (String stage : List.of("guard", "limits")) {
            var world = new World(); var reader = new Peer(false); var writer = new Peer(true); world.providers.put(DESTINATION, reader);
            var route = XBusCableRoute.bind(world, BlockPos.ZERO, SCAN, "io", "x1").pin().orElseThrow();
            var local = writer.xbusAccess("io", writer.xbusPort("io", "x0").orElseThrow()).orElseThrow();
            var changed = changeReader ? reader : writer;
            Runnable throwing = () -> { throw new IllegalStateException("private provider data"); };
            if (stage.equals("guard")) changed.onGuard = throwing; else changed.onLimits = throwing;
            var a = writer.host.flowSnapshot(); var b = reader.host.flowSnapshot();
            assertEquals(XBusExchange.Status.UNAVAILABLE, assertDoesNotThrow(() -> local.exchange(route.access())));
            assertEquals(a, writer.host.flowSnapshot()); assertEquals(b, reader.host.flowSnapshot());
        }
    }

    @Test void theSecondRoutesProviderCallbackCannotBreakTheFirstRoutesAlreadyCheckedPath() {
        var a = new World(); var b = new World(); var writer = new Peer(true); var reader = new Peer(false);
        a.providers.put(DESTINATION, writer); b.providers.put(DESTINATION, reader);
        var left = XBusCableRoute.bind(a, BlockPos.ZERO, SCAN, "io", "x0").pin().orElseThrow();
        var right = XBusCableRoute.bind(b, BlockPos.ZERO, SCAN, "io", "x1").pin().orElseThrow();
        reader.onPins = () -> a.connected = false;
        var beforeA = writer.host.flowSnapshot(); var beforeB = reader.host.flowSnapshot();
        assertEquals(XBusExchange.Status.UNAVAILABLE, left.access().exchange(right.access()));
        assertEquals(beforeA, writer.host.flowSnapshot()); assertEquals(beforeB, reader.host.flowSnapshot());
    }

    @Test void laterProviderCallbacksCannotRevokeAnEarlierRoutesGrantOrIdentityAndStillExchange() {
        for (String change : List.of("closed", "identity", "domain")) {
            var a = new World(); var b = new World(); var writer = new Peer(true); var reader = new Peer(false);
            a.providers.put(DESTINATION, writer); b.providers.put(DESTINATION, reader);
            var left = XBusCableRoute.bind(a, BlockPos.ZERO, SCAN, "io", "x0").pin().orElseThrow();
            var right = XBusCableRoute.bind(b, BlockPos.ZERO, SCAN, "io", "x1").pin().orElseThrow();
            reader.onPins = () -> {
                if (change.equals("closed")) writer.open = false;
                else if (change.equals("identity")) writer.device = UUID.randomUUID();
                else writer.domain = "changed";
            };
            var beforeA = writer.host.flowSnapshot(); var beforeB = reader.host.flowSnapshot();
            assertEquals(change.equals("closed") ? XBusExchange.Status.CLOSED : XBusExchange.Status.STALE,
                left.access().exchange(right.access()), change);
            assertEquals(beforeA, writer.host.flowSnapshot()); assertEquals(beforeB, reader.host.flowSnapshot());
        }
    }

    @Test void lateSourceContinuationChangesAreStaleAndAdditionalGuardsKeepThePhysicalBoundary() {
        var world = new World(); var reader = new Peer(false); var writer = new Peer(true); world.providers.put(DESTINATION, reader);
        var route = XBusCableRoute.bind(world, BlockPos.ZERO, SCAN, "io", "x1").pin().orElseThrow().access().guarded(Optional::empty);
        var local = writer.xbusAccess("io", writer.xbusPort("io", "x0").orElseThrow()).orElseThrow();
        var before = reader.host.flowSnapshot(); var oldFlow = writer.host.flowSnapshot().orElseThrow().state().flow();
        writer.onLimits = () -> {
            writer.host.reset(HOST, true); writer.host.setPaused(false, HOST, true); writer.host.tick(HOST, true);
        };
        assertEquals(XBusExchange.Status.STALE, local.exchange(route));
        assertNotEquals(oldFlow, writer.host.flowSnapshot().orElseThrow().state().flow());
        assertEquals(before, reader.host.flowSnapshot());
        writer.onLimits = () -> {};
        var fresh = writer.xbusAccess("io", writer.xbusPort("io", "x0").orElseThrow()).orElseThrow();
        world.connected = false;
        assertEquals(XBusExchange.Status.UNAVAILABLE, fresh.exchange(route));
        assertEquals(before, reader.host.flowSnapshot());
        assertTrue(writer.host.flowSnapshot().orElseThrow().receipts().isEmpty());
    }

    static final class Peer implements CommunicationEndpoint {
        XBusHistorySavedData history;
        final ComputerBlockHost host = new ComputerBlockHost(this::changed);
        UUID device = UUID.randomUUID(); boolean open = true, throwing; String domain = "primary"; int inboxCalls;
        Runnable onAccess = () -> {}, onGuard = () -> {}, onLimits = () -> {}, onPins = () -> {};
        Runnable onDirty = () -> {};
        private void changed() { onDirty.run(); }
        Peer(boolean writer) {
            host.installFlow(writer ? new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("write", "x0", "packet")))
                : new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", PACKET))), writer ? INPUTS : EMPTY, HOST, true);
            host.setPaused(false, HOST, true); host.tick(HOST, true);
        }
        UUID computer() { return host.flowSnapshot().orElseThrow().state().computer(); }
        public Optional<UUID> communicationIdentity() { if (throwing) throw new IllegalStateException("private provider data"); return Optional.of(device); }
        public String domain() { return domain; }
        public Optional<EndpointConfiguration> advertisement() { return Optional.of(new EndpointConfiguration("io", open)); }
        public List<String> xbusPins(String endpoint) { onPins.run(); return open && endpoint.equals("io") ? HOST.pins().xbus() : List.of(); }
        public Optional<XBusPortState> xbusPort(String endpoint, String pin) {
            return open && endpoint.equals("io") ? host.flowSnapshot().flatMap(saved -> XBusPortState.of(saved, HOST.pins(), pin)) : Optional.empty();
        }
        public Optional<XBusAccess> xbusAccess(String endpoint, XBusPortState expected) {
            onAccess.run();
            var identity = device; var originalDomain = domain;
            var access = new XBusAccess(host, expected, () -> { onLimits.run(); return HOST; }, () -> {
                onGuard.run();
                return !open ? Optional.of(XBusExchange.Status.CLOSED)
                    : xbusPort(endpoint, expected.pin()).filter(expected::equals).isEmpty() ? Optional.of(XBusExchange.Status.STALE) : Optional.empty();
            }).validated(() -> !open ? Optional.of(XBusExchange.Status.CLOSED)
                : !identity.equals(device) || !originalDomain.equals(domain) ? Optional.of(XBusExchange.Status.STALE) : Optional.empty());
            return Optional.of(history == null ? access : access.historical(history, () -> 4096, () -> 65536));
        }
        public AdmissionStatus receive(MessageEnvelope ignored) { inboxCalls++; return AdmissionStatus.ACCEPTED; }
    }
    static final class World implements XBusCableRoute.World {
        final Map<BlockPos, CommunicationEndpoint> providers = new HashMap<>();
        boolean connected = true; BlockPos unloaded, denied;
        public boolean loaded(BlockPos pos) { return !pos.equals(unloaded); }
        public CableDiscovery.Cell cell(BlockPos pos) {
            assertTrue(loaded(pos), "Never inspect an unloaded block");
            if (pos.equals(denied)) throw new CableDiscovery.DiscoveryException("access_denied");
            return new CableDiscovery.Cell(connected && pos.equals(new BlockPos(1, 0, 0)) ? CableDiscovery.Kind.CABLE
                : providers.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
        }
        public boolean acceptsCable(BlockPos pos, Direction side) { return !pos.equals(BlockPos.ZERO) || side == Direction.EAST; }
        public CommunicationEndpoint endpoint(BlockPos pos) { assertTrue(loaded(pos)); return providers.get(pos); }
    }
}
