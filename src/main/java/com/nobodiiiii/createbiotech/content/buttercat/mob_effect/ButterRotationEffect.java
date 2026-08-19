package com.nobodiiiii.createbiotech.content.buttercat.mob_effect;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.buttercat.ButterRotationAccess;
import com.nobodiiiii.createbiotech.registry.CBConfigs;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.NeoForgeMod;

public class ButterRotationEffect extends MobEffect {
	private static final ResourceLocation GROUND_SLOWDOWN_ID =
		CreateBiotech.asResource("butter_rotation_movement_slowdown");
	private static final ResourceLocation FLYING_SLOWDOWN_ID =
		CreateBiotech.asResource("butter_rotation_flying_slowdown");
	private static final ResourceLocation SWIMMING_SLOWDOWN_ID =
		CreateBiotech.asResource("butter_rotation_swimming_slowdown");

	public ButterRotationEffect() {
		super(MobEffectCategory.HARMFUL, 0xFFAA00);
		addSlowdownModifier(Attributes.MOVEMENT_SPEED, GROUND_SLOWDOWN_ID);
		addSlowdownModifier(Attributes.FLYING_SPEED, FLYING_SLOWDOWN_ID);
		addSlowdownModifier(NeoForgeMod.SWIM_SPEED, SWIMMING_SLOWDOWN_ID);
	}

	private void addSlowdownModifier(Holder<Attribute> attribute, ResourceLocation id) {
		addAttributeModifier(attribute, id, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
			ButterRotationEffect::getMovementSlowdownModifier);
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

	/**
	 * Amplifier 0 starts at -50%, then loses another 10% per amplifier,
	 * reaching the -100% cap at amplifier 5.
	 */
	public static double getMovementSlowdownModifier(int amplifier) {
		double slowdown = 0.5D + 0.1D * Math.max(0, amplifier);
		return -Math.min(1.0D, slowdown);
	}
}

