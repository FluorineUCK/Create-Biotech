package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;

/** Internal physical access after the host's structure and permission checks; not a world-access node. */
public final class LibraryShelfReader {
    // Vanilla physical slot geometry, not configurable data capacity or a recipe-page limit.
    private static final int SLOT_COUNT = 6;

    private LibraryShelfReader() {}

    public enum Status { OK, UNLOADED, MISSING, WRONG_SPACE, INVALID_CONTENTS, PROVIDER_FAILURE }

    public interface View {
        boolean loaded(BlockPos pos);
        boolean sameSpace(BlockPos core, BlockPos target);
        ChiseledBookShelfBlockEntity shelf(BlockPos pos);
    }

    /** Contains only owned item copies. Reading this snapshot never grants future access to that shelf. */
    public static final class Snapshot {
        private final BlockPos position;
        private final List<ItemStack> items;

        private Snapshot(BlockPos position, List<ItemStack> ownedCopies) {
            this.position = position.immutable();
            this.items = ownedCopies;
        }

        public BlockPos position() { return position; }
        public List<ItemStack> items() { return items.stream().map(ItemStack::copy).toList(); }
    }

    public record Result(Status status, Optional<Snapshot> snapshot) {
        public Result {
            Objects.requireNonNull(status); Objects.requireNonNull(snapshot);
            if ((status == Status.OK) != snapshot.isPresent())
                throw new IllegalArgumentException("Only a successful shelf read has contents");
        }
    }

    public static Result read(Level level, BlockPos core, BlockPos target) {
        Objects.requireNonNull(level, "level");
        return read(new View() {
            public boolean loaded(BlockPos pos) { return level.isLoaded(pos); }
            public boolean sameSpace(BlockPos origin, BlockPos pos) { return SubLevelCompat.sameSpace(level, origin, pos); }
            public ChiseledBookShelfBlockEntity shelf(BlockPos pos) {
                var entity = SubLevelCompat.getLoadedBlockEntity(level, pos);
                return entity instanceof ChiseledBookShelfBlockEntity shelf ? shelf : null;
            }
        }, core, target);
    }

    public static Result read(View view, BlockPos core, BlockPos target) {
        Objects.requireNonNull(view, "view");
        core = Objects.requireNonNull(core, "core").immutable();
        target = Objects.requireNonNull(target, "target").immutable();
        try {
            if (!view.loaded(core) || !view.loaded(target)) return failure(Status.UNLOADED);
            if (!view.sameSpace(core, target)) return failure(Status.WRONG_SPACE);
            var shelf = view.shelf(target);
            if (shelf == null || shelf.isRemoved()) return failure(Status.MISSING);
            return new Result(Status.OK, Optional.of(new Snapshot(target, copyContents(shelf))));
        } catch (InvalidContents invalid) {
            return failure(Status.INVALID_CONTENTS);
        } catch (RuntimeException failure) {
            return failure(Status.PROVIDER_FAILURE);
        }
    }

    /** Reused by existing authorized manual book workflows, without retaining container-owned stacks. */
    public static List<ItemStack> copyContents(ChiseledBookShelfBlockEntity shelf) {
        Objects.requireNonNull(shelf, "shelf");
        if (shelf.getContainerSize() != SLOT_COUNT) throw new InvalidContents();
        var copies = new ArrayList<ItemStack>(SLOT_COUNT);
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            var stack = shelf.getItem(slot);
            if (stack == null) throw new InvalidContents();
            copies.add(stack.copy());
        }
        return List.copyOf(copies);
    }

    private static Result failure(Status status) { return new Result(status, Optional.empty()); }
    private static final class InvalidContents extends IllegalArgumentException {}
}
