package com.nobodiiiii.createbiotech.content.buttercat.block;

import com.nobodiiiii.createbiotech.registry.CBMobEffects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class ButterBlock extends Block {
	private static final int EFFECT_DURATION_TICKS = 5;
	private static final int EFFECT_REFRESH_THRESHOLD_TICKS = 2;

	public ButterBlock(Properties properties) {
		super(properties);
	}

	@Override
	public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
		super.stepOn(level, pos, state, entity);
		if (level.isClientSide || !(entity instanceof LivingEntity livingEntity))
			return;

		applyRotation(livingEntity, getRotationAmplifier(livingEntity, level.getGameTime()));
	}

	protected int getRotationAmplifier(LivingEntity entity, long gameTime) {
		return 0;
	}

	private static void applyRotation(LivingEntity entity, int amplifier) {
		MobEffectInstance current = entity.getEffect(CBMobEffects.BUTTER_ROTATION.getDelegate());
		if (current != null && (current.getAmplifier() > amplifier
			|| current.getAmplifier() == amplifier && current.getDuration() > EFFECT_REFRESH_THRESHOLD_TICKS))
			return;

		entity.addEffect(new MobEffectInstance(CBMobEffects.BUTTER_ROTATION.getDelegate(),
			EFFECT_DURATION_TICKS, amplifier));
	}
}
