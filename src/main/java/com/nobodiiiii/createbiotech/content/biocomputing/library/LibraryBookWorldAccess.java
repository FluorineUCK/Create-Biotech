package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;

/** Physical adapter created only by an authorized local management host. No positions come from client packets. */
public final class LibraryBookWorldAccess implements LibraryBookAccess.View, AutoCloseable {
    private final Level level; private final BlockPos core; private final ServerPlayer player;
    private final BooleanSupplier valid, editable;
    private final Supplier<List<BlockPos>> scan;
    private final LibraryShelfUpdates.Watch watch;
    public LibraryBookWorldAccess(Level level, BlockPos core, ServerPlayer player, BooleanSupplier valid,
        BooleanSupplier editable, Supplier<List<BlockPos>> scan) {
        this.level = Objects.requireNonNull(level); this.core = core.immutable(); this.player = Objects.requireNonNull(player);
        this.valid = valid; this.editable = editable; this.scan = scan; watch = LibraryShelfUpdates.watch(level);
    }
    public boolean valid() { return valid.getAsBoolean(); }
    public DataValue.RecordValue address(LibraryBookAccess.Handle book) {
        if (!valid()) throw new LibraryBookAccess.Failure("denied");
        var state = level.getBlockState(core);
        var facing = LibraryCoreBlock.FACING;
        if (!state.hasProperty(facing)) throw new LibraryBookAccess.Failure("unavailable");
        return new DataValue.RecordValue(Map.of(
            "track", LibraryTrackAddress.from(core, state.getValue(facing), book.shelf()).value(),
            "slot", new DataValue.IntegerValue(book.slot())));
    }
    public Set<BlockPos> changes() { return watch.consumePositions(); }
    public void observe(Collection<BlockPos> members) {
        var positions = new HashSet<BlockPos>(); positions.add(core);
        for (var member : members) {
            positions.add(member.immutable());
            for (Direction direction : Direction.values()) positions.add(member.relative(direction).immutable());
        }
        watch.observe(positions);
    }
    @Override public boolean mayEdit() { return valid() && editable.getAsBoolean(); }
    @Override public List<BlockPos> shelves() {
        if (!valid()) throw new LibraryBookAccess.Failure("denied");
        return scan.get();
    }
    @Override public List<ItemStack> contents(BlockPos shelf) {
        if (!valid()) throw new LibraryBookAccess.Failure("denied");
        var result = LibraryShelfReader.read(level, core, shelf);
        if (result.status() != LibraryShelfReader.Status.OK)
            throw new LibraryBookAccess.Failure(result.status().name().toLowerCase(Locale.ROOT));
        return result.snapshot().orElseThrow().items();
    }
    @Override public boolean replace(BlockPos shelfPos, int slot, ItemStack expected, ItemStack replacement) {
        if (!mayEdit() || !level.mayInteract(player, shelfPos) || !level.getWorldBorder().isWithinBounds(shelfPos))
            throw new LibraryBookAccess.Failure("denied");
        contents(shelfPos); // Loaded-only and space checks must precede the live getter.
        var entity = SubLevelCompat.getLoadedBlockEntity(level, shelfPos);
        if (!(entity instanceof ChiseledBookShelfBlockEntity shelf) || !ItemStack.matches(shelf.getItem(slot), expected)) return false;
        shelf.setItem(slot, replacement.copy()); shelf.setChanged();
        // Also notify for component-only updates. The ordinary vanilla write emits BLOCK_CHANGE.
        LibraryShelfUpdates.changed(level, shelfPos);
        return true;
    }
    @Override public void close() { LibraryShelfUpdates.forget(level, watch); }
}
