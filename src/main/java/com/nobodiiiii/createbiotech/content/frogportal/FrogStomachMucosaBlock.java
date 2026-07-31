package com.nobodiiiii.createbiotech.content.frogportal;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Pink living terrain that cushions anything landing inside a Frog Stomach room. */
public class FrogStomachMucosaBlock extends Block {

	public static final MapCodec<FrogStomachMucosaBlock> CODEC = simpleCodec(FrogStomachMucosaBlock::new);

	public FrogStomachMucosaBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends FrogStomachMucosaBlock> codec() {
		return CODEC;
	}

	@Override
	public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, float fallDistance) {
		entity.causeFallDamage(fallDistance, 0.0f, level.damageSources().fall());
	}
}
