package com.nobodiiiii.createbiotech.content.frogportal;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A visually solid wall that does not occlude the dimension's fixed night skylight. Keeping the
 * wall fully transparent to the light engine gives every vertical column in a large stomach room
 * the same sky-light input instead of letting a partially attenuated value fade to zero toward the
 * room's centre.
 */
public class FrogStomachWallBlock extends Block {

	public static final MapCodec<FrogStomachWallBlock> CODEC = simpleCodec(FrogStomachWallBlock::new);

	public FrogStomachWallBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends FrogStomachWallBlock> codec() {
		return CODEC;
	}

	@Override
	public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
		return 0;
	}

	@Override
	public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
		return true;
	}

	@Override
	public boolean useShapeForLightOcclusion(BlockState state) {
		return false;
	}
}
