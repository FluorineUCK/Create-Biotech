package com.nobodiiiii.createbiotech.content.biocomputing.network;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationMachine;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class RequestReplyTransportTest {
    private static final UUID SOURCE = new UUID(0, 1), EVENT = new UUID(0, 2);
    private static final RequestCorrelation KEY = new RequestCorrelation(SOURCE, EVENT, 0);
    private static final DataValue.RecordValue PAYLOAD = new DataValue.RecordValue(Map.of("number", new DataValue.IntegerValue(7)));
    private static final ComputerHostLimits HOST = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(RequestReplyFixtures.HARDWARE, new ArchiveLimits(100_000, 1_000_000, 10_000_000)));

    @Test void transportAddsPhysicalReturnNameOutsideThePlayersPayload() {
        var output = new OutboundMessage(EVENT, 0, "gateway", PAYLOAD, 3, new OutboundProtocol.Request(KEY));
        var wrapped = EndpointTransport.envelope(SOURCE, "source literal name", output);
        assertEquals(new MessageProtocol.Request(new RequestContext(KEY, "source literal name")), wrapped.protocol());
        assertEquals(PAYLOAD, wrapped.payload()); assertEquals(3, wrapped.sequence());
        assertThrows(IllegalArgumentException.class, () -> EndpointTransport.envelope(SOURCE, "", output));
        assertThrows(IllegalArgumentException.class, () -> EndpointTransport.envelope(SOURCE, "x".repeat(EndpointConfiguration.MAX_NAME_LENGTH + 1), output));
        assertThrows(IllegalArgumentException.class, () -> EndpointTransport.envelope(SOURCE, "\uD800", output));
        assertThrows(IllegalArgumentException.class, () -> EndpointTransport.envelope(new UUID(0, 3), "source", output));
        var reply = new OutboundMessage(new UUID(0, 4), 1, "source literal name", PAYLOAD, 8, new OutboundProtocol.Reply(KEY));
        assertEquals(new MessageProtocol.Reply(KEY), EndpointTransport.envelope(new UUID(0, 3), null, reply).protocol());
        var ordinary = new OutboundMessage(EVENT, 0, "gateway", PAYLOAD, 3);
        assertEquals(MessageProtocol.Message.INSTANCE, EndpointTransport.envelope(SOURCE, null, ordinary).protocol());
    }

    @Test void aGenericGatewayReceivesTheSameRequestHeaderWithoutComputerOrClusterState() {
        var received = new ArrayList<MessageEnvelope>();
        var gateway = new CommunicationEndpoint() {
            public Optional<EndpointConfiguration> advertisement() { return Optional.of(new EndpointConfiguration("gateway", true)); }
            public AdmissionStatus receive(MessageEnvelope message) { received.add(message); return AdmissionStatus.ACCEPTED; }
        };
        EndpointTransport.World world = new EndpointTransport.World() {
            public boolean loaded(BlockPos pos) { return true; }
            public CableDiscovery.Cell cell(BlockPos pos) {
                return new CableDiscovery.Cell(pos.equals(new BlockPos(1, 0, 0)) ? CableDiscovery.Kind.CABLE
                    : pos.equals(new BlockPos(2, 0, 0)) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
            }
            public boolean acceptsCable(BlockPos pos, Direction side) { return side == (pos.equals(BlockPos.ZERO) ? Direction.EAST : Direction.WEST); }
            public CommunicationEndpoint endpoint(BlockPos pos) { return gateway; }
        };
        var output = new OutboundMessage(EVENT, 0, "gateway", PAYLOAD, 3, new OutboundProtocol.Request(KEY));
        var envelope = EndpointTransport.envelope(SOURCE, "origin", output);
        assertTrue(EndpointTransport.deliver(world, BlockPos.ZERO, new CommunicationNetworkLimits(100, 16), "gateway", envelope).accepted());
        assertEquals(List.of(envelope), received);
        assertThrows(UnsupportedOperationException.class, () -> received.getFirst().payload().fields().clear());
    }

    @Test void repeatedDuplexQueriesUsePhysicalDiscoverySurviveReloadAndDoNotAcceptOldResults() throws Exception {
        var client = new ComputerBlockHost(() -> {}); var server = new ComputerBlockHost(() -> {});
        var document = com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerSendScreenTest.requesterDocumentForIntegration();
        client.install(document.archive(), ComputerRuntime.MemoryPolicy.RESET, HOST, true);
        server.install(RequestReplyFixtures.server(), ComputerRuntime.MemoryPolicy.RESET, HOST, true);
        var world = new DuplexWorld(client, server);
        MessageEnvelope oldReply = null;
        for (int query = 1; query <= 20; query++) {
            client.begin(RequestReplyFixtures.input("query", query), HOST, true); finish(client);
            var request = client.outbound(HOST, true).orElseThrow();
            var envelope = EndpointTransport.envelope(client.snapshot().orElseThrow().id(), "client", request);
            var before = server.write();
            world.connected = false;
            assertFalse(deliver(world, BlockPos.ZERO, request.endpoint(), envelope).accepted());
            assertEquals(before, server.write());
            world.connected = true; world.server.open = false;
            assertFalse(deliver(world, BlockPos.ZERO, request.endpoint(), envelope).accepted());
            assertEquals(before, server.write()); world.server.open = true;
            assertTrue(deliver(world, BlockPos.ZERO, request.endpoint(), envelope).accepted());
            // Lose ACK and unload/reload: a retry remains one queued request and no synchronous execution.
            server.read(server.write());
            assertEquals(AdmissionStatus.DUPLICATE, deliver(world, BlockPos.ZERO, request.endpoint(), envelope).admission().orElseThrow());
            assertEquals(1, server.snapshot().orElseThrow().mailbox().queued().size());
            client.acknowledge(request.eventId(), request.ordinal(), HOST, true);
            server.tick(HOST, true); finish(server);
            var output = server.outbound(HOST, true).orElseThrow();
            var reply = EndpointTransport.envelope(server.snapshot().orElseThrow().id(), "server", output);
            if (oldReply != null) {
                var saved = client.write();
                assertEquals(AdmissionStatus.STALE_REPLY, deliver(world, new BlockPos(2, 0, 0), "client", oldReply).admission().orElseThrow());
                assertEquals(saved, client.write());
            }
            assertTrue(deliver(world, new BlockPos(2, 0, 0), output.endpoint(), reply).accepted());
            server.acknowledge(output.eventId(), output.ordinal(), HOST, true);
            client.read(client.write()); client.tick(HOST, true); finish(client);
            assertEquals(new DataValue.IntegerValue(query), client.snapshot().orElseThrow().machine().memory().field("result"));
            assertTrue(client.snapshot().orElseThrow().rpc().pending().isEmpty());
            oldReply = reply;
        }
        assertNotEquals(client.snapshot().orElseThrow().id(), server.snapshot().orElseThrow().id());
    }

    @Test void renamingAfterAdmissionDoesNotRetargetTheFrozenRequestOrItsRetry() {
        var client = new ComputerBlockHost(() -> {}); var server = new ComputerBlockHost(() -> {});
        client.install(RequestReplyFixtures.client(), ComputerRuntime.MemoryPolicy.RESET, HOST, true);
        server.install(RequestReplyFixtures.server(), ComputerRuntime.MemoryPolicy.RESET, HOST, true);
        var world = new DuplexWorld(client, server);
        client.begin(RequestReplyFixtures.input("query", 7), HOST, true); finish(client);
        var output = client.outbound(HOST, true).orElseThrow(); var source = client.snapshot().orElseThrow().id();
        assertTrue(deliver(world, BlockPos.ZERO, "server", EndpointTransport.envelope(source, "client", output)).accepted());
        world.client.name = "renamed";
        assertEquals(AdmissionStatus.DUPLICATE,
            deliver(world, BlockPos.ZERO, "server", EndpointTransport.envelope(source, "renamed", output)).admission().orElseThrow());
        client.acknowledge(output.eventId(), output.ordinal(), HOST, true);
        server.tick(HOST, true); finish(server);
        var reply = server.outbound(HOST, true).orElseThrow(); assertEquals("client", reply.endpoint());
        var envelope = EndpointTransport.envelope(server.snapshot().orElseThrow().id(), "server", reply);
        assertEquals("not_found", deliver(world, new BlockPos(2, 0, 0), reply.endpoint(), envelope).code());
        assertEquals(reply, server.outbound(HOST, true).orElseThrow());
        world.client.name = "client";
        assertTrue(deliver(world, new BlockPos(2, 0, 0), reply.endpoint(), envelope).accepted());
        client.tick(HOST, true); finish(client);
        assertEquals(new DataValue.IntegerValue(7), client.snapshot().orElseThrow().machine().memory().field("result"));
    }

    private static EndpointTransport.Result deliver(DuplexWorld world, BlockPos origin, String name, MessageEnvelope envelope) {
        return EndpointTransport.deliver(world, origin, new CommunicationNetworkLimits(100, 16), name, envelope);
    }
    private static void finish(ComputerBlockHost host) {
        for (int i = 0; host.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); host.tick(HOST, true);
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, host.snapshot().orElseThrow().machine().status());
    }
    private static final class Endpoint implements CommunicationEndpoint {
        String name; final ComputerBlockHost host; boolean open = true;
        Endpoint(String name, ComputerBlockHost host) { this.name = name; this.host = host; }
        public Optional<EndpointConfiguration> advertisement() { return Optional.of(new EndpointConfiguration(name, open)); }
        public AdmissionStatus receive(MessageEnvelope envelope) { return open ? host.receive(envelope, HOST, true) : AdmissionStatus.CLOSED; }
    }
    private static final class DuplexWorld implements EndpointTransport.World {
        final Endpoint client, server; boolean connected = true;
        DuplexWorld(ComputerBlockHost client, ComputerBlockHost server) {
            this.client = new Endpoint("client", client); this.server = new Endpoint("server", server);
        }
        public boolean loaded(BlockPos pos) { return true; }
        public CableDiscovery.Cell cell(BlockPos pos) {
            return new CableDiscovery.Cell(connected && pos.equals(new BlockPos(1, 0, 0)) ? CableDiscovery.Kind.CABLE
                : pos.equals(BlockPos.ZERO) || pos.equals(new BlockPos(2, 0, 0)) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
        }
        public boolean acceptsCable(BlockPos pos, Direction side) { return side == (pos.equals(BlockPos.ZERO) ? Direction.EAST : Direction.WEST); }
        public CommunicationEndpoint endpoint(BlockPos pos) { return pos.equals(BlockPos.ZERO) ? client : server; }
    }
}
