package com.nobodiiiii.createbiotech.content.biocomputing.library;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.*;

class LibraryBookAccessTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    final LibraryBookMedia media = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS);
    @Test void explicitLocalSaveShowsComponentIdentityWithoutOverwritingExistingPages() {
        var view = new View(); var before = view.book.copy();
        var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        var handle = access.put(access.list().getFirst(), "reading", new LibraryEntry(LibraryEntry.Kind.DATA,
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue(42)));
        var original = before.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT).pages();
        var pages = view.book.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT).pages();
        var identity = ((com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.TextValue)
            media.revision(view.book).field("medium")).value();
        assertEquals(original.size() + 1, pages.size());
        assertEquals(original, pages.subList(0, original.size()));
        assertTrue(pages.getLast().raw().contains(identity));
        access.put(handle, "reading", new LibraryEntry(LibraryEntry.Kind.DATA,
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue(43)));
        assertEquals(pages, view.book.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT).pages());
        assertTrue(media.read(before).isEmpty(), "Source stack remains untouched");
    }
    @Test void physicalSelectionReadsOnlyTheSelectedMemberAndKeepsTheBookFence() {
        var target = new BlockPos(2, 0, 1); var reads = new ArrayList<BlockPos>();
        var book = new ItemStack[] {LibraryBookMediaTest.book()};
        var view = new LibraryBookAccess.View() {
            public boolean mayEdit() { return true; }
            public List<BlockPos> shelves() { return List.of(BlockPos.ZERO, target); }
            public List<ItemStack> contents(BlockPos pos) {
                reads.add(pos); assertEquals(target, pos, "Explicit positioning must not read every book to resolve a name");
                return List.of(ItemStack.EMPTY, ItemStack.EMPTY, book[0], ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
            }
            public boolean replace(BlockPos pos, int slot, ItemStack before, ItemStack after) { fail("Read must not write"); return false; }
        };
        var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        var selected = access.at(target, 2);
        assertEquals(target, selected.shelf()); assertEquals(2, selected.slot());
        assertTrue(access.read(selected).isEmpty());
        assertEquals(List.of(target, target), reads);
        assertEquals("missing_track", assertThrows(LibraryBookAccess.Failure.class,
            () -> access.at(target.above(), 2)).code());
        assertEquals("invalid_slot", assertThrows(LibraryBookAccess.Failure.class,
            () -> access.at(target, -1)).code());
        assertEquals(List.of(target, target), reads, "Invalid addresses must not touch contents");
        book[0] = media.put(book[0], "new", new LibraryEntry(LibraryEntry.Kind.DATA,
            com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.NullValue.INSTANCE));
        assertEquals("changed", assertThrows(LibraryBookAccess.Failure.class, () -> access.read(selected)).code());
    }
    @Test void staleReadOrWriteNeverTargetsAChangedPhysicalBook() {
        var view = new View(); var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        var handle = access.list().getFirst();
        view.book = media.put(view.book, "other", new LibraryEntry(LibraryEntry.Kind.DATA,
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue(2)));
        assertEquals("changed", assertThrows(LibraryBookAccess.Failure.class, () -> access.read(handle)).code());
        assertEquals("changed", assertThrows(LibraryBookAccess.Failure.class, () -> access.put(handle, "x",
            new LibraryEntry(LibraryEntry.Kind.DATA, com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.NullValue.INSTANCE))).code());
        assertEquals(0, view.writes);
    }
    @Test void actualPutAndDeleteCommitCopiedBooksWithoutChangingOriginalPages() {
        var view = new View(); var original = view.book; var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        var handle = access.list().getFirst();
        access.put(handle, "x", new LibraryEntry(LibraryEntry.Kind.DATA,
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue(42)));
        assertEquals(1, view.writes);
        assertTrue(media.read(original).isEmpty());
        LibraryBookIdentityPageTest.assertOriginalPagesPreserved(original, view.book);
        access.delete(access.list().getFirst(), "x"); assertEquals(2, view.writes); assertTrue(media.read(view.book).isEmpty());
    }
    @Test void deniedOrMissingMediumDoesNotReadAsAnEmptyLibrary() {
        var view = new View(); var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        var handle = access.list().getFirst(); view.allowed = false;
        assertEquals("denied", assertThrows(LibraryBookAccess.Failure.class, () -> access.delete(handle, "x")).code());
        assertEquals(0, view.writes); view.allowed = true; view.present = false;
        assertEquals("unloaded", assertThrows(LibraryBookAccess.Failure.class, access::list).code());
    }
    @Test void notifiedShelfRefreshDoesNotRereadUnchangedBooks() {
        var a = BlockPos.ZERO; var b = new BlockPos(1, 0, 0);
        var reads = new HashMap<BlockPos, Integer>();
        var view = new LibraryBookAccess.View() {
            public boolean mayEdit() { return true; }
            public List<BlockPos> shelves() { return List.of(a, b); }
            public List<ItemStack> contents(BlockPos pos) {
                reads.merge(pos, 1, Integer::sum);
                return List.of(LibraryBookMediaTest.book(), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
            }
            public boolean replace(BlockPos pos, int slot, ItemStack before, ItemStack after) { return false; }
        };
        var access = new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES);
        assertEquals(2, access.list().size()); assertEquals(Map.of(a, 1, b, 1), reads);
        assertEquals(2, access.listChanged(Set.of(a)).size());
        assertEquals(Map.of(a, 2, b, 1), reads);
        assertEquals(2, access.listChanged(Set.of()).size());
        assertEquals(Map.of(a, 2, b, 1), reads);
    }
    static final class View implements LibraryBookAccess.View {
        ItemStack book = LibraryBookMediaTest.book(); boolean allowed = true, present = true; int writes;
        public boolean mayEdit() { return allowed; }
        public List<BlockPos> shelves() { return List.of(BlockPos.ZERO); }
        public List<ItemStack> contents(BlockPos pos) {
            if (!present) throw new LibraryBookAccess.Failure("unloaded");
            return List.of(book.copy(), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
        }
        public boolean replace(BlockPos pos, int slot, ItemStack expected, ItemStack replacement) {
            if (!ItemStack.matches(book, expected)) return false; book = replacement.copy(); writes++; return true;
        }
    }
}
