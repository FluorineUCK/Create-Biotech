package com.nobodiiiii.createbiotech.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityRenderTime;

import net.minecraft.client.DeltaTracker;

@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerTimerMixin {

	@ModifyReturnValue(method = "getGameTimeDeltaPartialTick", at = @At("RETURN"))
	private float createBiotech$freezeCapturedEntityPartialTick(float original) {
		return CapturedEntityRenderTime.overridePartialTick(original);
	}
}
