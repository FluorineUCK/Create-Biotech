package com.nobodiiiii.createbiotech.mixin;

import java.util.Set;
import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.nobodiiiii.createbiotech.content.fixedcarrotfishingrod.FixedCarrotFishingRodTarget;
import com.nobodiiiii.createbiotech.content.fixedcarrotfishingrod.FixedCarrotFishingRodTargeting;
import com.nobodiiiii.createbiotech.registry.CBMemoryModuleTypes;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.TemptingSensor;
import net.minecraft.world.item.ItemStack;

/** Extends every vanilla-style tempting sensor with a stationary fixed-rod target. */
@Mixin(TemptingSensor.class)
public abstract class TemptingSensorFixedCarrotFishingRodMixin {

	@Shadow
	@Final
	private Predicate<ItemStack> temptations;

	@ModifyReturnValue(method = "requires", at = @At("RETURN"))
	private Set<MemoryModuleType<?>> createBiotech$registerFixedRodMemory(Set<MemoryModuleType<?>> original) {
		return ImmutableSet.<MemoryModuleType<?>>builder()
			.addAll(original)
			.add(CBMemoryModuleTypes.FIXED_CARROT_FISHING_ROD_TARGET.get())
			.build();
	}

	@Inject(method = "doTick", at = @At("TAIL"))
	private void createBiotech$findFixedRod(ServerLevel level, PathfinderMob entity, CallbackInfo ci) {
		Brain<?> brain = entity.getBrain();
		MemoryModuleType<FixedCarrotFishingRodTarget> memoryType =
			CBMemoryModuleTypes.FIXED_CARROT_FISHING_ROD_TARGET.get();
		if (brain.hasMemoryValue(MemoryModuleType.TEMPTING_PLAYER)) {
			brain.eraseMemory(memoryType);
			return;
		}

		FixedCarrotFishingRodTarget target = FixedCarrotFishingRodTargeting.findNearest(entity, temptations);
		if (target == null)
			brain.eraseMemory(memoryType);
		else
			brain.setMemory(memoryType, target);
	}
}
