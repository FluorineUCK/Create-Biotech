package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookTransactionTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.*;

class LibraryRemoteTransactionTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    static final LibraryServiceLimits LIMITS = new LibraryServiceLimits(100000, 8, new MailboxLimits(8, 8), LibraryBookMediaTest.LIMITS);
    final UUID epoch = UUID.randomUUID();
    LibraryWriteBatch tick(long tick) { return new LibraryWriteBatch(epoch, tick); }
    final LibraryBookMedia media = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS);
    final LibraryBookAccessTest.View view = new LibraryBookAccessTest.View();
    final LibraryBookReadSource source = new LibraryBookReadSource(new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES), () -> Set.of(BlockPos.ZERO));
    LibraryServiceStore configured() {
        view.book = LibraryBookVolumesTest.VOLUMES.rename(media.upgrade(LibraryBookVersionsTest.legacy()), "日志");
        var store = new LibraryServiceStore(() -> {});
        assertEquals("configured", store.configure(0, config(true), LIMITS)); return store;
    }
    LibraryServiceConfiguration config(boolean enabled) {
        return new LibraryServiceConfiguration(Optional.of(new EndpointConfiguration("books", enabled)), Map.of("日志", LibraryServiceConfiguration.Access.READ_WRITE));
    }
    static String code(LibraryServiceStore store) {
        return ((DataValue.TextValue)store.outbound(LIMITS).orElseThrow().envelope().payload().field("code")).value();
    }
    @Test void closedLegacyPortRetainsItsCompetitionWithTheSlowDeviceAndPreparationIsSideEffectFree() {
        var store = configured();
        var message = request(0, put(media.stat(view.book, "reading").field("version"), "reading", 44));
        store.receive(message, LIMITS, tick(1)); store.configure(1, config(false), LIMITS);
        var before = store.write();
        var target = new LibraryWriteBatch.Target(tick(1), "日志", "reading");
        var batch = store.prepareBatch(source, LIMITS, tick(2), Optional.of(target));
        assertTrue(batch.competesWith(target)); assertTrue(batch.current());
        assertEquals(before, store.write()); assertEquals(0, view.writes);
        batch.publishWithoutCallback();
        var restored = new LibraryServiceStore(() -> {}); restored.read(store.write());
        assertEquals("conflict", restored.snapshot(LIMITS).orElseThrow().writes().get(LibraryMutation.Receipt.key(message)).verdict());
        restored.configure(2, config(true), LIMITS);
        assertEquals("ready", restored.tick(source, LIMITS, tick(3))); assertEquals("conflict", code(restored));
        assertEquals(0, view.writes);
    }
    @Test void sharedBatchPreflightFailureAndStalePreparationCannotPublishHalfAVerdict() {
        var store = configured();
        var message = request(0, put(media.stat(view.book, "reading").field("version"), "reading", 44));
        store.receive(message, LIMITS, tick(1)); var before = store.write();
        var target = new LibraryWriteBatch.Target(tick(1), "日志", "reading");
        var current = store.snapshot(LIMITS).orElseThrow();
        var tight = new LibraryServiceLimits(current.value().storageUnits(), LIMITS.grants(), LIMITS.mailbox(), LIMITS.archive());
        assertEquals("memory_limit", assertThrows(LibraryBookAccess.Failure.class,
            () -> store.prepareBatch(source, tight, tick(2), Optional.of(target))).code());
        assertEquals(before, store.write()); assertEquals(0, view.writes);
        var prepared = store.prepareBatch(source, LIMITS, tick(2), Optional.of(target));
        store.configure(1, config(false), LIMITS);
        assertFalse(prepared.current());
        assertEquals("changed", assertThrows(LibraryBookAccess.Failure.class, prepared::publishWithoutCallback).code());
        assertEquals("", store.snapshot(LIMITS).orElseThrow().writes().get(LibraryMutation.Receipt.key(message)).verdict());
    }
    @Test void closedTickPutCommitsOnePhysicalEntryAndReplySurvivesReloadWithoutRepeatingWrite() {
        var store = configured(); var before = view.book.copy();
        var message = request(0, put(media.stat(view.book, "reading").field("version"), "reading", 44));
        assertEquals(AdmissionStatus.ACCEPTED, store.receive(message, LIMITS, tick(3)));
        assertEquals("batch_pending", store.tick(source, LIMITS, tick(3)));
        assertTrue(ItemStack.matches(before, view.book));
        assertEquals("ready", store.tick(source, LIMITS, tick(4)));
        assertEquals("ok", code(store)); assertEquals(1, view.writes);
        assertEquals(new DataValue.IntegerValue(44), media.read(view.book).get("reading").value());
        var reply = store.outbound(LIMITS).orElseThrow();
        assertEquals(obj("version", media.stat(view.book, "reading").field("version")), reply.envelope().payload().field("data"));
        var restored = new LibraryServiceStore(() -> {}); restored.read(store.write());
        assertEquals("ready", restored.tick(source, LIMITS, tick(5)));
        assertEquals(reply, restored.outbound(LIMITS).orElseThrow()); assertEquals(1, view.writes);
    }
    @Test void sameTickCompetitorsBehindBlockedReadReplyBothFailAfterClosedReloadRegardlessArrivalOrder() {
        for (boolean reverse : List.of(false, true)) {
            var store = configured(); var before = view.book.copy();
            store.receive(request(0, obj("op", "read", "volume", "日志", "entry", "reading")), LIMITS, tick(0));
            assertEquals("ready", store.tick(source, LIMITS, tick(1)));
            var readReply = store.outbound(LIMITS).orElseThrow();
            var version = media.stat(view.book, "reading").field("version");
            var a = request(0, put(version, "reading", 44)); var b = request(0, put(version, "reading", 45));
            assertEquals(AdmissionStatus.ACCEPTED, store.receive(reverse ? b : a, LIMITS, tick(2)));
            store.tick(source, LIMITS, tick(2)); // The second competitor arrives after this tick callback.
            assertEquals(AdmissionStatus.ACCEPTED, store.receive(reverse ? a : b, LIMITS, tick(2)));
            assertTrue(ItemStack.matches(before, view.book));
            assertEquals("configured", store.configure(1, config(false), LIMITS));
            var restored = new LibraryServiceStore(() -> {}); restored.read(store.write());
            assertEquals("closed", restored.tick(source, LIMITS, new LibraryWriteBatch(UUID.randomUUID(), 0)));
            assertEquals("configured", restored.configure(2, config(true), LIMITS));
            assertEquals("ready", restored.tick(source, LIMITS, new LibraryWriteBatch(UUID.randomUUID(), 0)));
            assertEquals(readReply, restored.outbound(LIMITS).orElseThrow());
            assertTrue(restored.acknowledge(readReply, LIMITS));
            for (int i = 0; i < 2; i++) {
                assertEquals("ready", restored.tick(source, LIMITS, tick(5)));
                assertEquals("conflict", code(restored));
                assertTrue(restored.acknowledge(restored.outbound(LIMITS).orElseThrow(), LIMITS));
            }
            assertTrue(ItemStack.matches(before, view.book)); assertEquals(0, view.writes);
        }
    }
    @Test void invalidContenderDoesNotVetoValidWriteAndDifferentEntriesRemainIndependent() {
        var store = configured();
        var version = media.stat(view.book, "reading").field("version");
        var old = LibraryMutation.Version.decode(version);
        var stale = new LibraryMutation.Version(old.medium(), old.entry(), old.revision() - 1).value();
        store.receive(request(0, put(stale, "reading", 99)), LIMITS, tick(1));
        store.receive(request(0, put(version, "reading", 44)), LIMITS, tick(1));
        store.receive(request(0, put(media.stat(view.book, "other").field("version"), "other", 55)), LIMITS, tick(1));
        for (String expected : List.of("stale_version", "ok", "ok")) {
            assertEquals("ready", store.tick(source, LIMITS, tick(2))); assertEquals(expected, code(store));
            assertTrue(store.acknowledge(store.outbound(LIMITS).orElseThrow(), LIMITS, source));
        }
        assertEquals(new DataValue.IntegerValue(44), media.read(view.book).get("reading").value());
        assertEquals(new DataValue.IntegerValue(55), media.read(view.book).get("other").value());
    }
    @Test void authorizationPrecedesPhysicalLookupAndLegacyRequestsDoNotAcquireNewWriteAuthority() {
        for (var access : List.of(LibraryServiceConfiguration.Access.CLOSED, LibraryServiceConfiguration.Access.READ_ONLY)) {
            var store = configured();
            store.configure(1, new LibraryServiceConfiguration(config(true).endpoint(), Map.of("日志", access)), LIMITS);
            store.receive(request(0, put(media.stat(view.book, "reading").field("version"), "reading", 44)), LIMITS, tick(1));
            assertEquals("ready", store.tick(() -> { fail("Unauthorized request inspected physical books"); return null; }, LIMITS, tick(2)));
            assertEquals(access == LibraryServiceConfiguration.Access.CLOSED ? "denied" : "readonly", code(store)); assertEquals(0, view.writes);
        }
        var store = configured();
        store.receive(request(0, put(media.stat(view.book, "reading").field("version"), "reading", 44)), LIMITS);
        var root = (DataValue.RecordValue)ValueNbtCodec.decode((net.minecraft.nbt.CompoundTag)store.write(), LIMITS.archive());
        var fields = new HashMap<>(root.fields()); fields.remove("writes"); fields.put("version", new DataValue.IntegerValue(1));
        var legacy = new LibraryServiceStore(() -> {}); legacy.read(ValueNbtCodec.encode(new DataValue.RecordValue(fields), LIMITS.archive()));
        assertEquals("ready", legacy.tick(source, LIMITS, tick(9))); assertEquals("unsupported", code(legacy)); assertEquals(0, view.writes);
    }
    @Test void completeReplyMustFitBeforePhysicalReplacementAndReducedLimitsRetainQueuedWork() {
        var store = configured(); var before = view.book.copy();
        var message = request(0, put(media.stat(view.book, "reading").field("version"), "reading", 44));
        store.receive(message, LIMITS, tick(1));
        var sealed = store.snapshot(LIMITS).orElseThrow().writes(Map.of(LibraryMutation.Receipt.key(message), new LibraryWriteBatch.Admission(tick(1), "eligible")));
        var tight = new LibraryServiceLimits(sealed.value().storageUnits(), LIMITS.grants(), LIMITS.mailbox(), LIMITS.archive());
        assertEquals("memory_limit", store.tick(source, tight, tick(2)));
        assertTrue(ItemStack.matches(before, view.book)); assertEquals(0, view.writes); assertTrue(store.outbound(tight).isEmpty());
        var restored = new LibraryServiceStore(() -> {}); restored.read(store.write());
        assertEquals("ready", restored.tick(source, LIMITS, tick(3))); assertEquals("ok", code(restored)); assertEquals(1, view.writes);
    }
    @Test void batchPublicationBudgetFailureCannotTurnConflictingRequestsIntoAWinner() {
        var store = configured(); var expected = media.stat(view.book, "reading").field("version"); var before = view.book.copy();
        store.receive(request(0, put(expected, "reading", 44)), LIMITS, tick(1));
        store.receive(request(0, put(expected, "reading", 45)), LIMITS, tick(1));
        var admitted = store.write(); var current = store.snapshot(LIMITS).orElseThrow();
        var tight = new LibraryServiceLimits(current.value().storageUnits(), LIMITS.grants(), LIMITS.mailbox(), LIMITS.archive());
        assertEquals("memory_limit", store.tick(source, tight, tick(2))); assertEquals(admitted, store.write());
        assertTrue(ItemStack.matches(before, view.book)); assertEquals(0, view.writes);
        var restored = new LibraryServiceStore(() -> {}); restored.read(store.write());
        for (int i = 0; i < 2; i++) {
            assertEquals("ready", restored.tick(source, LIMITS, tick(3))); assertEquals("conflict", code(restored));
            assertTrue(restored.acknowledge(restored.outbound(LIMITS).orElseThrow(), LIMITS));
        }
        assertEquals(0, view.writes); assertTrue(ItemStack.matches(before, view.book));
    }
    @Test void malformedRequestLegacyMediaAndMissingDeleteRemainExplicitNonMutatingResults() {
        var store = configured(); var version = media.stat(view.book, "reading").field("version");
        store.receive(request(0, obj("op", "put", "volume", "日志", "entry", "reading", "expected", version, "kind", "DATA")), LIMITS, tick(1));
        store.receive(request(0, obj("op", "delete", "volume", "日志", "entry", "absent", "expected", media.stat(view.book, "absent").field("version"))), LIMITS, tick(1));
        for (String expected : List.of("invalid_request", "missing_entry")) {
            assertEquals("ready", store.tick(source, LIMITS, tick(2))); assertEquals(expected, code(store));
            assertTrue(store.acknowledge(store.outbound(LIMITS).orElseThrow(), LIMITS));
        }
        view.book = LibraryBookVolumesTest.VOLUMES.rename(LibraryBookVersionsTest.legacy(), "日志"); var oldBook = view.book.copy();
        store.receive(request(0, put(version, "reading", 55)), LIMITS, tick(3));
        assertEquals("ready", store.tick(source, LIMITS, tick(4))); assertEquals("unversioned_medium", code(store));
        assertTrue(ItemStack.matches(oldBook, view.book)); assertEquals(0, view.writes);
    }
    @Test void deletedEntryHasTombstoneAndReplacementBookCannotBeWrittenByQueuedOldRequest() {
        var store = configured(); var version = media.stat(view.book, "reading").field("version");
        store.receive(request(0, obj("op", "delete", "volume", "日志", "entry", "reading", "expected", version)), LIMITS, tick(1));
        assertEquals("ready", store.tick(source, LIMITS, tick(2))); assertEquals("ok", code(store));
        assertFalse(((DataValue.BooleanValue)media.stat(view.book, "reading").field("present")).value());
        assertTrue(store.acknowledge(store.outbound(LIMITS).orElseThrow(), LIMITS, source));
        var tombstone = media.stat(view.book, "reading").field("version"); assertNotEquals(version, tombstone);
        store.receive(request(0, put(tombstone, "reading", 55)), LIMITS, tick(3));
        view.book = LibraryBookVolumesTest.VOLUMES.rename(media.upgrade(LibraryBookVersionsTest.legacy()), "日志"); var replacement = view.book.copy();
        assertEquals("ready", store.tick(source, LIMITS, tick(4))); assertEquals("medium_changed", code(store));
        assertTrue(ItemStack.matches(replacement, view.book));
    }
    @Test void acknowledgedSuccessDoesNotCleanAnUnavailableOrReplacedMedium() {
        var store = configured(); var message = request(0, put(media.stat(view.book, "reading").field("version"), "reading", 44));
        store.receive(message, LIMITS, tick(1)); store.tick(source, LIMITS, tick(2));
        var taken = view.book.copy();
        view.book = LibraryBookVolumesTest.VOLUMES.rename(media.upgrade(LibraryBookVersionsTest.legacy()), "日志"); var replacement = view.book.copy();
        assertTrue(store.acknowledge(store.outbound(LIMITS).orElseThrow(), LIMITS, source));
        assertTrue(ItemStack.matches(replacement, view.book)); assertEquals(1, view.writes);
        assertTrue(media.prepareWrite(taken, message).replay()); assertTrue(store.snapshot(LIMITS).orElseThrow().mailbox().queued().isEmpty());
    }
    @Test void serviceRecoversCommittedBookAfterCallbackFailureThenAcknowledgesBeforeReleasingReceipt() {
        var store = configured();
        var message = request(0, put(media.stat(view.book, "reading").field("version"), "reading", 44));
        store.receive(message, LIMITS, tick(1));
        LibraryWriteService.Source fault = () -> {
            var directory = source.snapshot();
            return new LibraryWriteService.Directory() {
                public String status(String volume) { return directory.status(volume); }
                public Map<String, LibraryEntry> read(String volume) { return directory.read(volume); }
                public DataValue.RecordValue stat(String volume, String entry) { return directory.stat(volume, entry); }
                public LibraryBookAccess.PreparedWrite prepare(MessageEnvelope request) {
                    var prepared = directory.prepare(request);
                    return new LibraryBookAccess.PreparedWrite(prepared.reply(), prepared.replay(), () -> {
                        prepared.commit().run(); throw new IllegalStateException("after physical commit");
                    });
                }
                public void release(MessageEnvelope request) { directory.release(request); }
            };
        };
        assertEquals("commit_retry", store.tick(fault, LIMITS, tick(2)));
        assertTrue(store.outbound(LIMITS).isEmpty()); assertEquals(1, view.writes);
        var receipt = media.prepareWrite(view.book, message); assertTrue(receipt.replay());
        var restored = new LibraryServiceStore(() -> {}); restored.read(store.write());
        assertEquals("ready", restored.tick(source, LIMITS, new LibraryWriteBatch(UUID.randomUUID(), 0)));
        assertEquals(receipt.reply(), restored.outbound(LIMITS).orElseThrow().envelope().payload()); assertEquals(1, view.writes);
        var beforeCleanup = media.stat(view.book, "reading");
        var outbound = restored.outbound(LIMITS).orElseThrow();
        assertTrue(restored.acknowledge(outbound, LIMITS, source));
        assertEquals(beforeCleanup, media.stat(view.book, "reading")); assertEquals(2, view.writes);
        assertEquals("stale_version", assertThrows(LibraryBookAccess.Failure.class, () -> media.prepareWrite(view.book, message)).code());
        assertFalse(restored.acknowledge(outbound, LIMITS, source)); assertEquals(2, view.writes);
        var afterAck = new LibraryServiceStore(() -> {}); afterAck.read(restored.write());
        assertEquals(AdmissionStatus.DUPLICATE, afterAck.receive(message, LIMITS, tick(9)));
    }
}
