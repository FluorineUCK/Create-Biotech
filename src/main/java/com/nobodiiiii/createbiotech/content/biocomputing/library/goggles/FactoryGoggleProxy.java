package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;

import java.util.function.Function;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Embedded Panel faces own their BE; only physical upper halves proxy to their lower owner. */
public final class FactoryGoggleProxy {
	private FactoryGoggleProxy() {}
	public static BlockPos resolve(Level level, BlockPos pos) { return resolve(level::getBlockState, pos); }

	public static BlockPos resolve(Function<BlockPos, BlockState> states, BlockPos pos) {
		var state = states.apply(pos);
		if (!(state.getBlock() instanceof ComputerBlock || state.getBlock() instanceof LibraryCoreBlock)
				|| state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.UPPER) return pos;
		var lower = states.apply(pos.below());
		if (lower.getBlock() == state.getBlock()
				&& lower.getValue(BlockStateProperties.HORIZONTAL_FACING) == state.getValue(BlockStateProperties.HORIZONTAL_FACING)
				&& lower.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) return pos.below();
		return pos;
	}
}
