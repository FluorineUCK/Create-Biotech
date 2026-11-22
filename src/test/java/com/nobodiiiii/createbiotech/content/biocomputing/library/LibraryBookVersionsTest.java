package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.*;

class LibraryBookVersionsTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    final LibraryBookMedia media = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS);

    @Test void publicStatReadsTypedValueAndVersionFromOneAuthorizedBookSnapshot() {
        var view = new LibraryBookAccessTest.View(); view.book = legacy();
        var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        access.renameVolume(access.list().getFirst(), "日志");
        var before = view.book.copy();
        var result = stat(view.book, "reading");
        assertEquals(new DataValue.BooleanValue(true), result.field("present"));
        assertEquals(new DataValue.TextValue("DATA"), result.field("kind"));
        assertEquals(new DataValue.IntegerValue(43), result.field("value"));
        assertInstanceOf(DataValue.RecordValue.class, result.field("version"));
        assertEquals(result, stat(view.book.copy(), "reading"));
        var absent = stat(view.book, "new");
        assertEquals(new DataValue.BooleanValue(false), absent.field("present"));
        assertEquals(DataValue.NullValue.INSTANCE, absent.field("value"));
        assertNotEquals(result.field("version"), absent.field("version"));
        assertTrue(ItemStack.matches(before, view.book)); assertEquals(1, view.writes);
    }

    DataValue.RecordValue stat(ItemStack book, String entry) {
        var view = new LibraryBookAccessTest.View(); view.book = book; view.allowed = false;
        var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        var config = new LibraryServiceConfiguration(Optional.of(new EndpointConfiguration("books", true)),
            Map.of("日志", LibraryServiceConfiguration.Access.READ_ONLY));
        var reply = LibraryReadService.execute(config, new DataValue.RecordValue(Map.of(
            "op", new DataValue.TextValue("stat"), "volume", new DataValue.TextValue("日志"), "entry", new DataValue.TextValue(entry))),
            new LibraryBookReadSource(access, Set::of));
        assertEquals(new DataValue.TextValue("ok"), reply.field("code"));
        assertEquals(0, view.writes);
        return (DataValue.RecordValue)reply.field("data");
    }

    @Test void localEditsAdvanceOnlyTheirEntryAndNeverReuseDeletedOrOtherBooksVersions() {
        var view = new LibraryBookAccessTest.View(); view.book = legacy();
        var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        var handle = access.renameVolume(access.list().getFirst(), "日志");
        var old = stat(view.book, "reading").field("version");
        var absent = stat(view.book, "other").field("version");
        var value = new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43));
        handle = access.put(handle, "other", value);
        assertEquals(old, stat(view.book, "reading").field("version"));
        assertNotEquals(absent, stat(view.book, "other").field("version"));
        handle = access.put(handle, "reading", value);
        var replaced = stat(view.book, "reading").field("version");
        assertNotEquals(old, replaced, "Even same-value explicit replacement consumes a revision");
        handle = access.delete(handle, "other");
        assertNotEquals(absent, stat(view.book, "other").field("version"));
        assertEquals(new DataValue.BooleanValue(false), stat(view.book, "other").field("present"));
        handle = access.delete(handle, "reading");
        var removed = stat(view.book, "reading").field("version");
        handle = access.put(handle, "reading", value);
        var recreated = stat(view.book, "reading").field("version");
        assertNotEquals(old, recreated); assertNotEquals(replaced, recreated); assertNotEquals(removed, recreated);
        access.renameVolume(handle, "日志");
        assertEquals(recreated, stat(view.book, "reading").field("version"));
        var encoded = CustomData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, view.book.get(LibraryBookMediaTest.COMPONENT)).getOrThrow();
        var carried = LibraryBookVolumesTest.VOLUMES.rename(LibraryBookMediaTest.book(), "日志");
        carried.set(LibraryBookMediaTest.COMPONENT, CustomData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, encoded).getOrThrow());
        assertEquals(recreated, stat(carried, "reading").field("version"));
        var replacementBook = media.put(LibraryBookVolumesTest.VOLUMES.rename(LibraryBookMediaTest.book(), "日志"), "reading", value);
        assertNotEquals(recreated, stat(replacementBook, "reading").field("version"));
    }

    @Test void explicitLecternRenameUpgradesLegacyContentsButReadDoesNotWrite() {
        var view = new LibraryBookAccessTest.View(); view.book = legacy();
        var original = view.book.copy(); var entries = media.read(view.book);
        assertTrue(ItemStack.matches(original, view.book));
        var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        var renamed = access.renameVolume(access.list().getFirst(), "日志");
        assertEquals(1, view.writes);
        assertNotEquals(original.get(LibraryBookMediaTest.COMPONENT), view.book.get(LibraryBookMediaTest.COMPONENT),
            "Explicit rename must initialize book-owned versions, not just change the volume label");
        assertEquals(entries, access.read(renamed));
        LibraryBookIdentityPageTest.assertOriginalPagesPreserved(original, view.book);
        assertEquals(original.get(DataComponents.CUSTOM_DATA), view.book.get(DataComponents.CUSTOM_DATA));
        assertTrue(ItemStack.matches(original, legacy()));
    }

    @Test void unversionedReadRemainsCompatibleAndStatNeverInitializesABook() {
        for (var book : List.of(legacy(), LibraryBookMediaTest.book())) {
            var before = book.copy();
            assertEquals("unversioned_medium", assertThrows(LibraryBookAccess.Failure.class, () -> media.stat(book, "reading")).code());
            media.read(book);
            assertTrue(ItemStack.matches(before, book));
            var saved = media.put(book, "new", new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE));
            assertEquals(new DataValue.BooleanValue(true), media.stat(saved, "new").field("present"));
            assertEquals(media.read(book).get("reading"), media.read(saved).get("reading"));
        }
    }

    @Test void revisionMetadataAndTombstonesConsumeExistingCapacityWithoutPartialLocalWrites() {
        var view = new LibraryBookAccessTest.View(); view.book = legacy(); var before = view.book.copy();
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        int legacyBytes;
        try { buffer.writeNbt(view.book.get(LibraryBookMediaTest.COMPONENT).copyTag()); legacyBytes = buffer.readableBytes(); }
        finally { buffer.release(); }
        var bounded = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS, legacyBytes);
        var access = new LibraryBookAccess(view, bounded, LibraryBookVolumesTest.VOLUMES);
        var handle = access.list().getFirst();
        assertEquals(ArchiveException.Code.LIMIT_EXCEEDED,
            assertThrows(ArchiveException.class, () -> access.renameVolume(handle, "日志")).code());
        assertEquals(0, view.writes); assertTrue(ItemStack.matches(before, view.book));
        assertEquals(new DataValue.IntegerValue(43), bounded.read(view.book).get("reading").value());

        var limited = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS, 1800);
        var book = limited.upgrade(LibraryBookMediaTest.book()); boolean reachedLimit = false;
        for (int i = 0; i < 100; i++) {
            var current = book.copy();
            try {
                var created = limited.put(book, "deleted" + i, new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE));
                book = limited.delete(created, "deleted" + i);
                assertTrue(limited.read(book).isEmpty());
            } catch (ArchiveException failure) {
                assertEquals(ArchiveException.Code.LIMIT_EXCEEDED, failure.code());
                assertTrue(ItemStack.matches(current, book)); reachedLimit = true; break;
            }
        }
        assertTrue(reachedLimit, "Deleted-name history must not be an unmetered hidden log");
    }

    @Test void malformedRevisionsAndCounterExhaustionNeverOverwriteTheSource() {
        var written = media.upgrade(legacy());
        var fields = ((DataValue.RecordValue)ValueNbtCodec.decode(written.get(LibraryBookMediaTest.COMPONENT).copyTag(),
            LibraryBookMediaTest.LIMITS)).fields();
        for (var invalid : List.of(Map.entry("medium", (DataValue)new DataValue.TextValue("not-an-id")),
                Map.entry("counter", new DataValue.IntegerValue(-1)),
                Map.entry("revisions", new DataValue.RecordValue(Map.of())),
                Map.entry("revisions", new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(0)))),
                Map.entry("revisions", new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(2)))))) {
            var broken = new LinkedHashMap<>(fields); broken.put(invalid.getKey(), invalid.getValue());
            var book = encoded(written, broken); var before = book.copy();
            assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class, () -> media.read(book)).code());
            assertThrows(ArchiveException.class, () -> media.upgrade(book));
            assertThrows(ArchiveException.class, () -> media.put(book, "x", new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE)));
            assertTrue(ItemStack.matches(before, book));
        }
        var exhausted = new LinkedHashMap<>(fields); exhausted.put("counter", new DataValue.IntegerValue(Long.MAX_VALUE));
        var book = encoded(written, exhausted); var before = book.copy();
        assertEquals("version_exhausted", assertThrows(LibraryBookAccess.Failure.class,
            () -> media.put(book, "new", new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE))).code());
        assertEquals("version_exhausted", assertThrows(LibraryBookAccess.Failure.class, () -> media.delete(book, "reading")).code());
        assertTrue(ItemStack.matches(before, book)); assertEquals(1, media.read(book).size());
    }

    private static ItemStack encoded(ItemStack source, Map<String, DataValue> fields) {
        var copy = source.copy(); copy.set(LibraryBookMediaTest.COMPONENT,
            CustomData.of(ValueNbtCodec.encode(new DataValue.RecordValue(fields), LibraryBookMediaTest.LIMITS)));
        return copy;
    }

    static ItemStack legacy() {
        var book = LibraryBookMediaTest.book();
        book.set(LibraryBookMediaTest.COMPONENT, CustomData.of(ValueNbtCodec.encode(new DataValue.RecordValue(Map.of(
            "version", new DataValue.IntegerValue(1), "entries", new DataValue.RecordValue(Map.of(
                "reading", new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("DATA"),
                    "value", new DataValue.IntegerValue(43))))))), LibraryBookMediaTest.LIMITS)));
        return book;
    }
}
