package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.*;

class LibraryVolumeAccessTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    final LibraryBookMedia media = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS);
    final LibraryBookVolumes volumes = LibraryBookVolumesTest.VOLUMES;
    @Test void resolvesExactNamesAndSameEntryNameCanExistInDifferentVolumes() {
        var view = new View(); var access = access(view);
        view.books[0] = media.put(volumes.rename(view.books[0], "日志"), "reading", entry(1));
        view.books[1] = media.put(volumes.rename(view.books[1], "Archive"), "reading", entry(2));
        assertEquals(entry(1), access.read(access.findVolume("日志")).get("reading"));
        assertEquals(entry(2), access.read(access.findVolume("Archive")).get("reading"));
        assertEquals("missing_volume", failure(() -> access.findVolume("archive")));
        assertEquals("missing_volume", failure(() -> access.findVolume("missing")));
    }
    @Test void duplicateVolumesNeverPickAWinnerAndCanBeRepairedByPhysicalSelection() {
        var view = new View(); var access = access(view);
        view.books[0] = volumes.rename(view.books[0], "日志"); view.books[1] = view.books[0].copy();
        assertEquals("duplicate_volume", failure(() -> access.findVolume("日志"))); assertEquals(0, view.writes);
        var repaired = access.renameVolume(access.list().getLast(), "备份");
        assertEquals("备份", access.volumeName(repaired).orElseThrow());
        assertEquals(0, access.findVolume("日志").slot()); assertEquals(1, access.findVolume("备份").slot());
    }
    @Test void namingCannotOverwriteAnotherVolumeOrUseAStaleHandle() {
        var view = new View(); var access = access(view); var handles = access.list();
        access.renameVolume(handles.getFirst(), "日志");
        assertEquals("duplicate_volume", failure(() -> access.renameVolume(handles.getLast(), "日志")));
        assertEquals("changed", failure(() -> access.renameVolume(handles.getFirst(), "changed")));
        assertEquals(1, view.writes); assertTrue(volumes.name(view.books[1]).isEmpty());
    }
    @Test void physicalMoveKeepsNameButOldSlotHandleCannotAccessIt() {
        var view = new View(); var access = access(view);
        view.books[0] = volumes.rename(view.books[0], "日志"); var old = access.findVolume("日志");
        view.books[5] = view.books[0]; view.books[0] = ItemStack.EMPTY;
        assertEquals("changed", failure(() -> access.read(old))); assertEquals(5, access.findVolume("日志").slot());
        view.books[5] = ItemStack.EMPTY; assertEquals("missing_volume", failure(() -> access.findVolume("日志")));
    }
    @Test void malformedUnavailableAndDeniedAreNotAnEmptyDirectory() {
        var view = new View(); var access = access(view); var first = access.list().getFirst();
        view.allowed = false; assertEquals("denied", failure(() -> access.renameVolume(first, "日志")));
        view.allowed = true; view.books[1].set(LibraryBookVolumesTest.COMPONENT, " ");
        assertEquals("invalid_volume", failure(() -> access.findVolume("日志")));
        assertEquals("invalid_volume", failure(() -> access.renameVolume(first, "日志")));
        view.loaded = false; assertEquals("unloaded", failure(() -> access.findVolume("日志"))); assertEquals(0, view.writes);
    }
    @Test void sessionRenameKeepsEntryDraftAddressAndUpdatesTheSelectedSnapshot() {
        var view = new View(); var access = access(view); var session = new LibraryBookSession(access);
        session.refresh(); session.select(0); long before = session.revision();
        assertEquals("ok", session.renameVolume(before, "日志"));
        assertEquals(new DataValue.TextValue("日志"), session.snapshot(null).field("volume"));
        assertEquals("changed", session.renameVolume(before, "stale"));
        assertEquals("ok", session.mutate(session.revision(), false, "reading", entry(43)));
        assertEquals("日志", volumes.name(view.books[0]).orElseThrow()); assertEquals(entry(43), session.read("reading"));
        session.refresh(); session.select(0); assertEquals(new DataValue.TextValue("日志"), session.snapshot(null).field("volume"));
    }
    private LibraryBookAccess access(View view) { return new LibraryBookAccess(view, media, volumes); }
    private static LibraryEntry entry(int value) { return new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(value)); }
    private static String failure(org.junit.jupiter.api.function.Executable action) { return assertThrows(LibraryBookAccess.Failure.class, action).code(); }
    static final class View implements LibraryBookAccess.View {
        final ItemStack[] books = { LibraryBookMediaTest.book(), LibraryBookMediaTest.book(), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY };
        boolean allowed = true, loaded = true; int writes;
        public boolean mayEdit() { return allowed; }
        public List<BlockPos> shelves() { return List.of(BlockPos.ZERO); }
        public List<ItemStack> contents(BlockPos pos) {
            if (!loaded) throw new LibraryBookAccess.Failure("unloaded");
            return Arrays.stream(books).map(ItemStack::copy).toList();
        }
        public boolean replace(BlockPos pos, int slot, ItemStack expected, ItemStack replacement) {
            if (!ItemStack.matches(books[slot], expected)) return false;
            books[slot] = replacement.copy(); writes++; return true;
        }
    }
}
