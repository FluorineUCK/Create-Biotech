package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.*;

class LibraryBookTransactionTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    final LibraryBookMedia media = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS);
    static DataValue.RecordValue obj(Object... values) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < values.length; i += 2) fields.put((String)values[i], values[i+1] instanceof String s ? new DataValue.TextValue(s) : (DataValue)values[i+1]);
        return new DataValue.RecordValue(fields);
    }
    static MessageEnvelope request(long sequence, DataValue.RecordValue payload) {
        UUID source = UUID.randomUUID(), event = UUID.randomUUID();
        return new MessageEnvelope(source, sequence, event, 0, payload,
            new MessageProtocol.Request(new RequestContext(new RequestCorrelation(source, event, 0), "client")));
    }
    static DataValue.RecordValue put(DataValue expected, String entry, long value) {
        return obj("op", "put", "volume", "日志", "entry", entry, "expected", expected, "kind", "DATA", "value", new DataValue.IntegerValue(value));
    }
    @Test void preparedWriteAndReceiptReplaceOnePhysicalBookAndReplayDoesNotWriteAgain() {
        var view = new LibraryBookAccessTest.View(); view.book = LibraryBookVolumesTest.VOLUMES.rename(media.upgrade(LibraryBookVersionsTest.legacy()), "日志");
        var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        var expected = media.stat(view.book, "reading").field("version"); var before = view.book.copy();
        var message = request(0, put(expected, "reading", 44));
        var prepared = access.prepareWrite(access.list().getFirst(), message);
        assertTrue(ItemStack.matches(before, view.book)); assertEquals(0, view.writes);
        assertFalse(prepared.replay()); prepared.commit().run();
        assertEquals(1, view.writes); assertEquals(new DataValue.IntegerValue(44), media.read(view.book).get("reading").value());
        assertNotEquals(expected, media.stat(view.book, "reading").field("version"));
        assertEquals(new DataValue.TextValue("ok"), prepared.reply().field("code"));
        var replay = access.prepareWrite(access.list().getFirst(), message);
        assertTrue(replay.replay()); assertEquals(prepared.reply(), replay.reply()); replay.commit().run();
        assertEquals(1, view.writes);
        assertEquals("stale_version", assertThrows(LibraryBookAccess.Failure.class,
            () -> access.prepareWrite(access.list().getFirst(), request(1, put(expected, "reading", 45)))).code());
        assertEquals(1, view.writes);
    }
    @Test void changingOnlyTheRpcReturnRouteDoesNotChangeTheOriginalTransactionIdentity() {
        var book = media.upgrade(LibraryBookVersionsTest.legacy());
        var message = request(0, put(media.stat(book, "reading").field("version"), "reading", 44));
        var written = media.prepareWrite(book, message);
        var context = ((MessageProtocol.Request)message.protocol()).context();
        var rerouted = new MessageEnvelope(message.source(), message.sequence(), message.eventId(), message.ordinal(), message.payload(),
            new MessageProtocol.Request(new RequestContext(context.correlation(), "cluster-result")));
        var replay = media.prepareWrite(written.replacement(), rerouted);
        assertTrue(replay.replay()); assertEquals(written.reply(), replay.reply());
        assertTrue(ItemStack.matches(written.replacement(), replay.replacement()));
    }
    @Test void aPreparedHandleCannotAuthorizeWritingADifferentlyNamedVolume() {
        var view = new LibraryBookAccessTest.View();
        view.book = LibraryBookVolumesTest.VOLUMES.rename(media.upgrade(LibraryBookVersionsTest.legacy()), "private");
        var before = view.book.copy(); var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        var message = request(0, put(media.stat(view.book, "reading").field("version"), "reading", 44));
        assertEquals("changed", assertThrows(LibraryBookAccess.Failure.class, () -> access.prepareWrite(access.list().getFirst(), message)).code());
        assertEquals(0, view.writes); assertTrue(ItemStack.matches(before, view.book));
    }
    @Test void committedReceiptSurvivesCodecAndLocalEditsAndCleanupDoesNotChangeTheEntryVersion() {
        var original = media.upgrade(LibraryBookVersionsTest.legacy());
        var message = request(0, put(media.stat(original, "reading").field("version"), "reading", 44));
        var completed = media.prepareWrite(original, message);
        var saved = CustomData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, completed.replacement().get(LibraryBookMediaTest.COMPONENT)).getOrThrow();
        var loaded = original.copy(); loaded.set(LibraryBookMediaTest.COMPONENT, CustomData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, saved).getOrThrow());
        loaded = media.put(loaded, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(45)));
        assertEquals(completed.reply(), media.prepareWrite(loaded, message).reply(), "Retry reports the original commit, not the newer local edit");
        assertEquals(new DataValue.IntegerValue(45), media.read(loaded).get("reading").value());
        var version = media.stat(loaded, "reading");
        var cleared = media.releaseReceipt(loaded, message);
        assertEquals(version, media.stat(cleared, "reading")); assertTrue(bytes(cleared) < bytes(loaded));
        assertTrue(ItemStack.matches(cleared, media.releaseReceipt(cleared, message)));
        assertEquals("stale_version", assertThrows(LibraryBookAccess.Failure.class, () -> media.prepareWrite(cleared, message)).code());
        assertEquals(original.get(DataComponents.WRITABLE_BOOK_CONTENT), cleared.get(DataComponents.WRITABLE_BOOK_CONTENT));
        assertEquals(original.get(DataComponents.CUSTOM_DATA), cleared.get(DataComponents.CUSTOM_DATA));
    }

    @Test void changedCommandCannotReuseOrEraseAnExistingCompletionReceipt() {
        var book = media.upgrade(LibraryBookVersionsTest.legacy());
        var expected = media.stat(book, "reading").field("version"); var message = request(0, put(expected, "reading", 44));
        var written = media.prepareWrite(book, message).replacement(); var before = written.copy();
        var different = new MessageEnvelope(message.source(), message.sequence(), message.eventId(), message.ordinal(), put(expected, "reading", 999), message.protocol());
        assertEquals("identity_conflict", assertThrows(LibraryBookAccess.Failure.class, () -> media.prepareWrite(written, different)).code());
        assertEquals("identity_conflict", assertThrows(LibraryBookAccess.Failure.class, () -> media.releaseReceipt(written, different)).code());
        assertTrue(ItemStack.matches(before, written));
    }

    @Test void preparedPhysicalCommitRejectsBothChangedStackAndRevokedEditingPermission() {
        var view = namedView(); var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        var message = request(0, put(media.stat(view.book, "reading").field("version"), "reading", 44));
        var prepared = access.prepareWrite(access.list().getFirst(), message);
        view.allowed = false;
        assertEquals("denied", assertThrows(LibraryBookAccess.Failure.class, prepared.commit()::run).code());
        view.allowed = true;
        view.book = LibraryBookVolumesTest.VOLUMES.rename(media.upgrade(LibraryBookVersionsTest.legacy()), "日志");
        var replacement = view.book.copy();
        assertEquals("changed", assertThrows(LibraryBookAccess.Failure.class, prepared.commit()::run).code());
        assertEquals("medium_changed", assertThrows(LibraryBookAccess.Failure.class,
            () -> access.prepareWrite(access.list().getFirst(), message)).code());
        assertEquals(0, view.writes); assertTrue(ItemStack.matches(replacement, view.book));
    }

    @Test void physicalWriteThenProviderExceptionCanRecoverFromBookReceiptWithoutRepeatingMutation() {
        var backing = namedView(); boolean[] fault = {true};
        var view = new LibraryBookAccess.View() {
            public boolean mayEdit() { return true; }
            public List<BlockPos> shelves() { return backing.shelves(); }
            public List<ItemStack> contents(BlockPos shelf) { return backing.contents(shelf); }
            public boolean replace(BlockPos shelf, int slot, ItemStack before, ItemStack after) {
                boolean changed = backing.replace(shelf, slot, before, after);
                if (fault[0]) { fault[0] = false; throw new IllegalStateException("injected after physical replacement"); }
                return changed;
            }
        };
        var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        var message = request(0, put(media.stat(backing.book, "reading").field("version"), "reading", 44));
        var prepared = access.prepareWrite(access.list().getFirst(), message);
        assertThrows(IllegalStateException.class, prepared.commit()::run);
        assertEquals(1, backing.writes);
        var after = backing.book.copy(); var replay = access.prepareWrite(access.list().getFirst(), message);
        assertTrue(replay.replay()); assertEquals(prepared.reply(), replay.reply()); replay.commit().run();
        assertEquals(1, backing.writes); assertTrue(ItemStack.matches(after, backing.book));
    }

    @Test void deleteKeepsAbsenceVersionAndItsRetryDoesNotDeleteARecreatedEntry() {
        var book = media.upgrade(LibraryBookVersionsTest.legacy()); var expected = media.stat(book, "reading").field("version");
        var message = request(0, obj("op", "delete", "volume", "日志", "entry", "reading", "expected", expected));
        var deleted = media.prepareWrite(book, message); var absent = media.stat(deleted.replacement(), "reading");
        assertEquals(new DataValue.BooleanValue(false), absent.field("present"));
        assertNotEquals(expected, absent.field("version"));
        var recreated = media.prepareWrite(deleted.replacement(), request(1, put(absent.field("version"), "reading", 55))).replacement();
        var retriedDelete = media.prepareWrite(recreated, message);
        assertTrue(retriedDelete.replay()); assertEquals(deleted.reply(), retriedDelete.reply());
        assertEquals(new DataValue.IntegerValue(55), media.read(retriedDelete.replacement()).get("reading").value());
        assertEquals("missing_entry", assertThrows(LibraryBookAccess.Failure.class, () -> media.prepareWrite(deleted.replacement(),
            request(2, obj("op", "delete", "volume", "日志", "entry", "reading", "expected", absent.field("version"))))).code());
    }

    @Test void receiptCapacityIsCheckedBeforeAnyReplacementAndPreparedStacksAreDefensiveCopies() {
        var book = media.upgrade(LibraryBookVersionsTest.legacy()); var before = book.copy();
        var entry = new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(44));
        var local = media.put(book, "reading", entry);
        var limited = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS, bytes(local));
        var message = request(0, put(media.stat(book, "reading").field("version"), "reading", 44));
        assertEquals(ArchiveException.Code.LIMIT_EXCEEDED,
            assertThrows(ArchiveException.class, () -> limited.prepareWrite(book, message)).code());
        assertTrue(ItemStack.matches(book, before));
        var prepared = media.prepareWrite(book, message); prepared.replacement().setCount(9);
        assertEquals(1, prepared.replacement().getCount()); assertTrue(bytes(prepared.replacement()) > bytes(local));
    }

    @Test void strictCommandsNeverInitializeLegacyBooksOrAcceptMismatchedEntryTokens() {
        var legacy = LibraryBookVersionsTest.legacy(); var before = legacy.copy();
        var initialized = media.upgrade(legacy); var token = media.stat(initialized, "reading").field("version");
        assertEquals("unversioned_medium", assertThrows(LibraryBookAccess.Failure.class,
            () -> media.prepareWrite(legacy, request(0, put(token, "reading", 44)))).code());
        assertThrows(IllegalArgumentException.class, () -> LibraryMutation.decode(put(token, "other", 44)));
        var missing = new LinkedHashMap<>(put(token, "reading", 44).fields()); missing.remove("expected");
        assertThrows(IllegalArgumentException.class, () -> LibraryMutation.decode(new DataValue.RecordValue(missing)));
        var extra = new LinkedHashMap<>(put(token, "reading", 44).fields()); extra.put("nbt", obj());
        assertThrows(IllegalArgumentException.class, () -> LibraryMutation.decode(new DataValue.RecordValue(extra)));
        var program = new LinkedHashMap<>(put(token, "reading", 44).fields()); program.put("kind", new DataValue.TextValue("PROGRAM"));
        assertThrows(IllegalArgumentException.class, () -> LibraryMutation.decode(new DataValue.RecordValue(program)));
        assertTrue(ItemStack.matches(before, legacy));
    }

    @Test void versionTwoBooksKeepTheirIdentityAndEntriesWhenFirstTransactionAddsReceipts() {
        var current = media.upgrade(LibraryBookVersionsTest.legacy());
        var fields = root(current); fields.remove("receipts"); fields.put("version", new DataValue.IntegerValue(2));
        var v2 = encoded(current, fields); var before = v2.copy();
        assertEquals(media.stat(current, "reading"), media.stat(v2, "reading"));
        assertTrue(ItemStack.matches(before, v2));
        var token = media.stat(v2, "reading").field("version");
        var result = media.prepareWrite(v2, request(0, put(token, "reading", 44)));
        var version = ((DataValue.RecordValue)media.stat(result.replacement(), "reading").field("version"));
        assertEquals(((DataValue.RecordValue)token).field("medium"), version.field("medium"));
        assertEquals(new DataValue.IntegerValue(44), media.read(result.replacement()).get("reading").value());
        assertTrue(ItemStack.matches(before, v2));
    }

    @Test void corruptCompletionEvidenceIsNotSilentlyDiscardedOrOverwritten() {
        var book = media.upgrade(LibraryBookVersionsTest.legacy());
        var message = request(0, put(media.stat(book, "reading").field("version"), "reading", 44));
        var completed = media.prepareWrite(book, message).replacement();
        var cases = new ArrayList<Map<String, DataValue>>();
        var wrongOwner = root(completed); wrongOwner.put("medium", new DataValue.TextValue(UUID.randomUUID().toString())); cases.add(wrongOwner);
        var badVersion = root(completed); badVersion.put("revisions", obj("reading", new DataValue.IntegerValue(1))); cases.add(badVersion);
        var badReceipt = root(completed); badReceipt.put("receipts", obj("wrong_key", obj())); cases.add(badReceipt);
        for (var fields : cases) {
            var corrupted = encoded(completed, fields); var before = corrupted.copy();
            assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class, () -> media.read(corrupted)).code());
            assertThrows(ArchiveException.class, () -> media.prepareWrite(corrupted, message));
            assertThrows(ArchiveException.class, () -> media.releaseReceipt(corrupted, message));
            assertThrows(ArchiveException.class, () -> media.put(corrupted, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE)));
            assertTrue(ItemStack.matches(before, corrupted));
        }
    }

    private static Map<String, DataValue> root(ItemStack book) {
        return new LinkedHashMap<>(((DataValue.RecordValue)ValueNbtCodec.decode(book.get(LibraryBookMediaTest.COMPONENT).copyTag(), LibraryBookMediaTest.LIMITS)).fields());
    }
    private static ItemStack encoded(ItemStack source, Map<String, DataValue> fields) {
        var copy = source.copy(); copy.set(LibraryBookMediaTest.COMPONENT,
            CustomData.of(ValueNbtCodec.encode(new DataValue.RecordValue(fields), LibraryBookMediaTest.LIMITS))); return copy;
    }

    private LibraryBookAccessTest.View namedView() {
        var view = new LibraryBookAccessTest.View();
        view.book = LibraryBookVolumesTest.VOLUMES.rename(media.upgrade(LibraryBookVersionsTest.legacy()), "日志"); return view;
    }
    private static int bytes(ItemStack book) {
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try { buffer.writeNbt(book.get(LibraryBookMediaTest.COMPONENT).copyTag()); return buffer.readableBytes(); }
        finally { buffer.release(); }
    }
}
