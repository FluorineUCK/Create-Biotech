package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Physical classification only; owns no books, recipe index, service grants or production state. */
public final class LibraryStructureWorldView implements LibraryStructureScanner.View {
    private final Level level;

    public LibraryStructureWorldView(Level level) { this.level = Objects.requireNonNull(level); }

    @Override public boolean isLoaded(BlockPos pos) { return level.isLoaded(pos); }
    @Override public boolean sameSpace(BlockPos core, BlockPos candidate) {
        return SubLevelCompat.sameSpace(level, core, candidate);
    }
    @Override public LibraryStructureScanner.MemberKind memberAt(BlockPos pos) {
        if (!isLoaded(pos)) throw new IllegalStateException("Library structure position is not loaded");
        var state = level.getBlockState(pos);
        if (state.is(CBBlocks.PATTERN_STORAGE_CORE.get()))
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                ? LibraryStructureScanner.MemberKind.CORE_LOWER : LibraryStructureScanner.MemberKind.CORE_UPPER;
        if (state.is(Blocks.BOOKSHELF)) return LibraryStructureScanner.MemberKind.BOOKSHELF;
        return state.is(Blocks.CHISELED_BOOKSHELF)
            ? LibraryStructureScanner.MemberKind.MEDIA_SHELF : LibraryStructureScanner.MemberKind.NONE;
    }
}
