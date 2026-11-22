package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class ClusterConnectorTransportTest {
    private static final BlockPos TARGET = new BlockPos(2, 0, 0);
    private static final CommunicationNetworkLimits LIMITS = new CommunicationNetworkLimits(100, 16);

    @Test void closedConfiguredPortIsDiagnosedWithoutDiscoveryOrAdmission() {
        var world = new World();
        var peer = new Peer("external", "target") {
            public List<EndpointConfiguration> advertisements() {
                return List.of(new EndpointConfiguration("target", false));
            }
        };
        world.faces.put(new Face(TARGET, Direction.WEST), peer);
        var groups = (DataValue.RecordValue) PeerDiscovery.discover(world, BlockPos.ZERO, LIMITS).field("peers");
        assertTrue(groups.fields().isEmpty());
        var result = deliver(world, "target", message());
        assertEquals("closed", result.code());
        assertFalse(result.accepted());
        assertTrue(result.admission().isEmpty());
        assertTrue(peer.received.isEmpty());
        assertEquals("not_found", deliver(world, "unknown", message()).code());
    }

    @Test void disabledNamesDoNotShadowAnEnabledPeerOrHideEnabledAmbiguity() {
        var world = new World(); var closed = new Peer("closed", "target") {
            public List<EndpointConfiguration> advertisements() { return List.of(new EndpointConfiguration("target", false)); }
        };
        var open = new Peer("open", "target");
        world.faces.put(new Face(TARGET, Direction.WEST), closed);
        world.faces.put(new Face(TARGET, Direction.UP), open);
        assertTrue(deliver(world, "target", message()).accepted());
        assertEquals(1, open.received.size()); assertTrue(closed.received.isEmpty());
        var extra = new Peer("extra", "target");
        world.faces.put(new Face(new BlockPos(1, -1, 0), Direction.UP), extra);
        assertEquals("ambiguous", deliver(world, "target", message()).code());
        assertEquals(1, open.received.size()); assertTrue(extra.received.isEmpty());
    }

    @Test void openFaceOfOneDomainSurvivesClosedFaceDeduplicationInEitherOrder() {
        for (boolean westOpen : List.of(false, true)) {
            var world = new World();
            var closed = new Peer("primary", "target") {
                public List<EndpointConfiguration> advertisements() { return List.of(new EndpointConfiguration("target", false)); }
            };
            var open = new Peer("primary", "target");
            world.faces.put(new Face(TARGET, Direction.WEST), westOpen ? open : closed);
            world.faces.put(new Face(TARGET, Direction.UP), westOpen ? closed : open);
            assertTrue(deliver(world, "target", message()).accepted());
            assertEquals(1, open.received.size()); assertTrue(closed.received.isEmpty());
            var peers = (DataValue.RecordValue) PeerDiscovery.discover(world, BlockPos.ZERO, LIMITS).field("peers");
            assertEquals(new DataValue.BooleanValue(false), ((DataValue.RecordValue)peers.field("target")).field("ambiguous"));
        }
    }

    @Test void closedDiagnosticNeverHidesIncompleteTopologyOrRetainsAStaleGrant() {
        var world = new World();
        var peer = new Peer("primary", "target") {
            public List<EndpointConfiguration> advertisements() { return List.of(new EndpointConfiguration("target", false)); }
        };
        world.faces.put(new Face(TARGET, Direction.WEST), peer);
        assertEquals("topology_limit", EndpointTransport.deliver(world, BlockPos.ZERO, new CommunicationNetworkLimits(1, 16), "target", message()).code());
        assertTrue(peer.received.isEmpty());
        var open = new Peer("primary", "target"); var envelope = message();
        world.faces.put(new Face(TARGET, Direction.WEST), open);
        assertTrue(deliver(world, "target", envelope).accepted());
        world.faces.put(new Face(TARGET, Direction.WEST), peer);
        assertEquals("closed", deliver(world, "target", envelope).code());
        world.faces.clear(); assertEquals("not_found", deliver(world, "target", envelope).code());
        assertEquals(1, open.received.size()); assertTrue(peer.received.isEmpty());
    }

    @Test void addressedNameReachesTheSelectedLogicalPort() {
        var world = new World(); var peer = new Peer("external", "first", "second");
        world.faces.put(new Face(TARGET, Direction.WEST), peer);
        var envelope = message();
        assertTrue(deliver(world, "second", envelope).accepted());
        assertEquals(List.of("second"), peer.receivedPorts);
        assertEquals(List.of(envelope), peer.received);
        assertEquals(0, peer.legacyCalls);
    }

    @Test void differentFacesOfOneBlockDoNotUseTheFirstDiscoveredProvider() {
        var world = new World(); var external = new Peer("external", "public"); var internal = new Peer("internal", "private");
        world.faces.put(new Face(TARGET, Direction.WEST), external);
        world.faces.put(new Face(TARGET, Direction.UP), internal);
        assertEquals("accepted", deliver(world, "private", message()).code());
        assertEquals(List.of("private"), internal.receivedPorts);
        assertTrue(external.receivedPorts.isEmpty());
        assertEquals(0, external.legacyCalls);
    }

    @Test void sameDomainAndPortOnMultipleFacesIsOneLogicalDestination() {
        var world = new World(); var shared = new Peer("primary", "shared");
        world.faces.put(new Face(TARGET, Direction.WEST), shared);
        world.faces.put(new Face(TARGET, Direction.UP), shared);
        assertTrue(deliver(world, "shared", message()).accepted());
        assertEquals(List.of("shared"), shared.receivedPorts);
        var groups = (DataValue.RecordValue) PeerDiscovery.discover(world, BlockPos.ZERO, LIMITS).field("peers");
        var descriptor = (DataValue.RecordValue) groups.field("shared");
        assertEquals(new DataValue.BooleanValue(false), descriptor.field("ambiguous"));
        assertEquals(1, ((DataValue.ListValue) descriptor.field("endpoints")).values().size());
    }

    @Test void sameNameInDifferentDomainsRemainsAmbiguousEvenAtOnePosition() {
        var world = new World(); var external = new Peer("external", "shared"); var internal = new Peer("internal", "shared");
        world.faces.put(new Face(TARGET, Direction.WEST), external);
        world.faces.put(new Face(TARGET, Direction.UP), internal);
        assertEquals("ambiguous", deliver(world, "shared", message()).code());
        assertTrue(external.received.isEmpty()); assertTrue(internal.received.isEmpty());
        var groups = (DataValue.RecordValue) PeerDiscovery.discover(world, BlockPos.ZERO, LIMITS).field("peers");
        assertEquals(new DataValue.BooleanValue(true), ((DataValue.RecordValue) groups.field("shared")).field("ambiguous"));
    }

    @Test void logicalPortLimitCannotBeBypassedByUsingOnePhysicalBlock() {
        var world = new World(); var peer = new Peer("external", "one", "two", "three");
        world.faces.put(new Face(TARGET, Direction.WEST), peer);
        var limits = new CommunicationNetworkLimits(100, 2);
        assertEquals("endpoint_limit", EndpointTransport.deliver(world, BlockPos.ZERO, limits, "one", message()).code());
        var failure = assertThrows(PeerDiscovery.DiscoveryException.class, () -> PeerDiscovery.discover(world, BlockPos.ZERO, limits));
        assertEquals("endpoint_limit", failure.getMessage());
        assertTrue(peer.received.isEmpty()); assertEquals(0, peer.legacyCalls);
    }

    @Test void domainIsRevalidatedBeforeAdmission() {
        var world = new World(); var peer = new Peer("external", "target") {
            int reads;
            public String domain() { return ++reads == 1 ? "external" : "changed"; }
        };
        world.faces.put(new Face(TARGET, Direction.WEST), peer);
        assertEquals("endpoint_changed", deliver(world, "target", message()).code());
        assertTrue(peer.received.isEmpty()); assertEquals(0, peer.legacyCalls);
    }

    @Test void disabledAdvertisementsStillConsumeTheAggregateInspectionBudget() {
        var world = new World();
        for (var side : List.of(Direction.WEST, Direction.UP)) {
            world.faces.put(new Face(TARGET, side), new Peer(side.getName(), "one", "two", "three") {
                public List<EndpointConfiguration> advertisements() {
                    return ports.stream().map(port -> new EndpointConfiguration(port.name(), false)).toList();
                }
            });
        }
        var limits = new CommunicationNetworkLimits(100, 4);
        assertEquals("endpoint_limit", EndpointTransport.deliver(world, BlockPos.ZERO, limits, "one", message()).code());
        assertEquals("endpoint_limit", assertThrows(PeerDiscovery.DiscoveryException.class,
            () -> PeerDiscovery.discover(world, BlockPos.ZERO, limits)).getMessage());
    }

    @Test void playerDiscoveryListsAllPortsButDoesNotExposeTheOtherSideBehindTheEndpoint() {
        var world = new World(); var peer = new Peer("external", "one", "two");
        world.faces.put(new Face(TARGET, Direction.WEST), peer);
        world.faces.put(new Face(TARGET.east(2), Direction.WEST), new Peer("internal", "hidden"));
        world.cables.add(TARGET.east());
        var groups = (DataValue.RecordValue) PeerDiscovery.discover(world, BlockPos.ZERO, LIMITS).field("peers");
        assertEquals(Set.of("one", "two"), groups.fields().keySet());
        assertTrue(peer.received.isEmpty());
    }

    @Test void oldSingleEndpointProvidersRemainCompatibleAndPreserveRequestMetadata() {
        var world = new World(); var received = new ArrayList<MessageEnvelope>();
        world.faces.put(new Face(TARGET, Direction.WEST), new CommunicationEndpoint() {
            public Optional<EndpointConfiguration> advertisement() { return Optional.of(new EndpointConfiguration("legacy", true)); }
            public AdmissionStatus receive(MessageEnvelope message) { received.add(message); return AdmissionStatus.ACCEPTED; }
        });
        var source = UUID.randomUUID(); var event = UUID.randomUUID();
        var request = new MessageEnvelope(source, 7, event, 0, message().payload(),
            new MessageProtocol.Request(new RequestContext(new RequestCorrelation(source, event, 0), "return")));
        assertTrue(deliver(world, "legacy", request).accepted());
        assertEquals(List.of(request), received);
    }

    private static EndpointTransport.Result deliver(World world, String name, MessageEnvelope message) {
        return EndpointTransport.deliver(world, BlockPos.ZERO, LIMITS, name, message);
    }
    private static MessageEnvelope message() {
        return new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0,
            new DataValue.RecordValue(Map.of("name", new DataValue.TextValue("temperature"), "value", new DataValue.IntegerValue(21))));
    }
    private record Face(BlockPos position, Direction side) {}
    private static class Peer implements CommunicationEndpoint {
        final String domain; final List<EndpointConfiguration> ports;
        final List<String> receivedPorts = new ArrayList<>(); final List<MessageEnvelope> received = new ArrayList<>();
        int legacyCalls;
        Peer(String domain, String... names) { this.domain = domain; ports = Arrays.stream(names).map(name -> new EndpointConfiguration(name, true)).toList(); }
        public String domain() { return domain; }
        public List<EndpointConfiguration> advertisements() { return ports; }
        public Optional<EndpointConfiguration> advertisement() { return ports.stream().findFirst(); }
        public AdmissionStatus receive(String name, MessageEnvelope envelope) { receivedPorts.add(name); received.add(envelope); return AdmissionStatus.ACCEPTED; }
        public AdmissionStatus receive(MessageEnvelope envelope) { legacyCalls++; return AdmissionStatus.UNSUPPORTED; }
    }
    private static class World implements EndpointTransport.World, PeerDiscovery.DiscoveryWorld {
        final Map<Face, CommunicationEndpoint> faces = new LinkedHashMap<>();
        final Set<BlockPos> cables = new HashSet<>(List.of(new BlockPos(1, 0, 0), new BlockPos(1, 1, 0), new BlockPos(2, 1, 0)));
        public boolean loaded(BlockPos pos) { return true; }
        public boolean allowed(BlockPos pos) { return true; }
        public long gameTime() { return 12; }
        public String dimension() { return "minecraft:overworld"; }
        public CableDiscovery.Cell cell(BlockPos pos) {
            return new CableDiscovery.Cell(cables.contains(pos) ? CableDiscovery.Kind.CABLE
                : faces.keySet().stream().anyMatch(face -> face.position().equals(pos)) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
        }
        public boolean acceptsCable(BlockPos pos, Direction side) { return pos.equals(BlockPos.ZERO) ? side == Direction.EAST : faces.containsKey(new Face(pos, side)); }
        public CommunicationEndpoint endpoint(BlockPos pos) { return faces.entrySet().stream().filter(entry -> entry.getKey().position().equals(pos)).map(Map.Entry::getValue).findFirst().orElse(null); }
        public CommunicationEndpoint endpoint(BlockPos pos, Direction side) { return faces.get(new Face(pos, side)); }
    }
}
