package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterState.Side.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class ClusterRouterTest {
    static final UUID MEMBER = new UUID(0, 1), CALLER = new UUID(0, 2);
    static final ClusterConfiguration.Member WORKER = new ClusterConfiguration.Member(MEMBER, new BlockPos(5, 70, 9));
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(10000, 1000000, 1000000);
    static final ClusterLimits LIMITS = new ClusterLimits(8, new MailboxLimits(8, 8), 100000, ARCHIVE);

    @Test void alreadyConsumedOldReplyCanBeAcknowledgedAfterNewerReplyHighWaterMark() {
        var router = configured(); MessageEnvelope oldReply = null;
        for (int sequence = 0; sequence < 2; sequence++) {
            var request = request(CALLER, sequence, "client");
            router.receive(EXTERNAL, "calculate", request, LIMITS); router.acknowledge(router.outbound(LIMITS).orElseThrow(), LIMITS);
            var reply = reply(MEMBER, sequence, request); if (sequence == 0) oldReply = reply;
            assertEquals(AdmissionStatus.ACCEPTED, router.receive(INTERNAL, "result", reply, LIMITS));
            router.acknowledge(router.outbound(LIMITS).orElseThrow(), LIMITS);
        }
        var before = router.write();
        assertEquals(AdmissionStatus.STALE_REPLY, router.receive(INTERNAL, "result", oldReply, LIMITS));
        assertEquals(before, router.write()); assertTrue(router.outbound(LIMITS).isEmpty());
        assertEquals(AdmissionStatus.CLOSED, router.receive(EXTERNAL, "unknown", oldReply, LIMITS));
    }

    @Test void messageUsesExplicitMemberAndOutputWithoutRunningAnyProgram() {
        var router = configured(); var incoming = message(CALLER, 0);
        assertEquals(AdmissionStatus.ACCEPTED, router.receive(EXTERNAL, "calculate", incoming, LIMITS));
        var forward = router.outbound(LIMITS).orElseThrow();
        assertEquals(INTERNAL, forward.target().side()); assertEquals("worker", forward.target().port());
        assertEquals(WORKER, forward.target().member().orElseThrow()); assertEquals(incoming, forward.envelope());
        assertTrue(router.acknowledge(forward, LIMITS));
        assertEquals(AdmissionStatus.IDENTITY_CONFLICT, router.receive(INTERNAL, "result", message(CALLER, 1), LIMITS));
        assertEquals(AdmissionStatus.ACCEPTED, router.receive(INTERNAL, "result", message(MEMBER, 0), LIMITS));
        assertEquals(EXTERNAL, router.outbound(LIMITS).orElseThrow().target().side());
        assertEquals("display", router.outbound(LIMITS).orElseThrow().target().port());
        assertTrue(router.outbound(LIMITS).orElseThrow().target().member().isEmpty());
    }

    @Test void requestFreezesReturnPathAndPreservesSystemIdentityThroughReply() {
        var router = configured(); var request = request(CALLER, 0, "client");
        assertEquals(AdmissionStatus.ACCEPTED, router.receive(EXTERNAL, "calculate", request, LIMITS));
        var outgoing = router.outbound(LIMITS).orElseThrow();
        assertEquals(request.source(), outgoing.envelope().source()); assertEquals(request.eventId(), outgoing.envelope().eventId());
        assertEquals(request.sequence(), outgoing.envelope().sequence()); assertEquals(request.payload(), outgoing.envelope().payload());
        assertEquals(correlation(request), correlation(outgoing.envelope()));
        assertEquals("result", ((MessageProtocol.Request) outgoing.envelope().protocol()).context().returnEndpoint());
        assertTrue(router.acknowledge(outgoing, LIMITS));
        assertEquals("busy", router.configure(configuration("other"), LIMITS));
        assertEquals(AdmissionStatus.IDENTITY_CONFLICT, router.receive(INTERNAL, "result", reply(CALLER, 1, request), LIMITS));
        var reply = reply(MEMBER, 0, request);
        assertEquals(AdmissionStatus.ACCEPTED, router.receive(INTERNAL, "result", reply, LIMITS));
        var returned = router.outbound(LIMITS).orElseThrow();
        assertEquals("client", returned.target().port()); assertEquals(EXTERNAL, returned.target().side()); assertEquals(reply, returned.envelope());
        assertTrue(router.acknowledge(returned, LIMITS));
        assertEquals("configured", router.configure(configuration("other"), LIMITS));
        assertEquals(AdmissionStatus.STALE_REPLY, router.receive(INTERNAL, "result", reply(MEMBER, 1, request), LIMITS));
        assertTrue(router.outbound(LIMITS).isEmpty());
    }

    @Test void memberCanRequestOutsideAndResponseReturnsToItsPinnedIdentity() {
        var router = configured(); var request = request(MEMBER, 0, "worker");
        assertEquals(AdmissionStatus.ACCEPTED, router.receive(INTERNAL, "result", request, LIMITS));
        var outgoing = router.outbound(LIMITS).orElseThrow(); assertEquals("display", outgoing.target().port());
        assertEquals("calculate", ((MessageProtocol.Request) outgoing.envelope().protocol()).context().returnEndpoint());
        router.acknowledge(outgoing, LIMITS);
        assertEquals(AdmissionStatus.ACCEPTED, router.receive(EXTERNAL, "calculate", reply(CALLER, 0, request), LIMITS));
        var returned = router.outbound(LIMITS).orElseThrow();
        assertEquals(INTERNAL, returned.target().side()); assertEquals("worker", returned.target().port()); assertEquals(WORKER, returned.target().member().orElseThrow());
    }

    @Test void unknownDisabledAndWrongSidePortsDoNotAdmit() {
        var router = configured(); var before = router.write();
        assertEquals(AdmissionStatus.CLOSED, router.receive(EXTERNAL, "result", message(CALLER, 0), LIMITS));
        assertEquals(AdmissionStatus.CLOSED, router.receive(INTERNAL, "calculate", message(MEMBER, 0), LIMITS));
        assertEquals(before, router.write());
        var disabled = new ClusterConfiguration(Map.of("A", WORKER), Map.of("off", new ClusterConfiguration.Port("A", "worker", "result", "display", false)));
        assertEquals("configured", router.configure(disabled, LIMITS));
        assertEquals(AdmissionStatus.CLOSED, router.receive(EXTERNAL, "off", message(CALLER, 0), LIMITS)); assertTrue(router.outbound(LIMITS).isEmpty());
    }

    @Test void duplicatePortFenceAndReceiptSurviveAfterQueueIsAcknowledged() {
        var router = configured(); var message = message(CALLER, 0);
        assertEquals(AdmissionStatus.ACCEPTED, router.receive(EXTERNAL, "calculate", message, LIMITS));
        assertEquals(AdmissionStatus.DUPLICATE, router.receive(EXTERNAL, "calculate", message, LIMITS));
        assertEquals(AdmissionStatus.IDENTITY_CONFLICT, router.receive(EXTERNAL, "not-the-original-port", message, LIMITS));
        router.acknowledge(router.outbound(LIMITS).orElseThrow(), LIMITS);
        var loaded = new ClusterRouter(() -> {}); loaded.read(router.write());
        assertEquals(AdmissionStatus.DUPLICATE, loaded.receive(EXTERNAL, "calculate", message, LIMITS)); assertTrue(loaded.outbound(LIMITS).isEmpty());
    }

    @Test void oldAcknowledgementCannotRemoveNewHeadAndFailedTransferRetainsIt() {
        var router = configured(); router.receive(EXTERNAL, "calculate", message(CALLER, 0), LIMITS);
        var first = router.outbound(LIMITS).orElseThrow(); router.receive(EXTERNAL, "calculate", message(CALLER, 1), LIMITS);
        assertEquals(first, router.outbound(LIMITS).orElseThrow()); assertEquals("busy", router.configure(configuration("renamed"), LIMITS));
        assertTrue(router.acknowledge(first, LIMITS)); assertFalse(router.acknowledge(first, LIMITS));
        assertEquals(1, router.outbound(LIMITS).orElseThrow().envelope().sequence());
    }

    @Test void acceptedRequestAndThenQueuedReplyResumeFromActualNbtCopies() {
        var router = configured(); var request = request(CALLER, 0, "client"); router.receive(EXTERNAL, "calculate", request, LIMITS);
        var saved = router.write(); var restored = new ClusterRouter(() -> {}); restored.read(saved);
        ((CompoundTag) saved).putString("tampered", "ignored-copy");
        var forwarded = restored.outbound(LIMITS).orElseThrow(); assertEquals(WORKER, forwarded.target().member().orElseThrow());
        assertEquals("result", ((MessageProtocol.Request) forwarded.envelope().protocol()).context().returnEndpoint());
        restored.acknowledge(forwarded, LIMITS); var response = reply(MEMBER, 0, request);
        assertEquals(AdmissionStatus.ACCEPTED, restored.receive(INTERNAL, "result", response, LIMITS));
        var next = new ClusterRouter(() -> {}); next.read(restored.write());
        assertEquals("client", next.outbound(LIMITS).orElseThrow().target().port()); assertEquals(response, next.outbound(LIMITS).orElseThrow().envelope());
        assertEquals(AdmissionStatus.DUPLICATE, next.receive(INTERNAL, "result", response, LIMITS)); next.acknowledge(next.outbound(LIMITS).orElseThrow(), LIMITS);
        var finalLoad = new ClusterRouter(() -> {}); finalLoad.read(next.write());
        assertTrue(finalLoad.outbound(LIMITS).isEmpty()); assertEquals(AdmissionStatus.DUPLICATE, finalLoad.receive(INTERNAL, "result", response, LIMITS));
    }

    @Test void multipleCorrelationsKeepIndependentReturnPathsAcrossRestart() {
        var router = configured(); var older = request(CALLER, 0, "first"); var newer = request(CALLER, 1, "second");
        router.receive(EXTERNAL, "calculate", older, LIMITS); router.receive(EXTERNAL, "calculate", newer, LIMITS);
        router.acknowledge(router.outbound(LIMITS).orElseThrow(), LIMITS); router.acknowledge(router.outbound(LIMITS).orElseThrow(), LIMITS);
        var restored = new ClusterRouter(() -> {}); restored.read(router.write());
        assertEquals(AdmissionStatus.ACCEPTED, restored.receive(INTERNAL, "result", reply(MEMBER, 0, newer), LIMITS));
        assertEquals("second", restored.outbound(LIMITS).orElseThrow().target().port()); restored.acknowledge(restored.outbound(LIMITS).orElseThrow(), LIMITS);
        assertEquals(AdmissionStatus.ACCEPTED, restored.receive(INTERNAL, "result", reply(MEMBER, 1, older), LIMITS));
        assertEquals("first", restored.outbound(LIMITS).orElseThrow().target().port());
        assertEquals(correlation(older), ((MessageProtocol.Reply) restored.outbound(LIMITS).orElseThrow().envelope().protocol()).correlation());
    }

    @Test void queuePendingAndSourceCapsAreNotSilentlyEvicted() {
        var limits = new ClusterLimits(8, new MailboxLimits(1, 1), 100000, ARCHIVE); var router = configured(); var request = request(CALLER, 0, "client");
        assertEquals(AdmissionStatus.ACCEPTED, router.receive(EXTERNAL, "calculate", request, limits));
        assertEquals(AdmissionStatus.QUEUE_FULL, router.receive(EXTERNAL, "calculate", message(CALLER, 1), limits));
        router.acknowledge(router.outbound(limits).orElseThrow(), limits);
        assertEquals(AdmissionStatus.QUEUE_FULL, router.receive(EXTERNAL, "calculate", request(CALLER, 1, "next"), limits));
        assertEquals(AdmissionStatus.SOURCE_LIMIT, router.receive(EXTERNAL, "calculate", message(UUID.randomUUID(), 0), limits)); assertTrue(router.outbound(limits).isEmpty());
    }

    @Test void rejectedLargeAdmissionDoesNotPublishQueueReceiptOrPendingPath() {
        var dirty = new AtomicInteger(); var router = new ClusterRouter(dirty::incrementAndGet); var limits = new ClusterLimits(8, new MailboxLimits(8, 8), 2000, ARCHIVE);
        assertEquals("configured", router.configure(configuration("display"), limits)); var before = router.write(); int changes = dirty.get();
        var original = request(CALLER, 0, "client"); var large = new MessageEnvelope(original.source(), 0, original.eventId(), 0,
            new DataValue.RecordValue(Map.of("huge", new DataValue.TextValue("x".repeat(10000)))), original.protocol());
        assertEquals(AdmissionStatus.MEMORY_LIMIT, router.receive(EXTERNAL, "calculate", large, limits));
        assertEquals(before, router.write()); assertEquals(changes, dirty.get());
        assertEquals(AdmissionStatus.ACCEPTED, router.receive(EXTERNAL, "calculate", original, limits));
    }

    @Test void archiveFailureDoesNotTurnSuccessfulMemoryAdmissionIntoPartialSave() {
        var router = configured(); var original = message(CALLER, 0); var limits = new ClusterLimits(8, new MailboxLimits(8, 8), 100000, new ArchiveLimits(10000, 1500, 100000));
        var before = router.write(); var large = new MessageEnvelope(original.source(), 0, original.eventId(), 0,
            new DataValue.RecordValue(Map.of("large", new DataValue.TextValue("x".repeat(2000)))));
        assertEquals(AdmissionStatus.ARCHIVE_LIMIT, router.receive(EXTERNAL, "calculate", large, limits)); assertEquals(before, router.write());
        assertEquals(AdmissionStatus.ACCEPTED, router.receive(EXTERNAL, "calculate", original, limits));
    }

    @Test void invalidSaveIsPreservedAndNeverReadAsAnEmptyCluster() {
        var router = new ClusterRouter(() -> fail("invalid save cannot dirty owner")); router.read(StringTag.valueOf("future-format"));
        assertEquals(AdmissionStatus.UNAVAILABLE, router.receive(EXTERNAL, "calculate", message(CALLER, 0), LIMITS));
        assertEquals("unavailable", router.configure(configuration("display"), LIMITS));
        assertTrue(router.diagnostic().isPresent()); assertEquals(StringTag.valueOf("future-format"), router.write());
    }

    @Test void aliasesCannotDuplicateIdentityLocationOrInternalOutputNames() {
        var duplicateId = new ClusterConfiguration.Member(MEMBER, new BlockPos(7, 70, 9));
        assertThrows(IllegalArgumentException.class, () -> new ClusterConfiguration(Map.of("A", WORKER, "B", duplicateId), Map.of()));
        var duplicatePos = new ClusterConfiguration.Member(CALLER, WORKER.position());
        assertThrows(IllegalArgumentException.class, () -> new ClusterConfiguration(Map.of("A", WORKER, "B", duplicatePos), Map.of()));
        var p = new ClusterConfiguration.Port("A", "worker", "result", "display", true);
        assertThrows(IllegalArgumentException.class, () -> new ClusterConfiguration(Map.of("A", WORKER), Map.of("one", p, "two", p)));
        assertThrows(IllegalArgumentException.class, () -> new ClusterConfiguration(Map.of(), Map.of("one", p)));
        var mutable = new BlockPos.MutableBlockPos(1, 2, 3); var member = new ClusterConfiguration.Member(MEMBER, mutable);
        mutable.set(9, 9, 9); assertEquals(new BlockPos(1, 2, 3), member.position());
    }

    @Test void configurationCapRejectsWholeReplacement() {
        var router = configured(); var before = router.write(); var cfg = new ClusterConfiguration(Map.of("A", WORKER), Map.of(
            "one", new ClusterConfiguration.Port("A", "worker", "out1", "display", true),
            "two", new ClusterConfiguration.Port("A", "worker", "out2", "display", true)));
        assertEquals("configuration_limit", router.configure(cfg, new ClusterLimits(1, LIMITS.mailbox(), 100000, ARCHIVE))); assertEquals(before, router.write());
    }

    static ClusterRouter configured() { var router = new ClusterRouter(() -> {}); assertEquals("configured", router.configure(configuration("display"), LIMITS)); return router; }
    static ClusterConfiguration configuration(String external) { return new ClusterConfiguration(Map.of("A", WORKER), Map.of("calculate", new ClusterConfiguration.Port("A", "worker", "result", external, true))); }
    static MessageEnvelope message(UUID source, long sequence) { return new MessageEnvelope(source, sequence, UUID.randomUUID(), 0, new DataValue.RecordValue(Map.of("temperature", new DataValue.IntegerValue(21)))); }
    static MessageEnvelope request(UUID source, long sequence, String returnPort) {
        var original = message(source, sequence); return new MessageEnvelope(source, sequence, original.eventId(), 0, original.payload(),
            new MessageProtocol.Request(new RequestContext(new RequestCorrelation(source, original.eventId(), 0), returnPort)));
    }
    static RequestCorrelation correlation(MessageEnvelope request) { return ((MessageProtocol.Request) request.protocol()).context().correlation(); }
    static MessageEnvelope reply(UUID source, long sequence, MessageEnvelope request) {
        var message = message(source, sequence); return new MessageEnvelope(source, sequence, message.eventId(), 0, message.payload(), new MessageProtocol.Reply(correlation(request)));
    }
}
