package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryPositionedWriteSource;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.*;

class LibraryPositionedWriteSourceTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    static final BlockPos SHELF = new BlockPos(4, 0, 1);
    static final LibraryTrackAddress TRACK = new LibraryTrackAddress(1, 0, 0);
    final LibraryBookMedia media = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS);
    @Test void selectedSlotWriteIsPreparedWithoutSideEffectsAndRechecksTheLiveGrantAtCommit() {
        var fixture = new Fixture(); var before = fixture.book.copy(); var operation = fixture.operation();
        var prepared = fixture.source.prepare(TRACK, 2, operation);
        assertFalse(prepared.replay()); assertEquals(0, fixture.writes); assertTrue(ItemStack.matches(before, fixture.book));
        fixture.grant = LibraryServiceConfiguration.Access.READ_ONLY;
        assertEquals("readonly", assertThrows(LibraryBookAccess.Failure.class, prepared.commit()::run).code());
        assertEquals(0, fixture.writes); assertTrue(ItemStack.matches(before, fixture.book));
        fixture.grant = LibraryServiceConfiguration.Access.READ_WRITE;
        prepared.commit().run(); assertEquals(1, fixture.writes);
        assertEquals(new DataValue.IntegerValue(44), media.read(fixture.book).get("reading").value());
        var replay = fixture.source.prepare(TRACK, 2, operation);
        assertTrue(replay.replay()); assertEquals(prepared.reply(), replay.reply()); replay.commit().run();
        assertEquals(1, fixture.writes, "No directory scan, RPC endpoint or second write is needed");
    }
    @Test void unloadBookReplacementLocalEditAndGrantRevocationCannotCommitAnEarlierCandidate() {
        for (int scenario = 0; scenario < 4; scenario++) {
            var fixture = new Fixture(); var operation = fixture.operation(); var prepared = fixture.source.prepare(TRACK, 2, operation);
            String expected;
            switch (scenario) {
                case 0 -> { fixture.available = false; expected = "unloaded"; }
                case 1 -> { fixture.book = new Fixture().book; expected = "changed"; }
                case 2 -> { fixture.book = media.put(fixture.book, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(99))); expected = "changed"; }
                default -> { fixture.grant = LibraryServiceConfiguration.Access.CLOSED; expected = "denied"; }
            }
            var current = fixture.book.copy();
            assertEquals(expected, assertThrows(LibraryBookAccess.Failure.class, prepared.commit()::run).code());
            assertTrue(ItemStack.matches(current, fixture.book)); assertEquals(0, fixture.writes);
        }
    }
    @Test void uncertainPhysicalCommitReplaysTheBookReceiptAndExplicitReleasePreservesVersions() {
        var fixture = new Fixture(); var operation = fixture.operation(); fixture.failAfterReplace = true;
        var prepared = fixture.source.prepare(TRACK, 2, operation);
        assertThrows(IllegalStateException.class, prepared.commit()::run);
        assertEquals(1, fixture.writes);
        var written = fixture.book.copy(); fixture.failAfterReplace = false;
        // Reconstruct the adapter as on recovery; the receipt lives in the book, not the provider object.
        var recovered = new LibraryPositionedWriteSource(new LibraryBookAccess(fixture, media, LibraryBookVolumesTest.VOLUMES),
            track -> SHELF, volume -> fixture.grant);
        var replay = recovered.prepare(TRACK, 2, LibraryDeviceWrite.decode(operation.value()));
        assertTrue(replay.replay()); assertEquals(prepared.reply(), replay.reply()); replay.commit().run();
        assertEquals(1, fixture.writes); assertTrue(ItemStack.matches(written, fixture.book));
        fixture.grant = LibraryServiceConfiguration.Access.READ_ONLY;
        assertEquals("readonly", assertThrows(LibraryBookAccess.Failure.class, () -> recovered.release(TRACK, 2, operation)).code());
        assertEquals(1, fixture.writes);
        fixture.grant = LibraryServiceConfiguration.Access.READ_WRITE; recovered.release(TRACK, 2, operation);
        assertEquals(2, fixture.writes);
        assertEquals(media.stat(written, "reading"), media.stat(fixture.book, "reading"));
        assertEquals("stale_version", assertThrows(LibraryBookAccess.Failure.class, () -> recovered.prepare(TRACK, 2, operation)).code());
    }
    @Test void twoPreparedCandidatesDoNotSilentlyOverwriteAndThisCapabilityDoesNotClaimBatchArbitration() {
        var fixture = new Fixture(); var before = fixture.book.copy();
        var first = fixture.source.prepare(TRACK, 2, fixture.operation());
        var second = fixture.source.prepare(TRACK, 2, fixture.operation());
        assertEquals(0, fixture.writes); assertTrue(ItemStack.matches(before, fixture.book));
        // The future host MUST reject both same-tick contenders before either commit, not choose this test order.
        first.commit().run();
        assertEquals("changed", assertThrows(LibraryBookAccess.Failure.class, second.commit()::run).code());
        assertEquals(1, fixture.writes, "Physical CAS is only the last fence, not the all-fail competition rule");
    }
    final class Fixture implements LibraryBookAccess.View {
        ItemStack book = LibraryBookVolumesTest.VOLUMES.rename(media.upgrade(LibraryBookVersionsTest.legacy()), "日志");
        LibraryServiceConfiguration.Access grant = LibraryServiceConfiguration.Access.READ_WRITE;
        boolean available = true, failAfterReplace;
        int writes;
        final LibraryBookAccess access = new LibraryBookAccess(this, media, LibraryBookVolumesTest.VOLUMES);
        final LibraryPositionedWriteSource source = new LibraryPositionedWriteSource(access, address -> {
            if (!available) throw new LibraryBookAccess.Failure("unloaded");
            if (!TRACK.equals(address)) throw new LibraryBookAccess.Failure("missing_track");
            return SHELF;
        }, volume -> volume.equals("日志") ? grant : LibraryServiceConfiguration.Access.CLOSED);
        LibraryDeviceWrite operation() {
            return new LibraryDeviceWrite(UUID.randomUUID(), 3, new LibraryMutation("日志", "reading",
                LibraryMutation.Version.decode(media.stat(book, "reading").field("version")),
                Optional.of(new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(44)))));
        }
        public boolean mayEdit() { return available; }
        public List<BlockPos> shelves() { return List.of(BlockPos.ZERO, SHELF); }
        public List<ItemStack> contents(BlockPos shelf) {
            assertEquals(SHELF, shelf, "Physical addressing must not read unrelated books");
            if (!available) throw new LibraryBookAccess.Failure("unloaded");
            return List.of(ItemStack.EMPTY, ItemStack.EMPTY, book, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
        }
        public boolean replace(BlockPos shelf, int slot, ItemStack expected, ItemStack replacement) {
            assertEquals(SHELF, shelf); assertEquals(2, slot);
            if (!ItemStack.matches(book, expected)) return false;
            book = replacement.copy(); writes++;
            if (failAfterReplace) throw new IllegalStateException("Observer failed after the physical commit");
            return true;
        }
    }
}
