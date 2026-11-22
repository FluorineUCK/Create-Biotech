package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationMachine;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.PathNodeBuilder;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ValuePath;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PeerDiscoveryTest {
    @Test void physicalNetworkInputUsesOrdinaryPathNodesAndRemainsFrozenAfterDisconnection() {
        var world = new World();
        var limits = new CommunicationNetworkLimits(100, 16);
        var frozen = new DataValue.RecordValue(Map.of("network", PeerDiscovery.topology(world, BlockPos.ZERO, limits)));
        var path = new ValuePath("network", List.of()).key("cable_segments").index(0).key("cable_blocks");
        var workspace = new ProgramWorkspace();
        var insertion = PathNodeBuilder.insert(workspace.root(), frozen, path, 20, 20);
        assertTrue(workspace.root().nodes().stream().allMatch(node -> node.definition() instanceof ComputationGraph.Input
            || node.definition() instanceof ComputationGraph.Field || node.definition() instanceof ComputationGraph.Index));
        world.connected = false;
        var fresh = PeerDiscovery.topology(world, BlockPos.ZERO, limits);
        assertTrue(((DataValue.ListValue)fresh.field("cable_segments")).values().isEmpty());
        var machine = new ComputationMachine(workspace.snapshot().archive().compile(new ResourceLimits(100, 100000, 100)));
        machine.begin(frozen); machine.tick();
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
        assertEquals(new DataValue.IntegerValue(1), machine.report().output().orElseThrow().field(insertion.output()));
        assertEquals(new DataValue.IntegerValue(1), path.resolve(frozen));
        assertFalse(world.connected); // Evaluating a copy neither reconnects nor rereads the source.
    }

    @Test void physicalNetworkInputRejectsIncompleteOrDeniedTopologyInsteadOfReturningZeroSegments() {
        for (String fault : List.of("unloaded", "denied", "origin_denied", "topology_limit", "endpoint_limit")) {
            var world = new World();
            CommunicationEndpoint provider = () -> { fail("Physical input must not inspect protocol metadata"); return Optional.empty(); };
            world.providers.put(new BlockPos(2, 0, 0), provider);
            world.providers.put(new BlockPos(1, 1, 0), provider);
            if (fault.equals("unloaded")) world.unloaded = new BlockPos(1, -1, 0);
            if (fault.equals("denied")) world.denied = new BlockPos(2, 0, 0);
            if (fault.equals("origin_denied")) world.denied = BlockPos.ZERO;
            var limits = new CommunicationNetworkLimits(fault.equals("topology_limit") ? 1 : 100, fault.equals("endpoint_limit") ? 1 : 16);
            var failure = assertThrows(PeerDiscovery.DiscoveryException.class,
                () -> PeerDiscovery.topology(world, BlockPos.ZERO, limits), fault);
            assertEquals(fault.equals("unloaded") ? "topology_unloaded" : fault.endsWith("denied") ? "access_denied" : fault,
                failure.getMessage(), fault);
        }
    }

    @Test void programTopologySnapshotDoesNotReadEndpointProtocolOrPrivateExecutionMetadata() {
        var world = new World();
        world.providers.put(new BlockPos(2, 0, 0), () -> { fail("Physical snapshot must not invoke advertisements"); return Optional.empty(); });
        var view = PeerDiscovery.topology(world, BlockPos.ZERO, new CommunicationNetworkLimits(100, 16));
        assertEquals(Set.of("kind", "dimension", "game_time", "cable_segments"), view.fields().keySet());
        assertEquals(new DataValue.TextValue("biocomputing:physical_cable"), view.field("kind"));
        var segments = (DataValue.ListValue)view.field("cable_segments");
        assertEquals(1, segments.values().size());
        assertEquals(new DataValue.IntegerValue(1), ((DataValue.RecordValue)segments.values().getFirst()).field("connected_devices"));
        assertEquals(new DataValue.IntegerValue(42), view.field("game_time"));
    }

    @Test void playerDirectoryReportsPhysicalSegmentsWithoutOpeningClosedPeersOrExposingPins() {
        var world = new World();
        world.providers.put(new BlockPos(2, 0, 0), () -> Optional.of(new EndpointConfiguration("private", false)));
        world.providers.put(new BlockPos(1, 1, 0), () -> Optional.of(new EndpointConfiguration("public", true)));
        var view = PeerDiscovery.discover(world, BlockPos.ZERO, new CommunicationNetworkLimits(100, 16));
        assertEquals(Set.of("public"), ((DataValue.RecordValue)view.field("peers")).fields().keySet());
        var segments = assertInstanceOf(DataValue.ListValue.class, view.field("cable_segments"));
        assertEquals(1, segments.values().size());
        var segment = assertInstanceOf(DataValue.RecordValue.class, segments.values().getFirst());
        assertEquals(Set.of("source_faces", "cable_blocks", "connected_devices"), segment.fields().keySet());
        assertEquals(new DataValue.ListValue(List.of(new DataValue.TextValue("east"))), segment.field("source_faces"));
        assertEquals(new DataValue.IntegerValue(1), segment.field("cable_blocks"));
        assertEquals(new DataValue.IntegerValue(2), segment.field("connected_devices"));
    }

    @Test void cableRemovedByPinProviderDoesNotPublishAUsablePinDirectory() {
        var world = new World();
        world.providers.put(new BlockPos(2,0,0), new CommunicationEndpoint() {
            public Optional<EndpointConfiguration> advertisement() { return Optional.of(new EndpointConfiguration("public", true)); }
            public List<String> xbusPins(String name) { world.connected = false; return List.of("x1"); }
            public Optional<XBusPortState> xbusPort(String name, String pin) {
                return Optional.of(new XBusPortState(UUID.randomUUID(), UUID.randomUUID(), pin, 0, XBusPortState.Mode.READ, Optional.empty()));
            }
        });
        var failure = assertThrows(PeerDiscovery.DiscoveryException.class,
            () -> PeerDiscovery.discover(world, BlockPos.ZERO, new CommunicationNetworkLimits(100, 16)));
        assertEquals("endpoint_changed", failure.getMessage());
    }
    @Test void invalidOrChangingPinProvidersNeverProduceAPartialDirectory() {
        for (String fault : List.of("duplicate", "too_many", "empty", "long", "null", "private_failure", "withdrawn", "wrong_pin", "changed_list", "closed", "domain")) {
            var world = new World();
            world.providers.put(new BlockPos(1,1,0), () -> Optional.of(new EndpointConfiguration("valid", true)));
            world.providers.put(new BlockPos(2,0,0), new CommunicationEndpoint() {
                int reads; boolean changed;
                public Optional<EndpointConfiguration> advertisement() { return Optional.of(new EndpointConfiguration("public", !changed || !fault.equals("closed"))); }
                public String domain() { return changed && fault.equals("domain") ? "changed" : "primary"; }
                public List<String> xbusPins(String name) {
                    reads++;
                    return switch (fault) {
                        case "duplicate" -> List.of("x1", "x1");
                        case "too_many" -> java.util.stream.IntStream.range(0, 65).mapToObj(i -> "x" + i).toList();
                        case "empty" -> List.of("");
                        case "long" -> List.of("x".repeat(65));
                        case "null" -> null;
                        case "private_failure" -> throw new IllegalStateException("secret provider internals");
                        case "changed_list" -> reads == 1 ? List.of("x1") : List.of("x0");
                        default -> List.of("x1");
                    };
                }
                public Optional<XBusPortState> xbusPort(String name, String pin) {
                    changed = true;
                    if (fault.equals("withdrawn")) return Optional.empty();
                    return Optional.of(new XBusPortState(UUID.randomUUID(), UUID.randomUUID(), fault.equals("wrong_pin") ? "x0" : pin,
                        0, XBusPortState.Mode.IDLE, Optional.empty()));
                }
            });
            var failure = assertThrows(PeerDiscovery.DiscoveryException.class,
                () -> PeerDiscovery.discover(world, BlockPos.ZERO, new CommunicationNetworkLimits(100, 16)), fault);
            assertEquals(Set.of("withdrawn", "wrong_pin", "changed_list", "closed", "domain").contains(fault)
                ? "endpoint_changed" : "provider_failure", failure.getMessage(), fault);
        }
    }

    @Test void boundedCompleteTopologyAndPermissionPrecedePinInspection() {
        for (String fault : List.of("unloaded", "denied", "topology_limit", "endpoint_limit")) {
            var world = new World();
            CommunicationEndpoint provider = new CommunicationEndpoint() {
                public Optional<EndpointConfiguration> advertisement() { return Optional.of(new EndpointConfiguration("public", true)); }
                public List<String> xbusPins(String name) { fail("Incomplete or denied scan must not read pins"); return List.of(); }
            };
            world.providers.put(new BlockPos(2,0,0), provider); world.providers.put(new BlockPos(1,1,0), provider);
            if (fault.equals("unloaded")) world.unloaded = new BlockPos(1,-1,0);
            if (fault.equals("denied")) world.denied = new BlockPos(2,0,0);
            var limits = new CommunicationNetworkLimits(fault.equals("topology_limit") ? 1 : 100, fault.equals("endpoint_limit") ? 1 : 16);
            var failure = assertThrows(PeerDiscovery.DiscoveryException.class, () -> PeerDiscovery.discover(world, BlockPos.ZERO, limits));
            assertEquals(fault.equals("unloaded") ? "topology_unloaded" : fault.equals("denied") ? "access_denied" : fault, failure.getMessage());
        }
    }

    @Test void publicAliasesKeepOnlyTheirGrantedPinListsAndClosedAliasIsNotInspected() {
        var world = new World(); var physical = UUID.randomUUID(); var flow = UUID.randomUUID();
        world.providers.put(new BlockPos(2,0,0), new CommunicationEndpoint() {
            public Optional<EndpointConfiguration> advertisement() { return Optional.of(new EndpointConfiguration("a", true)); }
            public List<EndpointConfiguration> advertisements() { return List.of(new EndpointConfiguration("a", true), new EndpointConfiguration("b", true), new EndpointConfiguration("private", false)); }
            public List<String> xbusPins(String name) { assertNotEquals("private", name); return List.of("x1"); }
            public Optional<XBusPortState> xbusPort(String name, String pin) { return Optional.of(new XBusPortState(physical, flow, pin, 0, XBusPortState.Mode.PAUSED, Optional.empty())); }
        });
        var peers = (DataValue.RecordValue)PeerDiscovery.discover(world, BlockPos.ZERO, new CommunicationNetworkLimits(100, 16)).field("peers");
        assertEquals(Set.of("a", "b"), peers.fields().keySet());
        assertEquals(peers.field("a"), peers.field("b"));
        assertFalse(peers.toString().contains(physical.toString()));
    }
    @Test void revocationDuringFinalPinEnumerationCannotPublishTheOldGrant() {
        var world = new World();
        world.providers.put(new BlockPos(2,0,0), new CommunicationEndpoint() {
            boolean open = true; int reads;
            public Optional<EndpointConfiguration> advertisement() { return Optional.of(new EndpointConfiguration("public", open)); }
            public List<String> xbusPins(String name) { if (++reads == 2) open = false; return List.of("x1"); }
            public Optional<XBusPortState> xbusPort(String name, String pin) {
                return Optional.of(new XBusPortState(UUID.randomUUID(), UUID.randomUUID(), pin, 0, XBusPortState.Mode.READ, Optional.empty()));
            }
        });
        var failure = assertThrows(PeerDiscovery.DiscoveryException.class,
            () -> PeerDiscovery.discover(world, BlockPos.ZERO, new CommunicationNetworkLimits(100, 16)));
        assertEquals("endpoint_changed", failure.getMessage());
    }
    @Test void grantedXBusPinsAreFrozenNamesNotPayloadsOrImplicitConnections() {
        var world = new World(); var id = UUID.randomUUID(); var flow = UUID.randomUUID();
        world.providers.put(new BlockPos(2,0,0), new CommunicationEndpoint() {
            public Optional<EndpointConfiguration> advertisement() { return Optional.of(new EndpointConfiguration("public", true)); }
            public List<String> xbusPins(String name) { assertEquals("public", name); return List.of("x1"); }
            public Optional<XBusPortState> xbusPort(String name, String pin) {
                assertEquals("public", name); assertEquals("x1", pin);
                return Optional.of(new XBusPortState(id, flow, pin, 0, XBusPortState.Mode.WRITE, Optional.of("private source")));
            }
            public Optional<XBusAccess> xbusAccess(String name, XBusPortState expected) { fail("Discovery must not acquire transfer authority"); return Optional.empty(); }
        });
        var peers = (DataValue.RecordValue)PeerDiscovery.discover(world, BlockPos.ZERO, new CommunicationNetworkLimits(100, 16)).field("peers");
        var entry = (DataValue.RecordValue)((DataValue.ListValue)((DataValue.RecordValue)peers.field("public")).field("endpoints")).values().getFirst();
        assertEquals(new DataValue.ListValue(List.of(new DataValue.TextValue("x1"))), entry.field("xbus_pins"));
        assertEquals(Set.of("position", "xbus_pins"), entry.fields().keySet());
        world.providers.clear();
        assertEquals(new DataValue.ListValue(List.of(new DataValue.TextValue("x1"))), entry.field("xbus_pins"));
    }
    @Test void computerAndFutureGatewayUseSameReadOnlyContractWithLiteralDuplicateNames() {
        var world = new World();
        String name = "A.\u00a7b\n键";
        world.providers.put(new BlockPos(2,0,0), () -> Optional.of(new EndpointConfiguration(name, true)));
        world.providers.put(new BlockPos(1,1,0), () -> Optional.of(new EndpointConfiguration(name, true)));
        var result = PeerDiscovery.discover(world, BlockPos.ZERO, new CommunicationNetworkLimits(100, 16));
        var peers = (DataValue.RecordValue)result.field("peers");
        assertEquals(Set.of(name), peers.fields().keySet());
        var entry = (DataValue.RecordValue)peers.field(name);
        assertEquals(new DataValue.BooleanValue(true), entry.field("ambiguous"));
        var endpoints = (DataValue.ListValue)entry.field("endpoints");
        assertEquals(2, endpoints.values().size());
        assertEquals(Set.of("position"), ((DataValue.RecordValue)endpoints.values().getFirst()).fields().keySet());
        world.providers.clear();
        assertEquals(2, endpoints.values().size()); // Snapshot, not a live directory.
        assertTrue(((DataValue.RecordValue)PeerDiscovery.discover(world, BlockPos.ZERO,
            new CommunicationNetworkLimits(100,16)).field("peers")).fields().isEmpty());
    }
    @Test void closedProvidersStillCountAndIncompleteScanNeverCallsAdvertisements() {
        var world = new World();
        world.providers.put(new BlockPos(2,0,0), () -> { world.reads++; return Optional.empty(); });
        world.providers.put(new BlockPos(1,1,0), () -> { world.reads++; return Optional.of(new EndpointConfiguration("off",false)); });
        assertEquals("endpoint_limit", assertThrows(PeerDiscovery.DiscoveryException.class,
            () -> PeerDiscovery.discover(world, BlockPos.ZERO, new CommunicationNetworkLimits(100,1))).getMessage());
        assertEquals(0, world.reads);
        world.unloaded = new BlockPos(1,-1,0);
        assertEquals("topology_unloaded", assertThrows(PeerDiscovery.DiscoveryException.class,
            () -> PeerDiscovery.discover(world, BlockPos.ZERO, new CommunicationNetworkLimits(100,16))).getMessage());
        assertEquals(0, world.reads);
        world.unloaded = null;
        assertTrue(((DataValue.RecordValue)PeerDiscovery.discover(world, BlockPos.ZERO,
            new CommunicationNetworkLimits(100,16)).field("peers")).fields().isEmpty());
    }
    @Test void disconnectReconnectAndDeniedProviderNeverLeakMetadata() {
        var world = new World(); var target = new BlockPos(2,0,0);
        world.providers.put(target, () -> { world.reads++; return Optional.of(new EndpointConfiguration("gateway",true)); });
        world.denied = target;
        assertEquals("access_denied", assertThrows(PeerDiscovery.DiscoveryException.class,
            () -> PeerDiscovery.discover(world, BlockPos.ZERO, new CommunicationNetworkLimits(100,16))).getMessage());
        assertEquals(0, world.reads); world.denied = null; world.connected = false;
        assertTrue(((DataValue.RecordValue)PeerDiscovery.discover(world, BlockPos.ZERO,
            new CommunicationNetworkLimits(100,16)).field("peers")).fields().isEmpty());
        world.connected = true;
        assertEquals(Set.of("gateway"), ((DataValue.RecordValue)PeerDiscovery.discover(world, BlockPos.ZERO,
            new CommunicationNetworkLimits(100,16)).field("peers")).fields().keySet());
    }
    @Test void providerFailureIsExplicitNotPartialOrPrivateExceptionText() {
        var world = new World(); world.providers.put(new BlockPos(2,0,0), () -> { throw new IllegalStateException("private"); });
        assertEquals("provider_failure", assertThrows(PeerDiscovery.DiscoveryException.class,
            () -> PeerDiscovery.discover(world, BlockPos.ZERO, new CommunicationNetworkLimits(100,16))).getMessage());
    }
    private static final class World implements PeerDiscovery.DiscoveryWorld {
        final Map<BlockPos,CommunicationEndpoint> providers = new HashMap<>();
        BlockPos unloaded, denied; boolean connected = true; int reads;
        public boolean loaded(BlockPos pos) { return !pos.equals(unloaded); }
        public boolean allowed(BlockPos pos) { return !pos.equals(denied); }
        public CableDiscovery.Cell cell(BlockPos pos) {
            assertTrue(loaded(pos)); assertTrue(allowed(pos));
            return new CableDiscovery.Cell(connected && pos.equals(new BlockPos(1,0,0)) ? CableDiscovery.Kind.CABLE
                : providers.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
        }
        public CommunicationEndpoint endpoint(BlockPos pos) { assertTrue(loaded(pos)); assertTrue(allowed(pos)); return providers.get(pos); }
        public long gameTime() { return 42; }
        public String dimension() { return "minecraft:overworld"; }
    }
}
