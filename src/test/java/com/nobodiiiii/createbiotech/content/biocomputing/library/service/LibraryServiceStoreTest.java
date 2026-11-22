package com.nobodiiiii.createbiotech.content.biocomputing.library.service;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryReadServiceTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class LibraryServiceStoreTest {
    static final LibraryServiceLimits LIMITS = new LibraryServiceLimits(100000, 8, new MailboxLimits(2, 2), new ArchiveLimits(10000, 100000, 1000000));
    static final UUID CALLER = UUID.randomUUID();
    static MessageEnvelope request(long seq) {
        UUID event = UUID.randomUUID();
        return new MessageEnvelope(CALLER, seq, event, 0, obj("op", "read", "volume", "日志", "entry", "reading"),
            new MessageProtocol.Request(new RequestContext(new RequestCorrelation(CALLER, event, 0), "client")));
    }
    static LibraryServiceStore configured() {
        var store = new LibraryServiceStore(() -> {});
        assertEquals("configured", store.configure(0, config(), LIMITS)); return store;
    }
    @Test void admissionIsDurableDeduplicatedAndNeverReadsBeforeTick() {
        var store = configured(); var req = request(0);
        assertEquals(AdmissionStatus.ACCEPTED, store.receive(req, LIMITS));
        var restored = new LibraryServiceStore(() -> {}); restored.read(store.write());
        assertEquals(AdmissionStatus.DUPLICATE, restored.receive(req, LIMITS));
        assertEquals(1, restored.snapshot(LIMITS).orElseThrow().mailbox().queued().size());
        assertTrue(restored.outbound(LIMITS).isEmpty());
        var source = new Source(); assertEquals("ready", restored.tick(source, LIMITS));
        assertEquals(1, source.reads);
        var reply = restored.outbound(LIMITS).orElseThrow();
        assertEquals("client", reply.endpoint());
        assertEquals(((MessageProtocol.Request)req.protocol()).context().correlation(), ((MessageProtocol.Reply)reply.envelope().protocol()).correlation());
        assertEquals("ok", code(reply.envelope().payload()));
    }
    @Test void materializedReplySurvivesReloadAndDoesNotRereadMovedMedia() {
        var store = configured(); var req = request(0); store.receive(req, LIMITS); store.tick(new Source(), LIMITS);
        var pending = store.outbound(LIMITS).orElseThrow(); var restored = new LibraryServiceStore(() -> {}); restored.read(store.write());
        var source = new Source(); source.failure = "missing_volume";
        assertEquals("ready", restored.tick(source, LIMITS)); assertEquals(0, source.snapshots);
        assertEquals(pending, restored.outbound(LIMITS).orElseThrow());
        assertTrue(restored.acknowledge(pending, LIMITS)); assertFalse(restored.acknowledge(pending, LIMITS));
        var afterAck = new LibraryServiceStore(() -> {}); afterAck.read(restored.write());
        assertEquals(AdmissionStatus.DUPLICATE, afterAck.receive(req, LIMITS));
        assertTrue(afterAck.outbound(LIMITS).isEmpty());
    }
    @Test void backpressureAndUnsupportedProtocolsNeverDiscardAcceptedWork() {
        var store = configured(); var one = request(0); store.receive(one, LIMITS); store.receive(request(1), LIMITS);
        var before = store.write();
        assertEquals(AdmissionStatus.QUEUE_FULL, store.receive(request(2), LIMITS)); assertEquals(before, store.write());
        assertEquals(AdmissionStatus.UNSUPPORTED, store.receive(new MessageEnvelope(CALLER, 3, UUID.randomUUID(), 0, obj()), LIMITS));
        assertEquals(before, store.write());
        store.tick(new Source(), LIMITS); assertEquals(2, store.snapshot(LIMITS).orElseThrow().mailbox().queued().size());
    }
    @Test void ownerConfigurationUsesRevisionAndBusyFenceButCanPauseAndResume() {
        var store = configured();
        assertEquals("changed", store.configure(0, LibraryServiceConfiguration.closed(), LIMITS));
        store.receive(request(0), LIMITS); var before = store.write();
        assertEquals("busy", store.configure(1, new LibraryServiceConfiguration(config().endpoint(), Map.of()), LIMITS));
        assertEquals(before, store.write());
        var paused = new LibraryServiceConfiguration(Optional.of(new EndpointConfiguration("books", false)), config().grants());
        assertEquals("configured", store.configure(1, paused, LIMITS));
        assertEquals(AdmissionStatus.CLOSED, store.receive(request(1), LIMITS));
        assertEquals("closed", store.tick(new Source(), LIMITS)); assertTrue(store.outbound(LIMITS).isEmpty());
        assertEquals("configured", store.configure(2, config(), LIMITS));
        assertEquals("ready", store.tick(new Source(), LIMITS));
    }
    @Test void oversizedResponsesAreExplicitFailuresNotTruncatedValues() {
        var store = configured(); store.receive(request(0), LIMITS);
        var source = new Source();
        source.entries = Map.of("reading", new com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryEntry(
            com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryEntry.Kind.DATA, text("x".repeat(110000))));
        assertEquals("ready", store.tick(source, LIMITS));
        assertEquals(obj("code", "response_too_large", "data", DataValue.NullValue.INSTANCE), store.outbound(LIMITS).orElseThrow().envelope().payload());
        assertEquals(1, store.snapshot(LIMITS).orElseThrow().mailbox().queued().size());
    }
    @Test void invalidSaveAndReducedLimitsRetainOriginalRawState() {
        var store = configured(); store.receive(request(0), LIMITS); var before = store.write();
        var tiny = new LibraryServiceLimits(1, 8, LIMITS.mailbox(), LIMITS.archive());
        assertTrue(store.snapshot(tiny).isEmpty()); assertEquals(before, store.write());
        assertEquals(AdmissionStatus.UNAVAILABLE, store.receive(request(1), tiny));
        assertTrue(store.snapshot(LIMITS).isPresent());
        var bad = StringTag.valueOf("keep"); store.read(bad);
        assertTrue(store.snapshot(LIMITS).isEmpty()); assertEquals(bad, store.write());
    }
    @Test void sizeFailureBeforeAdmissionDoesNotAdvanceReceiptOrState() {
        var store = configured(); var before = store.write(); var original = request(0);
        var huge = new MessageEnvelope(original.source(), original.sequence(), original.eventId(), original.ordinal(),
            obj("data", text("z".repeat(110000))), original.protocol());
        assertEquals(AdmissionStatus.MEMORY_LIMIT, store.receive(huge, LIMITS)); assertEquals(before, store.write());
        assertEquals(AdmissionStatus.ACCEPTED, store.receive(original, LIMITS));
    }
}
