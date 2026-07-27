package com.nobodiiiii.createbiotech.content.frogportal;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Uses honey-block movement and sliding behaviour without its piston adhesion. Landing remains
 * completely safe, while the block's model continues to use the slime-block texture.
 */
public class FrogStomachSecretionBlock extends HoneyBlock {

	public FrogStomachSecretionBlock(Properties properties) {
		super(properties);
	}

	@Override
	public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, float fallDistance) {
		entity.playSound(SoundEvents.HONEY_BLOCK_SLIDE, 1.0f, 1.0f);
		if (!level.isClientSide)
			level.broadcastEntityEvent(entity, (byte) 54);
		entity.causeFallDamage(fallDistance, 0.0f, level.damageSources().fall());
	}
}
