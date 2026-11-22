package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.*;

class LibraryPositionedReadSourceTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    private static final LibraryTrackAddress TRACK = new LibraryTrackAddress(2, 0, 1);
    private static final BlockPos SHELF = new BlockPos(102, 64, 101);
    private static final LibraryDriveLimits LIMITS = new LibraryDriveLimits(2, 1, 2, 2, 64, 65536, LibraryBookMediaTest.LIMITS);
    private final LibraryBookMedia media = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS);

    @Test void positionedDirectoryPagesCostTimeReturnOnlyMetadataAndDoNotSearchOrModifyTheBook() {
        var fixture = new Fixture();
        fixture.book = media.put(fixture.book, "a", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.TextValue("not directory data")));
        var original = fixture.book.copy(); var head = selected(fixture);
        DataValue after = DataValue.NullValue.INSTANCE;
        DataValue version = null;
        for (String name : List.of("a", "reading")) {
            var command = new LibraryDriveCommand(TRACK.value(), new DataValue.TextValue("page:" + name), "list", head.sequence(),
                new DataValue.RecordValue(Map.of("after", after, "count", new DataValue.IntegerValue(1))));
            var admitted = command.apply(head, fixture.source, LIMITS);
            assertEquals(new DataValue.TextValue("accepted"), admitted.reply().field("code"));
            assertTrue(admitted.state().result().isEmpty());
            assertTrue(admitted.state().pending().orElseThrow().remainingTicks() > LIMITS.readBaseTicks());
            var restored = LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(admitted.state(), LIMITS), LIMITS);
            assertEquals(admitted.state(), restored, "Saved work keeps its page and remaining mechanical time");
            var complete = finish(restored, fixture.source);
            assertEquals("list", complete.result().orElseThrow().code());
            var page = (DataValue.RecordValue)complete.result().orElseThrow().value();
            assertEquals(new DataValue.TextValue("日志"), page.field("volume"));
            var rows = (DataValue.ListValue)page.field("entries"); assertEquals(1, rows.values().size());
            assertEquals(new DataValue.RecordValue(Map.of("name", new DataValue.TextValue(name), "kind", new DataValue.TextValue("DATA"))), rows.values().getFirst());
            if (version != null) assertEquals(version, page.field("version"));
            version = page.field("version"); assertInstanceOf(DataValue.RecordValue.class, version);
            after = page.field("next");
            assertEquals(name.equals("a") ? new DataValue.TextValue("a") : DataValue.NullValue.INSTANCE, after);
            assertEquals(complete, LibraryReadHead.advance(complete, fixture.source, LIMITS), "Unclaimed result stays retained");
            head = commandFor("ack", DataValue.NullValue.INSTANCE, complete.sequence()).apply(complete, fixture.source, LIMITS).state();
        }
        assertTrue(ItemStack.matches(original, fixture.book)); assertEquals(0, fixture.writes);
    }
    private static LibraryDriveCommand commandFor(String operation, DataValue data, long sequence) {
        return new LibraryDriveCommand(TRACK.value(), new DataValue.TextValue("directory-command"), operation, sequence, data);
    }

    @Test void directoryRejectsMalformedPagesBeforeReadingAndNeverTreatsMissingOrDeniedBooksAsEmpty() {
        var fixture = new Fixture(); var selected = selected(fixture);
        for (DataValue count : List.of(new DataValue.IntegerValue(0), new DataValue.IntegerValue(-1),
                new DataValue.IntegerValue(Long.MAX_VALUE), new DataValue.TextValue("1"))) {
            assertEquals("invalid_request", assertThrows(LibraryBookAccess.Failure.class, () -> commandFor("list",
                new DataValue.RecordValue(Map.of("after", DataValue.NullValue.INSTANCE, "count", count)), selected.sequence())
                .apply(selected, fixture.source, LIMITS)).code());
        }
        for (DataValue after : List.of(new DataValue.IntegerValue(0), new DataValue.TextValue(""))) {
            assertEquals("invalid_request", assertThrows(LibraryBookAccess.Failure.class, () -> commandFor("list",
                new DataValue.RecordValue(Map.of("after", after, "count", new DataValue.IntegerValue(1))), selected.sequence())
                .apply(selected, fixture.source, LIMITS)).code());
        }
        assertEquals(0, fixture.reads);
        var query = new LibraryDirectoryQuery(Optional.empty(), 1);
        assertEquals("not_positioned", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.list(LibraryReadHead.State.empty(), query, fixture.source, LIMITS)).code());
        fixture.allowed = false;
        assertEquals("denied", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.list(selected, query, fixture.source, LIMITS)).code());
        fixture.allowed = true; fixture.book = ItemStack.EMPTY;
        assertEquals("missing_medium", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.list(selected, query, fixture.source, LIMITS)).code());
        assertEquals(0, fixture.writes);
    }
    @Test void directorySnapshotRechecksPermissionIdentityContentsAndPresenceAfterSave() {
        for (String change : List.of("revoke", "replace", "create", "remove")) {
            var fixture = new Fixture();
            var pending = LibraryReadHead.list(selected(fixture), new LibraryDirectoryQuery(Optional.empty(), 2), fixture.source, LIMITS);
            String expected = switch (change) {
                case "revoke" -> { fixture.allowed = false; yield "denied"; }
                case "replace" -> { fixture.book = fixture.newBook(); yield "changed"; }
                case "create" -> { fixture.book = media.put(fixture.book, "new", new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE)); yield "changed"; }
                default -> { fixture.book = ItemStack.EMPTY; yield "missing_medium"; }
            };
            var result = finish(LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(pending, LIMITS), LIMITS), fixture.source).result().orElseThrow();
            assertEquals(expected, result.code()); assertEquals(DataValue.NullValue.INSTANCE, result.value()); assertEquals(0, fixture.writes);
        }
    }
    @Test void directoryCheckpointHasItsOwnSchemaAndCapacityErrorsNeverTruncateOrReplaceSavedWork() {
        var fixture = new Fixture(); var selected = selected(fixture);
        for (int i = 0; i < 20; i++) fixture.book = media.put(fixture.book, "entry_" + i,
            new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.TextValue("body".repeat(30))));
        var small = new LibraryDirectoryQuery(Optional.empty(), 1);
        var large = new LibraryDirectoryQuery(Optional.empty(), 20);
        var pending = LibraryReadHead.list(selected, small, fixture.source, LIMITS);
        assertTrue(LibraryReadHead.list(selected, large, fixture.source, LIMITS).pending().orElseThrow().totalTicks()
            > pending.pending().orElseThrow().totalTicks());
        // Bound the device to the actual encoded one-row snapshot, not a guessed balance number.
        var bytes = new java.io.ByteArrayOutputStream();
        var sample = pending.pending().orElseThrow().sample().orElseThrow();
        try { net.minecraft.nbt.NbtIo.write(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(
            new DataValue.RecordValue(Map.of("fence", sample.fence(), "value", sample.value())), LIMITS.archive()), new java.io.DataOutputStream(bytes)); }
        catch (java.io.IOException impossible) { throw new AssertionError(impossible); }
        var bounded = new LibraryDriveLimits(2, 1, 2, 2, 64, bytes.size(), LIMITS.archive());
        assertEquals(pending, LibraryReadHead.list(selected, small, fixture.source, bounded));
        assertEquals("buffer_full", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.list(selected, large, fixture.source, bounded)).code());
        var complete = finish(pending, fixture.source);
        assertEquals("stale_operation", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.take(complete, selected.sequence(), LIMITS)).code());
        for (var state : List.of(pending, complete)) {
            var raw = LibraryReadHeadCodec.encode(state, LIMITS);
            var value = (DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(raw, LIMITS.archive());
            assertEquals(new DataValue.IntegerValue(4), value.field("version"));
            for (int oldVersion : List.of(1, 2, 3)) {
                var fields = new LinkedHashMap<>(value.fields()); fields.put("version", new DataValue.IntegerValue(oldVersion));
                assertThrows(IllegalArgumentException.class, () -> LibraryReadHeadCodec.decode(
                    com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(new DataValue.RecordValue(fields), LIMITS.archive()), LIMITS));
            }
            assertEquals(state, LibraryReadHeadCodec.decode(raw, LIMITS));
        }
    }
    @Test void directoryCanReadEmptyLegacyAndEndPagesWithoutInitializingTheMedium() {
        var fixture = new Fixture(); var selected = selected(fixture);
        fixture.book = LibraryBookVolumesTest.VOLUMES.rename(LibraryBookMediaTest.book(), "日志");
        var original = fixture.book.copy();
        var empty = (DataValue.RecordValue)finish(LibraryReadHead.list(selected,
            new LibraryDirectoryQuery(Optional.empty(), 4), fixture.source, LIMITS), fixture.source).result().orElseThrow().value();
        assertEquals(new DataValue.ListValue(List.of()), empty.field("entries"));
        assertEquals(DataValue.NullValue.INSTANCE, empty.field("next")); assertEquals(DataValue.NullValue.INSTANCE, empty.field("version"));
        assertTrue(ItemStack.matches(original, fixture.book));
        fixture.book = fixture.newBook();
        var end = (DataValue.RecordValue)finish(LibraryReadHead.list(selected,
            new LibraryDirectoryQuery(Optional.of("reading"), 4), fixture.source, LIMITS), fixture.source).result().orElseThrow().value();
        assertEquals(new DataValue.ListValue(List.of()), end.field("entries")); assertEquals(DataValue.NullValue.INSTANCE, end.field("next"));
        assertInstanceOf(DataValue.RecordValue.class, end.field("version")); assertEquals(0, fixture.writes);
    }
    @Test void everyReturnedEntryNameCanBeUsedAsTheNextPageCursor() {
        var fixture = new Fixture(); var selected = selected(fixture);
        String longName = "a".repeat(LibraryBookVolumes.MAX_NAME + 1);
        fixture.book = media.put(fixture.book, longName, new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE));
        var first = (DataValue.RecordValue)finish(LibraryReadHead.list(selected,
            new LibraryDirectoryQuery(Optional.empty(), 1), fixture.source, LIMITS), fixture.source).result().orElseThrow().value();
        assertEquals(new DataValue.TextValue(longName), first.field("next"));
        var next = LibraryDirectoryQuery.decode(new DataValue.RecordValue(Map.of("after", first.field("next"), "count", new DataValue.IntegerValue(1))));
        var second = (DataValue.RecordValue)finish(LibraryReadHead.list(selected, next, fixture.source, LIMITS), fixture.source).result().orElseThrow().value();
        assertEquals(new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("name", new DataValue.TextValue("reading"),
            "kind", new DataValue.TextValue("DATA"))))), second.field("entries"));
        assertEquals(DataValue.NullValue.INSTANCE, second.field("next")); assertEquals(0, fixture.writes);
    }

    @Test void serialStatOfAbsentEntryCostsDeviceTimeAndReturnsTheActualCreationVersionWithoutWriting() {
        var fixture = new Fixture(); var original = fixture.book.copy();
        var positioned = selected(fixture);
        var command = new LibraryDriveCommand(TRACK.value(), new DataValue.TextValue("stat-request"),
            "stat", positioned.sequence(), new DataValue.TextValue("new_entry"));
        var admitted = command.apply(positioned, fixture.source, LIMITS);
        assertEquals(new DataValue.TextValue("accepted"), admitted.reply().field("code"));
        var pending = admitted.state();
        assertTrue(pending.result().isEmpty()); assertTrue(pending.pending().orElseThrow().remainingTicks() > 2);
        var saved = LibraryReadHeadCodec.encode(pending, LIMITS);
        assertEquals(pending, LibraryReadHeadCodec.decode(saved, LIMITS));
        var completed = finish(LibraryReadHeadCodec.decode(saved, LIMITS), fixture.source);
        assertEquals("stat", completed.result().orElseThrow().code());
        var result = (DataValue.RecordValue)completed.result().orElseThrow().value();
        assertEquals(new DataValue.BooleanValue(false), result.field("present"));
        assertEquals(DataValue.NullValue.INSTANCE, result.field("value"));
        assertEquals(media.stat(original, "new_entry"), result);
        assertTrue(ItemStack.matches(original, fixture.book)); assertEquals(0, fixture.writes);
        var taken = LibraryReadHead.take(completed, completed.sequence(), LIMITS);
        assertTrue(taken.state().result().isEmpty());
    }

    @Test void physicalReadWaitsForPositionAndFencesReplacementWithoutSearchingOtherBooks() {
        var fixture = new Fixture(); var original = fixture.book.copy();
        var head = selected(fixture);
        assertEquals(0, fixture.reads, "Seek and slot selection only inspect geometry");
        head = LibraryReadHead.read(head, "reading", fixture.source, LIMITS);
        assertTrue(head.result().isEmpty());
        assertTrue(ItemStack.matches(original, fixture.book));
        // A real component round trip, not an ItemStack/Java-object identity check.
        var restored = fixture.book.copy();
        restored.set(LibraryBookMediaTest.COMPONENT, net.minecraft.world.item.component.CustomData.of(
            fixture.book.get(LibraryBookMediaTest.COMPONENT).copyTag()));
        fixture.book = restored;
        head = finish(LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(head, LIMITS), LIMITS), fixture.source);
        assertEquals("read", head.result().orElseThrow().code());
        var value = (DataValue.RecordValue)head.result().orElseThrow().value();
        assertEquals(new DataValue.IntegerValue(43), value.field("value"));
        assertEquals(media.stat(fixture.book, "reading").field("version"), value.field("version"));
        head = LibraryReadHead.take(head, head.sequence(), LIMITS).state();
        head = LibraryReadHead.read(head, "reading", fixture.source, LIMITS);
        fixture.book = fixture.newBook(); // Same volume, entry and value; different durable medium identity.
        head = finish(head, fixture.source);
        assertEquals("changed", head.result().orElseThrow().code());
        assertEquals(DataValue.NullValue.INSTANCE, head.result().orElseThrow().value());
        assertEquals(original.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT),
            fixture.book.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT));
        assertEquals(0, fixture.writes);
    }
    @Test void statDistinguishesNullDataDeletedNamesAndNeverInitializesLegacyOrEmptyMedia() {
        var fixture = new Fixture(); var selected = selected(fixture);
        fixture.book = media.put(fixture.book, "nullable", new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE));
        var result = (DataValue.RecordValue)finish(LibraryReadHead.stat(selected, "nullable", fixture.source, LIMITS), fixture.source).result().orElseThrow().value();
        assertEquals(new DataValue.BooleanValue(true), result.field("present"));
        assertEquals(DataValue.NullValue.INSTANCE, result.field("value"));
        fixture.book = media.delete(fixture.book, "nullable");
        var deleted = (DataValue.RecordValue)finish(LibraryReadHead.stat(selected, "nullable", fixture.source, LIMITS), fixture.source).result().orElseThrow().value();
        assertEquals(new DataValue.BooleanValue(false), deleted.field("present"));
        assertNotEquals(result.field("version"), deleted.field("version"), "Deletion retains its new absence version");
        fixture.book = LibraryBookVolumesTest.VOLUMES.rename(LibraryBookMediaTest.book(), "日志");
        var before = fixture.book.copy();
        assertEquals("unversioned_medium", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.stat(selected, "absent", fixture.source, LIMITS)).code());
        assertTrue(ItemStack.matches(before, fixture.book)); assertEquals(0, fixture.writes);
    }
    @Test void statRechecksGrantReplacementAndConcurrentEntryCreationBeforeReturningAnAbsenceToken() {
        for (String change : List.of("revoke", "replace", "create", "remove")) {
            var fixture = new Fixture();
            var pending = LibraryReadHead.stat(selected(fixture), "absent", fixture.source, LIMITS);
            String expected = switch (change) {
                case "revoke" -> { fixture.allowed = false; yield "denied"; }
                case "replace" -> { fixture.book = fixture.newBook(); yield "changed"; }
                case "create" -> { fixture.book = media.put(fixture.book, "absent", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(9))); yield "changed"; }
                default -> { fixture.book = ItemStack.EMPTY; yield "missing_medium"; }
            };
            var result = finish(LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(pending, LIMITS), LIMITS), fixture.source).result().orElseThrow();
            assertEquals(expected, result.code()); assertEquals(DataValue.NullValue.INSTANCE, result.value());
            assertEquals(0, fixture.writes);
        }
    }
    @Test void statCheckpointCannotMasqueradeAsOldSchemaAndCapacityFailurePreservesIt() {
        var fixture = new Fixture(); var selected = selected(fixture);
        var pending = LibraryReadHead.stat(selected, "reading", fixture.source, LIMITS);
        var complete = finish(pending, fixture.source);
        var tiny = new LibraryDriveLimits(2, 1, 2, 2, 64, 1, LIMITS.archive());
        for (var state : List.of(pending, complete)) {
            var raw = LibraryReadHeadCodec.encode(state, LIMITS);
            var value = (DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(raw, LIMITS.archive());
            assertEquals(new DataValue.IntegerValue(2), value.field("version"));
            var fields = new LinkedHashMap<>(value.fields()); fields.put("version", new DataValue.IntegerValue(1));
            assertThrows(IllegalArgumentException.class, () -> LibraryReadHeadCodec.decode(
                com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(new DataValue.RecordValue(fields), LIMITS.archive()), LIMITS));
            assertThrows(LibraryBookAccess.Failure.class, () -> LibraryReadHeadCodec.decode(raw, tiny));
            assertEquals(state, LibraryReadHeadCodec.decode(raw, LIMITS));
        }
        assertEquals("not_positioned", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.stat(LibraryReadHead.State.empty(), "reading", fixture.source, LIMITS)).code());
    }
    @Test void revokedPermissionMissingEntryAndRemovedBookHaveDistinctFailuresAndNeverWrite() {
        var fixture = new Fixture(); var state = selected(fixture); var before = fixture.book.copy();
        assertEquals("missing_entry", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.read(state, "absent", fixture.source, LIMITS)).code());
        var pending = LibraryReadHead.read(state, "reading", fixture.source, LIMITS);
        fixture.allowed = false;
        assertEquals("denied", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.read(state, "reading", fixture.source, LIMITS)).code());
        var denied = finish(pending, fixture.source).result().orElseThrow();
        assertEquals("denied", denied.code()); assertEquals(DataValue.NullValue.INSTANCE, denied.value());
        assertTrue(ItemStack.matches(before, fixture.book));
        fixture.allowed = true; pending = LibraryReadHead.read(state, "reading", fixture.source, LIMITS);
        fixture.book = ItemStack.EMPTY;
        var removed = finish(pending, fixture.source).result().orElseThrow();
        assertEquals("missing_medium", removed.code()); assertEquals(DataValue.NullValue.INSTANCE, removed.value());
        assertEquals(0, fixture.writes);
    }
    @Test void legacyDataRemainsReadableWithoutInitializingIdentityOrEditingOriginalPages() {
        var fixture = new Fixture(); var legacy = LibraryBookMediaTest.book();
        legacy.set(LibraryBookMediaTest.COMPONENT, net.minecraft.world.item.component.CustomData.of(
            com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(new DataValue.RecordValue(Map.of(
                "version", new DataValue.IntegerValue(1), "entries", new DataValue.RecordValue(Map.of(
                    "reading", new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("DATA"),
                        "value", new DataValue.IntegerValue(43))))))), LibraryBookMediaTest.LIMITS)));
        fixture.book = LibraryBookVolumesTest.VOLUMES.rename(legacy, "日志"); var original = fixture.book.copy();
        var state = selected(fixture);
        var pending = LibraryReadHead.read(state, "reading", fixture.source, LIMITS);
        var completed = finish(LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(pending, LIMITS), LIMITS), fixture.source);
        var result = (DataValue.RecordValue)completed.result().orElseThrow().value();
        assertEquals(new DataValue.IntegerValue(43), result.field("value"));
        assertEquals(DataValue.NullValue.INSTANCE, result.field("version"));
        assertTrue(ItemStack.matches(original, fixture.book)); assertEquals(0, fixture.writes);
        assertEquals("unversioned_medium", assertThrows(LibraryBookAccess.Failure.class, () -> media.stat(fixture.book, "reading")).code());
        pending = LibraryReadHead.read(state, "reading", fixture.source, LIMITS);
        fixture.book = media.put(fixture.book, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(44)));
        assertEquals("changed", finish(pending, fixture.source).result().orElseThrow().code());
    }
    private LibraryReadHead.State selected(Fixture fixture) {
        var state = finish(LibraryReadHead.seek(LibraryReadHead.State.empty(), TRACK, fixture.source, LIMITS), fixture.source);
        state = LibraryReadHead.take(state, state.sequence(), LIMITS).state();
        state = finish(LibraryReadHead.select(state, 2, fixture.source, LIMITS), fixture.source);
        return LibraryReadHead.take(state, state.sequence(), LIMITS).state();
    }
    private static LibraryReadHead.State finish(LibraryReadHead.State state, LibraryReadHead.Source source) {
        for (int i = 0; state.pending().isPresent() && i < 10000; i++) state = LibraryReadHead.advance(state, source, LIMITS);
        assertTrue(state.pending().isEmpty()); return state;
    }
    private final class Fixture implements LibraryBookAccess.View {
        ItemStack book = newBook(); boolean allowed = true; int reads, writes;
        final LibraryBookAccess access = new LibraryBookAccess(this, media, LibraryBookVolumesTest.VOLUMES);
        final LibraryPositionedReadSource source = new LibraryPositionedReadSource(access, address -> {
            if (!TRACK.equals(address)) throw new LibraryBookAccess.Failure("missing_track");
            return SHELF;
        }, volume -> allowed && volume.equals("日志"));
        ItemStack newBook() {
            return LibraryBookVolumesTest.VOLUMES.rename(media.put(LibraryBookMediaTest.book(), "reading",
                new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43))), "日志");
        }
        public boolean mayEdit() { return true; }
        public List<BlockPos> shelves() { return List.of(BlockPos.ZERO, SHELF); }
        public List<ItemStack> contents(BlockPos shelf) {
            assertEquals(SHELF, shelf, "No directory scan or implicit volume-name resolution"); reads++;
            return List.of(ItemStack.EMPTY, ItemStack.EMPTY, book, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
        }
        public boolean replace(BlockPos shelf, int slot, ItemStack before, ItemStack after) { writes++; fail("Read-only source"); return false; }
    }
}
