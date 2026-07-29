package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nobodiiiii.createbiotech.content.powerbelt.PowerBeltWalkAnimation;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;

@Mixin(LivingEntity.class)
public abstract class PowerBeltWalkAnimationMixin {

	@Unique
	private static final float CREATE_BIOTECH_WALK_RESPONSE = 0.4f;

	@Shadow
	@Final
	public WalkAnimationState walkAnimation;

	@Inject(method = "calculateEntityAnimation(Z)V", at = @At("TAIL"))
	private void createBiotech$includePowerBeltSurfaceMovement(boolean includeHeight, CallbackInfo ci) {
		LivingEntity entity = (LivingEntity) (Object) this;
		Float surfaceMovement = PowerBeltWalkAnimation.consumeSurfaceMovement(entity);
		if (surfaceMovement == null || surfaceMovement <= 0)
			return;

		WalkAnimationStateAccessor animation = (WalkAnimationStateAccessor) (Object) walkAnimation;
		float previousSpeed = animation.createBiotech$getSpeedOld();
		float currentSpeed = animation.createBiotech$getSpeed();
		float baseTarget = (currentSpeed - previousSpeed * (1 - CREATE_BIOTECH_WALK_RESPONSE))
			/ CREATE_BIOTECH_WALK_RESPONSE;
		float baseMovement = Mth.clamp(baseTarget, 0, 1) / 4;
		float adjustedMovement = PowerBeltWalkAnimation.includeSurfaceMovement(baseMovement, surfaceMovement);
		float adjustedTarget = Math.min(adjustedMovement * 4, 1);
		float adjustedSpeed = previousSpeed
			+ (adjustedTarget - previousSpeed) * CREATE_BIOTECH_WALK_RESPONSE;

		animation.createBiotech$setSpeed(adjustedSpeed);
		animation.createBiotech$setPosition(
			animation.createBiotech$getPosition() + adjustedSpeed - currentSpeed);
	}
}
