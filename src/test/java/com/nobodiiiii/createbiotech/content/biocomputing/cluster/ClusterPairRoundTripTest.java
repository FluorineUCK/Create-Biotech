package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterState.Side.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.RequestReplyFixtures.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ComputerNbtCodec;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Two independent forwarding owners, not a ClusterEngineState or shared Computer RAM. */
class ClusterPairRoundTripTest {
    private static final ClusterLimits LIMITS = ClusterRouterTest.LIMITS;

    @Test void twoClustersPreserveOriginalCorrelationAndLatestReplyAcrossBothRoutingArchives() {
        var pair = new Pair();
        var requests = List.of(pair.submit(7), pair.submit(9));
        for (var original : requests) {
            var outbound = pair.left.outbound(LIMITS).orElseThrow();
            assertEquals("outside-worker", outbound.target().port());
            assertEquals(EXTERNAL, outbound.target().side());
            assertTrue(outbound.target().member().isEmpty());
            assertSameEnvelopeIdentity(original, outbound.envelope());
            assertEquals("outside-client", context(outbound.envelope()).returnEndpoint());
            assertEquals(context(original).correlation(), context(outbound.envelope()).correlation());
            assertEquals(AdmissionStatus.ACCEPTED, pair.right.receive(EXTERNAL, "outside-worker", outbound.envelope(), LIMITS));
            assertTrue(pair.left.acknowledge(outbound, LIMITS));
        }

        // Save both routers while the right-side forwarding queue and both return paths are live.
        pair.restore();
        assertEquals(2, pair.left.snapshot(LIMITS).orElseThrow().pending().size());
        assertEquals(2, pair.right.snapshot(LIMITS).orElseThrow().pending().size());
        for (var original : requests) {
            var outbound = pair.right.outbound(LIMITS).orElseThrow();
            assertEquals("worker", outbound.target().port());
            assertEquals(pair.worker.id(), outbound.target().member().orElseThrow().identity());
            assertSameEnvelopeIdentity(original, outbound.envelope());
            assertEquals(context(original).correlation(), context(outbound.envelope()).correlation());
            assertEquals("result", context(outbound.envelope()).returnEndpoint());
            assertEquals(AdmissionStatus.ACCEPTED, pair.worker.receive(outbound.envelope()));
            assertTrue(pair.right.acknowledge(outbound, LIMITS));
        }
        assertEquals(new DataValue.IntegerValue(100), pair.worker.memory().field("result"));
        assertEquals(new DataValue.IntegerValue(0), pair.caller.memory().field("result"));

        MessageEnvelope oldReply = null;
        for (long expected : List.of(7L, 9L)) {
            pair.worker.step(); finish(pair.worker);
            assertEquals(new DataValue.IntegerValue(expected), pair.worker.memory().field("result"));
            var output = pair.worker.outbound().orElseThrow();
            assertEquals("result", output.endpoint(), "The worker only knows its internal configured output");
            var response = reply(pair.worker.id(), output);
            if (oldReply == null) oldReply = response;
            assertEquals(AdmissionStatus.ACCEPTED, pair.right.receive(INTERNAL, output.endpoint(), response, LIMITS));
            acknowledge(pair.worker);

            // A disconnected physical link can leave this reply in the right router, not in the worker.
            pair.restore();
            var outward = pair.right.outbound(LIMITS).orElseThrow();
            assertEquals("outside-client", outward.target().port());
            assertEquals(response, outward.envelope(), "Replies retain source identity and correlation through both routers");
            assertEquals(AdmissionStatus.ACCEPTED, pair.left.receive(EXTERNAL, "outside-client", outward.envelope(), LIMITS));
            assertTrue(pair.right.acknowledge(outward, LIMITS));
            var inward = pair.left.outbound(LIMITS).orElseThrow();
            assertEquals("client", inward.target().port());
            assertEquals(pair.caller.id(), inward.target().member().orElseThrow().identity());
            assertEquals(response, inward.envelope());
            assertEquals(expected == 7 ? AdmissionStatus.STALE_REPLY : AdmissionStatus.ACCEPTED, pair.caller.receive(inward.envelope()));
            assertTrue(pair.left.acknowledge(inward, LIMITS));
            assertEquals(new DataValue.IntegerValue(0), pair.caller.memory().field("result"), "Admission never writes caller RAM");
        }
        pair.caller.step(); finish(pair.caller);
        assertEquals(new DataValue.IntegerValue(9), pair.caller.memory().field("result"));
        assertTrue(pair.caller.snapshot().rpc().pending().isEmpty());
        assertFalse(pair.left.snapshot(LIMITS).orElseThrow().busy());
        assertFalse(pair.right.snapshot(LIMITS).orElseThrow().busy());

        // Replaying the first reply after later source watermarks cannot resurrect any pending work.
        var leftBefore = pair.left.write(); var rightBefore = pair.right.write(); var callerBefore = pair.caller.snapshot();
        assertEquals(AdmissionStatus.STALE_REPLY, pair.right.receive(INTERNAL, "result", oldReply, LIMITS));
        assertEquals(AdmissionStatus.STALE_REPLY, pair.left.receive(EXTERNAL, "outside-client", oldReply, LIMITS));
        assertEquals(leftBefore, pair.left.write()); assertEquals(rightBefore, pair.right.write());
        assertEquals(callerBefore, pair.caller.snapshot());
        pair.restore();
        assertEquals(new DataValue.IntegerValue(9), pair.caller.memory().field("result"));
        assertTrue(pair.caller.snapshot().mailbox().queued().isEmpty());
    }

    @Test void privateNamesAndWrongIngressCannotReplaceTheExplicitPublicRoute() {
        var pair = new Pair(); pair.submit(13);
        var request = pair.left.outbound(LIMITS).orElseThrow();
        var before = pair.right.write();
        assertEquals(AdmissionStatus.CLOSED, pair.right.receive(EXTERNAL, "worker", request.envelope(), LIMITS));
        assertEquals(AdmissionStatus.CLOSED, pair.right.receive(EXTERNAL, "private-member", request.envelope(), LIMITS));
        assertEquals(AdmissionStatus.IDENTITY_CONFLICT, pair.right.receive(INTERNAL, "result", request.envelope(), LIMITS));
        assertEquals(before, pair.right.write());
        assertTrue(pair.right.snapshot(LIMITS).orElseThrow().pending().isEmpty());
        assertEquals(AdmissionStatus.ACCEPTED, pair.right.receive(EXTERNAL, "outside-worker", request.envelope(), LIMITS));
        assertEquals("worker", pair.right.outbound(LIMITS).orElseThrow().target().port());
    }

    private static void assertSameEnvelopeIdentity(MessageEnvelope expected, MessageEnvelope actual) {
        assertEquals(expected.source(), actual.source()); assertEquals(expected.sequence(), actual.sequence());
        assertEquals(expected.eventId(), actual.eventId()); assertEquals(expected.ordinal(), actual.ordinal());
        assertEquals(expected.payload(), actual.payload());
    }
    private static RequestContext context(MessageEnvelope envelope) {
        return ((MessageProtocol.Request) envelope.protocol()).context();
    }
    private static final class Pair {
        ComputerRuntime caller = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE);
        ComputerRuntime worker = new ComputerRuntime(UUID.randomUUID(), server(), HARDWARE);
        ClusterRouter left = new ClusterRouter(() -> {}), right = new ClusterRouter(() -> {});
        Pair() {
            assertEquals("configured", left.configure(new ClusterConfiguration(
                Map.of("private-caller", new ClusterConfiguration.Member(caller.id(), new BlockPos(1, 64, 1))),
                Map.of("outside-client", new ClusterConfiguration.Port("private-caller", "client", "server", "outside-worker", true))), LIMITS));
            assertEquals("configured", right.configure(new ClusterConfiguration(
                Map.of("private-member", new ClusterConfiguration.Member(worker.id(), new BlockPos(9, 64, 1))),
                Map.of("outside-worker", new ClusterConfiguration.Port("private-member", "worker", "result", "outside-client", true))), LIMITS));
        }
        MessageEnvelope submit(long value) {
            caller.begin(input("query", value)); finish(caller);
            var output = caller.outbound().orElseThrow(); var request = request(caller.id(), output, "client");
            assertEquals(AdmissionStatus.ACCEPTED, left.receive(INTERNAL, output.endpoint(), request, LIMITS));
            acknowledge(caller); return request;
        }
        void restore() {
            left = restoreRouter(left); right = restoreRouter(right);
            caller = restoreComputer(caller); worker = restoreComputer(worker);
        }
        private ClusterRouter restoreRouter(ClusterRouter before) {
            var after = new ClusterRouter(() -> {}); after.read(before.write());
            assertEquals(before.snapshot(LIMITS), after.snapshot(LIMITS)); return after;
        }
        private ComputerRuntime restoreComputer(ComputerRuntime before) {
            var after = ComputerRuntime.restore(ComputerNbtCodec.decode(ComputerNbtCodec.encode(before.snapshot(), LIMITS.archive()), LIMITS.archive()), HARDWARE);
            after.load(); assertEquals(before.snapshot(), after.snapshot()); return after;
        }
    }
}
