package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.nobodiiiii.createbiotech.content.powerbelt.PowerBeltWalkAnimation;

import net.minecraft.world.entity.LivingEntity;

@Mixin(LivingEntity.class)
public abstract class PowerBeltWalkAnimationMixin {

	// Sable redirects calculateEntityAnimation's call to this method, so adjust the
	// callee argument after any caller-side movement calculation has completed.
	@ModifyVariable(method = "updateWalkAnimation(F)V", at = @At("HEAD"), argsOnly = true)
	private float createBiotech$includePowerBeltSurfaceMovement(float movementDistance) {
		return PowerBeltWalkAnimation.consumeAdjustedMovement((LivingEntity) (Object) this, movementDistance);
	}
}
