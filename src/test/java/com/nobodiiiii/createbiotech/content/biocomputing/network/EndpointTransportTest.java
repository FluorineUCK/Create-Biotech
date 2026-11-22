package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class EndpointTransportTest {
    private static final CommunicationNetworkLimits SCAN = new CommunicationNetworkLimits(100, 16);
    private static final ComputerHostLimits HOST = new ComputerHostLimits(new ResourceLimits(100, 100_000, 1), new ArchiveLimits(100_000, 1_000_000, 10_000_000));

    @Test void playerEditedAndWireRoundTrippedSendReachesRealReceiverWithInspectableBackpressure() throws Exception {
        var document = com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerSendScreenTest.senderDocumentForIntegration("target", message().payload());
        var sender = new ComputerBlockHost(() -> {}); var receiver = new ComputerBlockHost(() -> {});
        sender.install(document.archive(), ComputerRuntime.MemoryPolicy.RESET, HOST, true);
        receiver.install(receiver(), ComputerRuntime.MemoryPolicy.RESET, HOST, true);
        var world = new World(); world.providers.put(new BlockPos(2, 0, 0), new CommunicationEndpoint() {
            public Optional<EndpointConfiguration> advertisement() { return Optional.of(new EndpointConfiguration("target", true)); }
            public AdmissionStatus receive(MessageEnvelope message) { return receiver.receive(message, HOST, true); }
        });
        sender.begin(new DataValue.RecordValue(Map.of()), HOST, true); finish(sender);
        var output = sender.outbound(HOST, true).orElseThrow();
        var envelope = new MessageEnvelope(sender.snapshot().orElseThrow().id(), output.sequence(), output.eventId(), output.ordinal(), output.payload());
        world.connected = false;
        var rejected = deliver(world, output.endpoint(), envelope);
        assertFalse(rejected.accepted()); assertEquals("not_found", rejected.code());
        var pending = communication(sender, Optional.of(rejected));
        assertEquals(new DataValue.IntegerValue(1), pending.field("outbox_remaining"));
        assertEquals(new DataValue.TextValue("target"), pending.field("next_endpoint"));
        assertEquals(new DataValue.IntegerValue(0), communication(receiver, Optional.empty()).field("inbox_count"));
        world.connected = true;
        assertTrue(deliver(world, output.endpoint(), envelope).accepted());
        assertEquals(new DataValue.IntegerValue(1), communication(receiver, Optional.empty()).field("inbox_count"));
        assertEquals(new DataValue.IntegerValue(0), receiver.snapshot().orElseThrow().machine().memory().field("count"));
        sender.acknowledge(output.eventId(), output.ordinal(), HOST, true);
        assertEquals(new DataValue.IntegerValue(0), communication(sender, Optional.empty()).field("outbox_remaining"));
        receiver.tick(HOST, true); finish(receiver);
        assertEquals(new DataValue.IntegerValue(7), receiver.snapshot().orElseThrow().machine().memory().field("count"));
    }

    private static DataValue.RecordValue communication(ComputerBlockHost host, Optional<EndpointTransport.Result> result) {
        return (DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.editor.CommunicationStatusView.of(
            host.snapshot(), HOST, host.loaded(), host.diagnostic(), result).field("communication");
    }

    @Test void genericSidedGatewayReceivesValuesWithoutComputerTypeOrSharedMemory() {
        var world = new World(); var peer = new Peer(); world.providers.put(new BlockPos(2, 0, 0), peer);
        var message = message(); var result = deliver(world, "target", message);
        assertTrue(result.accepted()); assertEquals(1, peer.received.size()); assertEquals(message, peer.received.getFirst());
        assertEquals(Set.of(Direction.WEST), world.reached);
        assertThrows(UnsupportedOperationException.class, () -> peer.received.getFirst().payload().fields().clear());
    }

    @Test void closedDisconnectedAmbiguousAndIncompleteTopologyNeverDeliver() {
        var world = new World(); var first = new Peer(); world.providers.put(new BlockPos(2, 0, 0), first);
        first.open = false; assertEquals("not_found", deliver(world, "target", message()).code()); first.open = true;
        world.connected = false; assertEquals("not_found", deliver(world, "target", message()).code()); world.connected = true;
        var second = new Peer(); world.providers.put(new BlockPos(1, 1, 0), second);
        assertEquals("ambiguous", deliver(world, "target", message()).code());
        world.unloaded = new BlockPos(1, -1, 0);
        assertEquals("topology_unloaded", deliver(world, "target", message()).code()); world.unloaded = null;
        assertEquals("endpoint_limit", EndpointTransport.deliver(world, BlockPos.ZERO, new CommunicationNetworkLimits(100, 1), "target", message()).code());
        assertEquals("topology_limit", EndpointTransport.deliver(world, BlockPos.ZERO, new CommunicationNetworkLimits(1, 16), "target", message()).code());
        assertTrue(first.received.isEmpty()); assertTrue(second.received.isEmpty());
    }

    @Test void targetRevalidationAndProviderErrorsDoNotAcknowledge() {
        var world = new World(); var peer = new Peer(); world.providers.put(new BlockPos(2, 0, 0), peer);
        peer.closeOnFirstRead = true;
        assertEquals("endpoint_changed", deliver(world, "target", message()).code()); assertTrue(peer.received.isEmpty());
        peer.closeOnFirstRead = false; peer.open = true; peer.throwReceive = true;
        var failed = deliver(world, "target", message()); assertEquals("provider_failure", failed.code()); assertFalse(failed.accepted());
        assertEquals("invalid_name", deliver(world, "", message()).code());
    }

    @Test void senderRetryAfterDurableAdmissionQueuesOnceAndReceiverRunsOnlyOnOwnTick() {
        var world = new World(); var sender = new ComputerBlockHost(() -> {}); var receiver = new ComputerBlockHost(() -> {});
        sender.install(sender(), ComputerRuntime.MemoryPolicy.RESET, HOST, true);
        receiver.install(receiver(), ComputerRuntime.MemoryPolicy.RESET, HOST, true);
        world.providers.put(new BlockPos(2, 0, 0), new CommunicationEndpoint() {
            public Optional<EndpointConfiguration> advertisement() { return Optional.of(new EndpointConfiguration("target", true)); }
            public AdmissionStatus receive(MessageEnvelope message) { return receiver.receive(message, HOST, true); }
        });
        sender.begin(new DataValue.RecordValue(Map.of()), HOST, true); finish(sender);
        var out = sender.outbound(HOST, true).orElseThrow();
        var envelope = new MessageEnvelope(sender.snapshot().orElseThrow().id(), out.sequence(), out.eventId(), out.ordinal(), out.payload());
        assertTrue(deliver(world, out.endpoint(), envelope).accepted());
        assertEquals(new DataValue.IntegerValue(0), receiver.snapshot().orElseThrow().machine().memory().field("count"));
        // Lost sender acknowledgement: retry the same durable head, including after recipient reload.
        var saved = receiver.write(); receiver.read(saved);
        assertEquals("duplicate", deliver(world, out.endpoint(), envelope).code());
        assertEquals(1, receiver.snapshot().orElseThrow().mailbox().queued().size());
        assertTrue(sender.acknowledge(out.eventId(), out.ordinal(), HOST, true)); assertTrue(sender.outbound(HOST, true).isEmpty());
        receiver.tick(HOST, true); finish(receiver);
        assertEquals(new DataValue.IntegerValue(7), receiver.snapshot().orElseThrow().machine().memory().field("count"));
        assertEquals("duplicate", deliver(world, out.endpoint(), envelope).code()); receiver.tick(HOST, true);
        assertEquals(new DataValue.IntegerValue(7), receiver.snapshot().orElseThrow().machine().memory().field("count"));
    }

    private static EndpointTransport.Result deliver(World world, String name, MessageEnvelope envelope) {
        return EndpointTransport.deliver(world, BlockPos.ZERO, SCAN, name, envelope);
    }
    private static MessageEnvelope message() { return new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, new DataValue.RecordValue(Map.of("count", new DataValue.IntegerValue(7)))); }
    private static ProgramArchive sender() {
        var graph = new ComputationGraph(Map.of(), Map.of("name", new ComputationGraph.Constant(new DataValue.TextValue("target")),
            "payload", new ComputationGraph.Constant(message().payload())), Map.of("endpoint", "name", "payload", "payload"));
        return new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of(), List.of(new ProgramDefinition.Send("endpoint", "payload"))), Map.of());
    }
    private static ProgramArchive receiver() {
        var graph = new ComputationGraph(Map.of("count", DataType.Scalar.INTEGER), Map.of("input", new ComputationGraph.Input("count")), Map.of("value", "input"));
        return new ProgramArchive(new ProgramDefinition(graph, Map.of("count", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))), List.of(new ProgramDefinition.Write("count", "value"))), Map.of());
    }
    private static void finish(ComputerBlockHost host) {
        for (int i = 0; host.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); host.tick(HOST, true);
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, host.snapshot().orElseThrow().machine().status());
    }
    private static class Peer implements CommunicationEndpoint {
        boolean open = true, closeOnFirstRead, throwReceive; final List<MessageEnvelope> received = new ArrayList<>();
        public Optional<EndpointConfiguration> advertisement() {
            if (!open) return Optional.empty();
            if (closeOnFirstRead) open = false;
            return Optional.of(new EndpointConfiguration("target", true));
        }
        public AdmissionStatus receive(MessageEnvelope message) {
            if (throwReceive) throw new IllegalStateException("private provider state");
            received.add(message); return AdmissionStatus.ACCEPTED;
        }
    }
    private static class World implements EndpointTransport.World {
        final Map<BlockPos, CommunicationEndpoint> providers = new HashMap<>();
        final Set<Direction> reached = new HashSet<>(); boolean connected = true; BlockPos unloaded;
        public boolean loaded(BlockPos pos) { return !pos.equals(unloaded); }
        public CableDiscovery.Cell cell(BlockPos pos) {
            assertTrue(loaded(pos));
            return new CableDiscovery.Cell(connected && pos.equals(new BlockPos(1, 0, 0)) ? CableDiscovery.Kind.CABLE
                : providers.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
        }
        public boolean acceptsCable(BlockPos pos, Direction side) {
            if (pos.equals(BlockPos.ZERO)) return side == Direction.EAST;
            reached.add(side); return true;
        }
        public CommunicationEndpoint endpoint(BlockPos pos) { assertTrue(loaded(pos)); return providers.get(pos); }
    }
}
