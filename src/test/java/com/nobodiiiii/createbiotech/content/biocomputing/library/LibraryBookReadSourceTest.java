package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.*;

class LibraryBookReadSourceTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    @Test void eventInvalidationUpdatesNamesWithoutPollingUnchangedBookContents() {
        var book = LibraryBookVolumesTest.VOLUMES.rename(LibraryBookMediaTest.book(), "日志");
        var stacks = new ArrayList<>(List.of(book, ItemStack.EMPTY)); int[] reads = {0};
        var view = new LibraryBookAccess.View() {
            public boolean mayEdit() { return false; }
            public List<BlockPos> shelves() { return List.of(BlockPos.ZERO); }
            public List<ItemStack> contents(BlockPos pos) { reads[0]++; return List.copyOf(stacks); }
            public boolean replace(BlockPos pos, int slot, ItemStack before, ItemStack after) { throw new AssertionError("write"); }
        };
        var dirty = new HashSet<BlockPos>();
        var source = new LibraryBookReadSource(new LibraryBookAccess(view,
            new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS), LibraryBookVolumesTest.VOLUMES),
            () -> { var result = Set.copyOf(dirty); dirty.clear(); return result; });
        assertEquals("ok", source.snapshot().status("日志")); assertEquals(1, reads[0]);
        assertEquals("missing_volume", source.snapshot().status("private")); assertEquals(1, reads[0]);
        stacks.set(1, book.copy()); dirty.add(BlockPos.ZERO);
        var duplicate = source.snapshot(); assertEquals("duplicate_volume", duplicate.status("日志")); assertEquals(2, reads[0]);
        assertEquals("duplicate_volume", assertThrows(LibraryBookAccess.Failure.class, () -> duplicate.read("日志")).code());
        assertEquals("duplicate_volume", assertThrows(LibraryBookAccess.Failure.class, () -> duplicate.stat("日志", "reading")).code());
        stacks.set(0, ItemStack.EMPTY); stacks.set(1, ItemStack.EMPTY); dirty.add(BlockPos.ZERO);
        assertEquals("missing_volume", source.snapshot().status("日志")); assertEquals(3, reads[0]);
    }
    @Test void failedRefreshMustNotForgetTheConsumedInvalidation() {
        var view = new LibraryBookAccessTest.View(); view.book = LibraryBookVolumesTest.VOLUMES.rename(view.book, "old");
        var dirty = new HashSet<BlockPos>();
        var source = new LibraryBookReadSource(new LibraryBookAccess(view,
            new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS), LibraryBookVolumesTest.VOLUMES),
            () -> { var result = Set.copyOf(dirty); dirty.clear(); return result; });
        assertEquals("ok", source.snapshot().status("old"));
        view.book = LibraryBookVolumesTest.VOLUMES.rename(view.book, "new"); view.present = false; dirty.add(BlockPos.ZERO);
        assertThrows(LibraryBookAccess.Failure.class, source::snapshot);
        view.present = true; // Provider recovers without emitting an additional block event.
        assertEquals("ok", source.snapshot().status("new"));
        assertEquals("missing_volume", source.snapshot().status("old"));
    }
    @Test void staleSnapshotCannotReadAReplacedBook() {
        var view = new LibraryBookAccessTest.View(); view.book = LibraryBookVolumesTest.VOLUMES.rename(view.book, "日志");
        var source = new LibraryBookReadSource(new LibraryBookAccess(view,
            new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS), LibraryBookVolumesTest.VOLUMES), Set::of);
        var snapshot = source.snapshot(); view.book = LibraryBookVolumesTest.VOLUMES.rename(view.book, "other");
        assertEquals("changed", assertThrows(LibraryBookAccess.Failure.class, () -> snapshot.read("日志")).code());
        assertEquals("changed", assertThrows(LibraryBookAccess.Failure.class, () -> snapshot.stat("日志", "reading")).code());
        assertEquals(0, view.writes);
    }
}
