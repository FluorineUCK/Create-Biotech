package com.nobodiiiii.createbiotech.content.frogportal;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A full slime-block analogue. NeoForge's piston hooks otherwise recognize only the vanilla block
 * instance, so the sticky and slime markers must be exposed explicitly for this custom block.
 */
public class FrogStomachSecretionBlock extends SlimeBlock {

	public FrogStomachSecretionBlock(Properties properties) {
		super(properties);
	}

	@Override
	public boolean isSlimeBlock(BlockState state) {
		return true;
	}

	@Override
	public boolean isStickyBlock(BlockState state) {
		return true;
	}

	@Override
	public boolean canStickTo(BlockState state, BlockState other) {
		return !other.is(Blocks.HONEY_BLOCK);
	}
}
