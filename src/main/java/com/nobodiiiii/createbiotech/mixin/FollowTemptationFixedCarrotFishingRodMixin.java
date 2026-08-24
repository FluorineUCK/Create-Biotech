package com.nobodiiiii.createbiotech.mixin;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.fixedcarrotfishingrod.FixedCarrotFishingRodTarget;
import com.nobodiiiii.createbiotech.content.fixedcarrotfishingrod.FixedCarrotFishingRodTargeting;
import com.nobodiiiii.createbiotech.registry.CBMemoryModuleTypes;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.FollowTemptation;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.phys.Vec3;

/** Makes the existing FollowTemptation behavior follow the target supplied by TemptingSensor. */
@Mixin(FollowTemptation.class)
public abstract class FollowTemptationFixedCarrotFishingRodMixin extends Behavior<PathfinderMob> {

	protected FollowTemptationFixedCarrotFishingRodMixin(
		Map<MemoryModuleType<?>, MemoryStatus> entryCondition) {
		super(entryCondition);
	}

	@Override
	protected boolean hasRequiredMemories(PathfinderMob owner) {
		if (super.hasRequiredMemories(owner))
			return true;

		Brain<?> brain = owner.getBrain();
		if (!brain.hasMemoryValue(CBMemoryModuleTypes.FIXED_CARROT_FISHING_ROD_TARGET.get()))
			return false;

		for (Entry<MemoryModuleType<?>, MemoryStatus> requirement : entryCondition.entrySet()) {
			if (requirement.getKey() == MemoryModuleType.TEMPTING_PLAYER
				&& requirement.getValue() == MemoryStatus.VALUE_PRESENT)
				continue;
			if (!brain.checkMemory(requirement.getKey(), requirement.getValue()))
				return false;
		}
		return true;
	}

	@Shadow
	@Final
	private Function<LivingEntity, Double> closeEnoughDistance;

	@Shadow
	protected abstract float getSpeedModifier(PathfinderMob pathfinder);

	@Inject(method = "canStillUse", at = @At("HEAD"), cancellable = true)
	private void createBiotech$continueFollowingFixedRod(ServerLevel level, PathfinderMob entity,
		long gameTime, CallbackInfoReturnable<Boolean> cir) {
		Brain<?> brain = entity.getBrain();
		if (brain.hasMemoryValue(MemoryModuleType.TEMPTING_PLAYER))
			return;

		Optional<FixedCarrotFishingRodTarget> target = createBiotech$getTarget(brain);
		if (target.isEmpty())
			return;
		if (FixedCarrotFishingRodTargeting.getValidBaitPosition(entity, target.get()) == null) {
			brain.eraseMemory(CBMemoryModuleTypes.FIXED_CARROT_FISHING_ROD_TARGET.get());
			cir.setReturnValue(false);
			return;
		}

		cir.setReturnValue(!brain.hasMemoryValue(MemoryModuleType.BREED_TARGET)
			&& !brain.hasMemoryValue(MemoryModuleType.IS_PANICKING));
	}

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void createBiotech$tickFixedRod(ServerLevel level, PathfinderMob owner, long gameTime,
		CallbackInfo ci) {
		Brain<?> brain = owner.getBrain();
		if (brain.hasMemoryValue(MemoryModuleType.TEMPTING_PLAYER))
			return;

		Optional<FixedCarrotFishingRodTarget> target = createBiotech$getTarget(brain);
		if (target.isEmpty()) {
			ci.cancel();
			return;
		}

		Vec3 baitPosition = FixedCarrotFishingRodTargeting.getValidBaitPosition(owner, target.get());
		if (baitPosition == null) {
			brain.eraseMemory(CBMemoryModuleTypes.FIXED_CARROT_FISHING_ROD_TARGET.get());
			ci.cancel();
			return;
		}

		FixedCarrotFishingRodTargeting.awardIfAnimalReachedPowerBelt(owner, target.get());
		BlockPosTracker baitTracker = new BlockPosTracker(baitPosition);
		brain.setMemory(MemoryModuleType.LOOK_TARGET, baitTracker);
		double closeEnough = closeEnoughDistance.apply(owner);
		if (owner.distanceToSqr(baitPosition) < Mth.square(closeEnough))
			brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		else
			brain.setMemory(MemoryModuleType.WALK_TARGET,
				new WalkTarget(baitTracker, getSpeedModifier(owner), 2));

		ci.cancel();
	}

	@Unique
	private static Optional<FixedCarrotFishingRodTarget> createBiotech$getTarget(Brain<?> brain) {
		MemoryModuleType<FixedCarrotFishingRodTarget> memoryType =
			CBMemoryModuleTypes.FIXED_CARROT_FISHING_ROD_TARGET.get();
		return brain.hasMemoryValue(memoryType) ? brain.getMemory(memoryType) : Optional.empty();
	}
}
