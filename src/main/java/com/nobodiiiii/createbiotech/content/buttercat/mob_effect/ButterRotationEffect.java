package com.nobodiiiii.createbiotech.content.buttercat.mob_effect;

import com.nobodiiiii.createbiotech.content.buttercat.ButterRotationAccess;
import com.nobodiiiii.createbiotech.registry.CBConfigs;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

public class ButterRotationEffect extends MobEffect {
	public ButterRotationEffect() {
		super(MobEffectCategory.HARMFUL, 0xFFAA00);
	}

	@Override
	public boolean applyEffectTick(LivingEntity entity, int amplifier) {
		if (!entity.level().isClientSide)
			((ButterRotationAccess) entity).createBiotech$setButterRotationAmplifier(amplifier);
		return true;
	}

	@Override
	public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
		return true;
	}

	public static float getRotationAngularSpeed() {
		return CBConfigs.SERVER.butterCat.rotationAngularSpeed.get().floatValue();
	}
}

