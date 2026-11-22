package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.*;

class LibraryDeviceWriteTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    final LibraryBookMedia media = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS);
    final UUID device = UUID.randomUUID();
    ItemStack book() {
        return LibraryBookVolumesTest.VOLUMES.rename(media.upgrade(LibraryBookVersionsTest.legacy()), "日志");
    }
    LibraryDeviceWrite operation(ItemStack book, long sequence, String entry, long value) {
        return new LibraryDeviceWrite(device, sequence, new LibraryMutation("日志", entry,
            LibraryMutation.Version.decode(media.stat(book, entry).field("version")),
            Optional.of(new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(value)))));
    }
    @Test void deviceWriteUsesTheSameBookVersionAndCarriesItsExactReceiptWithoutAnRpcEnvelope() {
        var book = book(); var before = book.copy(); var operation = operation(book, 3, "new_entry", 44);
        var prepared = media.prepareWrite(book, operation);
        assertTrue(ItemStack.matches(before, book), "Preparation cannot touch the real medium");
        assertFalse(prepared.replay());
        var written = prepared.replacement();
        assertEquals(new DataValue.IntegerValue(44), media.read(written).get("new_entry").value());
        assertEquals(media.read(before).get("reading"), media.read(written).get("reading"));
        assertEquals(before.get(DataComponents.WRITABLE_BOOK_CONTENT), written.get(DataComponents.WRITABLE_BOOK_CONTENT));
        assertEquals(before.get(DataComponents.CUSTOM_DATA), written.get(DataComponents.CUSTOM_DATA));
        var replay = media.prepareWrite(written.copy(), operation);
        assertTrue(replay.replay()); assertEquals(prepared.reply(), replay.reply());
        assertTrue(ItemStack.matches(written, replay.replacement()), "Replay does not consume another revision");
        assertEquals(media.stat(written, "new_entry").field("version"),
            ((DataValue.RecordValue)prepared.reply().field("data")).field("version"));
    }
    @Test void deviceAndLegacyReceiptsCoexistAndExactReleaseNeverRemovesTheOtherOrRevivesOldVersions() {
        var book = book(); var deviceWrite = operation(book, 3, "reading", 44);
        var first = media.prepareWrite(book, deviceWrite); book = first.replacement();
        var legacy = LibraryBookTransactionTest.request(3, LibraryBookTransactionTest.put(media.stat(book, "reading").field("version"), "reading", 45));
        // Deliberately share the UUID/sequence: identity families still cannot alias.
        var correlation = new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.RequestCorrelation(device, legacy.eventId(), 0);
        legacy = new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope(device, 3, legacy.eventId(), 0, legacy.payload(),
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageProtocol.Request(
                new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.RequestContext(correlation, "client")));
        book = media.prepareWrite(book, legacy).replacement(); var lastVersion = media.stat(book, "reading").field("version");
        assertEquals(new DataValue.IntegerValue(4), root(book).field("version"));
        assertEquals(first.reply(), media.prepareWrite(book.copy(), deviceWrite).reply(), "An older receipt is not the current entry version");
        var released = media.releaseReceipt(book, deviceWrite);
        assertEquals(lastVersion, media.stat(released, "reading").field("version"));
        assertTrue(media.prepareWrite(released, legacy).replay());
        assertEquals(new DataValue.IntegerValue(3), root(released).field("version"), "Remaining legacy journal stays in its original format");
        assertEquals("stale_version", assertThrows(LibraryBookAccess.Failure.class, () -> media.prepareWrite(released, deviceWrite)).code());
        var finalBook = media.releaseReceipt(released, legacy);
        assertEquals(lastVersion, media.stat(finalBook, "reading").field("version"));
        assertEquals(new DataValue.IntegerValue(45), media.read(finalBook).get("reading").value());
        assertTrue(((DataValue.RecordValue)root(finalBook).field("receipts")).fields().isEmpty());
    }
    @Test void sameIdentityCannotChangePayloadAndAnotherDeviceOrSequenceCannotReplayIt() {
        var book = book(); var original = operation(book, 4, "reading", 44); var written = media.prepareWrite(book, original).replacement();
        var forged = new LibraryDeviceWrite(device, 4, operation(book, 4, "reading", 99).mutation());
        assertEquals("identity_conflict", assertThrows(LibraryBookAccess.Failure.class, () -> media.prepareWrite(written, forged)).code());
        assertEquals("identity_conflict", assertThrows(LibraryBookAccess.Failure.class, () -> media.releaseReceipt(written, forged)).code());
        for (var other : List.of(new LibraryDeviceWrite(device, 5, original.mutation()),
                new LibraryDeviceWrite(UUID.randomUUID(), 4, original.mutation())))
            assertEquals("stale_version", assertThrows(LibraryBookAccess.Failure.class, () -> media.prepareWrite(written, other)).code());
        var replacement = book();
        assertEquals("medium_changed", assertThrows(LibraryBookAccess.Failure.class, () -> media.prepareWrite(replacement, original)).code());
        assertEquals(new DataValue.IntegerValue(44), media.read(written).get("reading").value());
    }
    @Test void deleteKeepsItsTombstoneAndReplayNeverDeletesANewerEntry() {
        var book = book(); var expected = LibraryMutation.Version.decode(media.stat(book, "reading").field("version"));
        var deletion = new LibraryDeviceWrite(device, 5, new LibraryMutation("日志", "reading", expected, Optional.empty()));
        var prepared = media.prepareWrite(book, deletion); var deleted = prepared.replacement();
        assertFalse(media.read(deleted).containsKey("reading"));
        assertNotEquals(expected.value(), media.stat(deleted, "reading").field("version"));
        var newer = media.put(deleted, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE));
        var replay = media.prepareWrite(newer, deletion);
        assertTrue(replay.replay()); assertEquals(prepared.reply(), replay.reply()); assertTrue(ItemStack.matches(newer, replay.replacement()));
        assertEquals(new DataValue.BooleanValue(true), media.stat(newer, "reading").field("present"));
    }
    @Test void durableDeviceReceiptRoundTripsButCannotHideInOldSchemaOrEscapeBookCapacity() {
        var book = book(); var operation = operation(book, 7, "new_entry", 44);
        assertEquals(operation, LibraryDeviceWrite.decode(operation.value()));
        var written = media.prepareWrite(book, operation).replacement();
        var raw = written.get(LibraryBookMediaTest.COMPONENT);
        var decoded = CustomData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,
            CustomData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, raw).getOrThrow()).getOrThrow();
        var carried = book.copy(); carried.set(LibraryBookMediaTest.COMPONENT, decoded);
        assertTrue(media.prepareWrite(carried, LibraryDeviceWrite.decode(operation.value())).replay());
        var bad = new LinkedHashMap<>(root(written).fields()); bad.put("version", new DataValue.IntegerValue(3));
        var disguised = written.copy(); disguised.set(LibraryBookMediaTest.COMPONENT, CustomData.of(ValueNbtCodec.encode(new DataValue.RecordValue(bad), LibraryBookMediaTest.LIMITS)));
        assertThrows(ArchiveException.class, () -> media.read(disguised));
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer()); int fullBytes;
        try { buffer.writeNbt(raw.copyTag()); fullBytes = buffer.readableBytes(); } finally { buffer.release(); }
        var bounded = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS, fullBytes - 1);
        var before = book.copy();
        assertEquals(ArchiveException.Code.LIMIT_EXCEEDED, assertThrows(ArchiveException.class, () -> bounded.prepareWrite(book, operation)).code());
        assertTrue(ItemStack.matches(before, book));
        assertEquals("unversioned_medium", assertThrows(LibraryBookAccess.Failure.class,
            () -> media.prepareWrite(LibraryBookVersionsTest.legacy(), operation)).code());
        assertThrows(IllegalArgumentException.class, () -> new LibraryDeviceWrite(device, 0, operation.mutation()));
    }
    DataValue.RecordValue root(ItemStack book) {
        return (DataValue.RecordValue)ValueNbtCodec.decode(book.get(LibraryBookMediaTest.COMPONENT).copyTag(), LibraryBookMediaTest.LIMITS);
    }
}
