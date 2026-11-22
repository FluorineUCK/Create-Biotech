package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LibraryShelfReaderTest {
    private static final BlockPos CORE = BlockPos.ZERO;
    private static final BlockPos SHELF = new BlockPos(1, 0, 0);

    @BeforeAll static void bootstrap() throws Exception {
        var type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
    }

    @Test void retainsAllPhysicalSlotsAndOwnsCopiesInBothDirections() {
        var view = new View();
        var book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.CUSTOM_NAME, Component.literal("original"));
        view.shelf.slots.set(4, book);
        var result = LibraryShelfReader.read(view, CORE, SHELF);
        assertEquals(LibraryShelfReader.Status.OK, result.status());
        var snapshot = result.snapshot().orElseThrow();
        assertEquals(SHELF, snapshot.position());
        assertEquals(6, snapshot.items().size());
        assertTrue(snapshot.items().get(0).isEmpty());
        assertTrue(snapshot.items().get(4).is(Items.WRITABLE_BOOK));
        var returned = snapshot.items();
        assertThrows(UnsupportedOperationException.class, () -> returned.set(0, ItemStack.EMPTY));
        returned.get(4).setCount(3);
        returned.get(4).set(DataComponents.CUSTOM_NAME, Component.literal("copy edit"));
        assertEquals(1, book.getCount());
        assertEquals("original", book.get(DataComponents.CUSTOM_NAME).getString());
        book.set(DataComponents.CUSTOM_NAME, Component.literal("source edit"));
        assertEquals(1, snapshot.items().get(4).getCount());
        assertEquals("original", snapshot.items().get(4).get(DataComponents.CUSTOM_NAME).getString());
    }

    @Test void takingABookChangesTheNextReadRatherThanReusingAnOldSnapshot() {
        var view = new View(); view.shelf.slots.set(1, new ItemStack(Items.WRITABLE_BOOK));
        var first = LibraryShelfReader.read(view, CORE, SHELF).snapshot().orElseThrow();
        var taken = view.shelf.slots.set(1, ItemStack.EMPTY);
        var second = LibraryShelfReader.read(view, CORE, SHELF).snapshot().orElseThrow();
        assertTrue(first.items().get(1).is(Items.WRITABLE_BOOK));
        assertTrue(second.items().stream().allMatch(ItemStack::isEmpty));
        view.shelf.slots.set(5, taken);
        var third = LibraryShelfReader.read(view, CORE, SHELF).snapshot().orElseThrow();
        assertTrue(third.items().get(1).isEmpty());
        assertTrue(third.items().get(5).is(Items.WRITABLE_BOOK));
    }

    @Test void unloadedCoreNeverChecksOrObtainsTheTarget() {
        var view = new View(); view.unloaded = CORE;
        assertFailure(view, LibraryShelfReader.Status.UNLOADED);
        assertEquals(List.of("loaded:" + CORE), view.calls);
    }

    @Test void unloadedShelfNeverObtainsBlockEntityOrComponents() {
        var view = new View(); view.unloaded = SHELF;
        assertFailure(view, LibraryShelfReader.Status.UNLOADED);
        assertEquals(List.of("loaded:" + CORE, "loaded:" + SHELF), view.calls);
        assertEquals(0, view.shelf.reads);
    }

    @Test void wrongSpaceIsRejectedBeforeReadingShelfContents() {
        var view = new View(); view.sameSpace = false;
        assertFailure(view, LibraryShelfReader.Status.WRONG_SPACE);
        assertFalse(view.calls.contains("shelf"));
        assertEquals(0, view.shelf.reads);
    }

    @Test void missingShelfIsNotASuccessfulEmptyLibrary() {
        var view = new View(); view.shelf = null;
        assertFailure(view, LibraryShelfReader.Status.MISSING);
        var empty = LibraryShelfReader.read(new View(), CORE, SHELF);
        assertEquals(LibraryShelfReader.Status.OK, empty.status());
        assertEquals(6, empty.snapshot().orElseThrow().items().size());
    }

    @Test void invalidPhysicalSlotCountIsNotTruncatedOrPadded() {
        var view = new View(); view.shelf.size = 7;
        assertFailure(view, LibraryShelfReader.Status.INVALID_CONTENTS);
        assertEquals(0, view.shelf.reads);
    }

    @Test void providerFailureDoesNotReturnAPartialSnapshot() {
        var view = new View(); view.shelf.failAt = 3;
        assertFailure(view, LibraryShelfReader.Status.PROVIDER_FAILURE);
        assertEquals(4, view.shelf.reads);
    }

    @Test void mutablePositionAndReturnedSlotListsCannotChangeTheCapturedAddress() {
        var pos = new BlockPos.MutableBlockPos(1, 0, 0);
        var snapshot = LibraryShelfReader.read(new View(), CORE, pos).snapshot().orElseThrow();
        pos.set(9, 9, 9);
        assertEquals(SHELF, snapshot.position());
    }

    @Test void removedShelfAndNullSlotAreNotSuccessfulEmptyReads() {
        var view = new View(); view.shelf.setRemoved();
        assertFailure(view, LibraryShelfReader.Status.MISSING);
        view = new View(); view.shelf.slots.set(2, null);
        assertFailure(view, LibraryShelfReader.Status.INVALID_CONTENTS);
    }

    @Test void aFailureDuringPhysicalLookupDoesNotEscapeOrProduceContents() {
        var result = LibraryShelfReader.read(new LibraryShelfReader.View() {
            public boolean loaded(BlockPos pos) { throw new IllegalStateException("lookup failed"); }
            public boolean sameSpace(BlockPos core, BlockPos target) { throw new AssertionError("must not inspect space"); }
            public ChiseledBookShelfBlockEntity shelf(BlockPos pos) { throw new AssertionError("must not obtain a shelf"); }
        }, CORE, SHELF);
        assertEquals(LibraryShelfReader.Status.PROVIDER_FAILURE, result.status());
        assertTrue(result.snapshot().isEmpty());
    }

    @Test void successAndFailureCannotCarryContradictorySnapshotStates() {
        var snapshot = LibraryShelfReader.read(new View(), CORE, SHELF).snapshot();
        assertThrows(IllegalArgumentException.class,
            () -> new LibraryShelfReader.Result(LibraryShelfReader.Status.MISSING, snapshot));
        assertThrows(IllegalArgumentException.class,
            () -> new LibraryShelfReader.Result(LibraryShelfReader.Status.OK, java.util.Optional.empty()));
    }

    private static void assertFailure(View view, LibraryShelfReader.Status expected) {
        var result = LibraryShelfReader.read(view, CORE, SHELF);
        assertEquals(expected, result.status());
        assertTrue(result.snapshot().isEmpty());
    }

    private static final class View implements LibraryShelfReader.View {
        final List<String> calls = new ArrayList<>();
        Shelf shelf = new Shelf(); BlockPos unloaded; boolean sameSpace = true;
        public boolean loaded(BlockPos pos) { calls.add("loaded:" + pos); return !pos.equals(unloaded); }
        public boolean sameSpace(BlockPos core, BlockPos target) { calls.add("space"); return sameSpace; }
        public ChiseledBookShelfBlockEntity shelf(BlockPos pos) { calls.add("shelf"); return shelf; }
    }

    private static final class Shelf extends ChiseledBookShelfBlockEntity {
        final List<ItemStack> slots = new ArrayList<>(List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
            ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
        int size = 6, reads, failAt = -1;
        Shelf() { super(SHELF, Blocks.CHISELED_BOOKSHELF.defaultBlockState()); }
        @Override public int getContainerSize() { return size; }
        @Override public ItemStack getItem(int slot) {
            reads++;
            if (slot == failAt) throw new IllegalStateException("injected shelf failure");
            return slots.get(slot);
        }
    }
}
