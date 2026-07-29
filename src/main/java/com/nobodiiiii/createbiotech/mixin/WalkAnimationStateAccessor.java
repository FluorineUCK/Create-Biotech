package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.WalkAnimationState;

@Mixin(WalkAnimationState.class)
public interface WalkAnimationStateAccessor {

	@Accessor("speedOld")
	float createBiotech$getSpeedOld();

	@Accessor("speed")
	float createBiotech$getSpeed();

	@Accessor("speed")
	void createBiotech$setSpeed(float speed);

	@Accessor("position")
	float createBiotech$getPosition();

	@Accessor("position")
	void createBiotech$setPosition(float position);
}
