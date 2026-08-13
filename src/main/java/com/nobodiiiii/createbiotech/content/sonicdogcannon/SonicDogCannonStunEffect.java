package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class SonicDogCannonStunEffect extends MobEffect {
	private static final ResourceLocation STUN_SPEED = CreateBiotech.asResource("stun_speed");

	public SonicDogCannonStunEffect() {
		super(MobEffectCategory.HARMFUL, 0xFF8C00);
		addAttributeModifier(Attributes.MOVEMENT_SPEED, STUN_SPEED, -0.5d, AttributeModifier.Operation.ADD_VALUE);
	}

	@Override
	public boolean applyEffectTick(LivingEntity entity, int amplifier) {
		return true;
	}

	@Override
	public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
		return duration > 0;
	}
}
