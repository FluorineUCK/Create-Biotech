package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nobodiiiii.createbiotech.content.buttercat.ButterRotationAccess;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

@Mixin(LivingEntity.class)
public abstract class LivingEntityButterRotationMixin implements ButterRotationAccess {

	@Unique
	private static final EntityDataAccessor<Integer> CREATE_BIOTECH$BUTTER_ROTATION_AMPLIFIER =
		SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.INT);
	@Unique
	private static final EntityDataAccessor<Float> CREATE_BIOTECH$BUTTER_ROTATION_PHASE =
		SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.FLOAT);
	@Unique
	private static final EntityDataAccessor<Long> CREATE_BIOTECH$BUTTER_ROTATION_PHASE_START_TICK =
		SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.LONG);

	@Inject(method = "defineSynchedData", at = @At("TAIL"))
	private void createBiotech$defineButterRotationData(SynchedEntityData.Builder builder, CallbackInfo ci) {
		builder.define(CREATE_BIOTECH$BUTTER_ROTATION_AMPLIFIER, -1);
		builder.define(CREATE_BIOTECH$BUTTER_ROTATION_PHASE, 0.0F);
		builder.define(CREATE_BIOTECH$BUTTER_ROTATION_PHASE_START_TICK, -1L);
	}

	@Override
	public int createBiotech$getButterRotationAmplifier() {
		return ((LivingEntity) (Object) this).getEntityData().get(CREATE_BIOTECH$BUTTER_ROTATION_AMPLIFIER);
	}

	@Override
	public void createBiotech$setButterRotationAmplifier(int amplifier) {
		LivingEntity entity = (LivingEntity) (Object) this;
		if (entity.getEntityData().get(CREATE_BIOTECH$BUTTER_ROTATION_AMPLIFIER) != amplifier)
			entity.getEntityData().set(CREATE_BIOTECH$BUTTER_ROTATION_AMPLIFIER, amplifier);
	}

	@Override
	public float createBiotech$getButterRotationPhase() {
		return ((LivingEntity) (Object) this).getEntityData().get(CREATE_BIOTECH$BUTTER_ROTATION_PHASE);
	}

	@Override
	public void createBiotech$setButterRotationPhase(float phase) {
		LivingEntity entity = (LivingEntity) (Object) this;
		if (Float.compare(entity.getEntityData().get(CREATE_BIOTECH$BUTTER_ROTATION_PHASE), phase) != 0)
			entity.getEntityData().set(CREATE_BIOTECH$BUTTER_ROTATION_PHASE, phase);
	}

	@Override
	public long createBiotech$getButterRotationPhaseStartTick() {
		return ((LivingEntity) (Object) this).getEntityData().get(CREATE_BIOTECH$BUTTER_ROTATION_PHASE_START_TICK);
	}

	@Override
	public void createBiotech$setButterRotationPhaseStartTick(long gameTime) {
		LivingEntity entity = (LivingEntity) (Object) this;
		if (entity.getEntityData().get(CREATE_BIOTECH$BUTTER_ROTATION_PHASE_START_TICK) != gameTime)
			entity.getEntityData().set(CREATE_BIOTECH$BUTTER_ROTATION_PHASE_START_TICK, gameTime);
	}
}
