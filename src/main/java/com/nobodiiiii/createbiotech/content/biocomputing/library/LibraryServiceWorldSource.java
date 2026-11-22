package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryWriteService;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.registry.CBDataComponents;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;

/** Autonomous storage adapter: service grants authorize only dedicated book entries in this valid structure. */
public final class LibraryServiceWorldSource implements LibraryWriteService.Source, AutoCloseable {
    private final Level level;
    private final LibraryShelfUpdates.Watch watch;
    private final LibraryBookReadSource source;
    private final LibraryBookAccess access;
    public LibraryServiceWorldSource(Level level, BlockPos core, BooleanSupplier available,
        Supplier<LibraryStructureScanner.ScanResult> scanner, ArchiveLimits archive, int bookBytes) {
        this.level = Objects.requireNonNull(level); watch = LibraryShelfUpdates.watch(level);
        observe(List.of(core));
        var view = new LibraryBookAccess.View() {
            public boolean mayEdit() { return available.getAsBoolean(); }
            public List<BlockPos> shelves() {
                if (!available.getAsBoolean()) throw new LibraryBookAccess.Failure("unavailable");
                var result = scanner.get();
                if (result.snapshot() != null) observe(result.snapshot().members());
                if (result.state() != LibraryStructureScanner.State.VALID || result.snapshot() == null)
                    throw new LibraryBookAccess.Failure(result.state() == LibraryStructureScanner.State.PARTIAL
                        ? "unloaded" : result.state().name().toLowerCase(Locale.ROOT));
                return result.snapshot().mediaShelves();
            }
            public List<ItemStack> contents(BlockPos shelf) {
                if (!available.getAsBoolean() || !level.getWorldBorder().isWithinBounds(shelf)) throw new LibraryBookAccess.Failure("denied");
                var read = LibraryShelfReader.read(level, core, shelf);
                if (read.status() != LibraryShelfReader.Status.OK) throw new LibraryBookAccess.Failure(read.status().name().toLowerCase(Locale.ROOT));
                return read.snapshot().orElseThrow().items();
            }
            public boolean replace(BlockPos pos, int slot, ItemStack before, ItemStack after) {
                if (!mayEdit() || !shelves().contains(pos)) throw new LibraryBookAccess.Failure("denied");
                contents(pos); // Loaded, world-border and same-space checks before touching the live slot.
                var entity = SubLevelCompat.getLoadedBlockEntity(level, pos);
                if (!(entity instanceof ChiseledBookShelfBlockEntity shelf) || !ItemStack.matches(shelf.getItem(slot), before)) return false;
                shelf.setItem(slot, after.copy()); shelf.setChanged(); LibraryShelfUpdates.changed(level, pos);
                return true;
            }
        };
        access = new LibraryBookAccess(view,
            new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), archive, bookBytes),
            new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get()));
        source = new LibraryBookReadSource(access, watch::consumePositions);
    }
    /** Explicit slot path reuses physical fences, not the name-searching service directory. */
    public com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryPositionedReadSource positioned(
        Function<LibraryTrackAddress, BlockPos> locate, Predicate<String> mayRead) {
        return new com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryPositionedReadSource(access, locate, mayRead);
    }
    /** Transient completion preparation over the same physical book adapter, never an instant player write API. */
    public com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryPositionedReadSource driveSource(
        Function<LibraryTrackAddress, BlockPos> locate,
        Function<String, com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceConfiguration.Access> grant) {
        return driveSource(locate, grant, null);
    }
    public com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryPositionedReadSource driveSource(
        Function<LibraryTrackAddress, BlockPos> locate,
        Function<String, com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceConfiguration.Access> grant,
        com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryPositionedReadSource.RecipeReader recipes) {
        return new com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryPositionedReadSource(access, locate,
            volume -> grant.apply(volume) != com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceConfiguration.Access.CLOSED,
            positionedWriter(locate, grant), recipes);
    }
    public com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryPositionedWriteSource positionedWriter(
        Function<LibraryTrackAddress, BlockPos> locate,
        Function<String, com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceConfiguration.Access> grant) {
        return new com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryPositionedWriteSource(access, locate, grant);
    }
    private void observe(Collection<BlockPos> members) {
        var positions = new HashSet<BlockPos>();
        for (var member : members) {
            positions.add(member.immutable());
            for (Direction face : Direction.values()) positions.add(member.relative(face).immutable());
        }
        watch.observe(positions);
    }
    @Override public LibraryWriteService.Directory snapshot() { return source.snapshot(); }
    @Override public void close() { LibraryShelfUpdates.forget(level, watch); }
}
