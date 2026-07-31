package com.nobodiiiii.createbiotech.content.frogportal;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.block.Block;

/**
 * Indestructible, fully opaque shell enclosing each generated Frog Stomach room.
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
}
