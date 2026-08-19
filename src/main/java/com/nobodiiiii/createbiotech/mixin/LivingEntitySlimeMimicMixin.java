package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nobodiiiii.createbiotech.content.buttercat.ButterRotationAccess;
import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicAccess;
import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicHandler;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

@Mixin(LivingEntity.class)
public abstract class LivingEntitySlimeMimicMixin implements SlimeMimicAccess, ButterRotationAccess {

	@Unique
	private static final EntityDataAccessor<Boolean> CREATE_BIOTECH$SLIME_MIMIC = SynchedEntityData.defineId(
		LivingEntity.class, EntityDataSerializers.BOOLEAN);
	@Unique
	private static final EntityDataAccessor<Integer> CREATE_BIOTECH$BUTTER_ROTATION_AMPLIFIER =
		SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.INT);

	@Inject(method = "defineSynchedData", at = @At("TAIL"))
	private void createBiotech$defineData(SynchedEntityData.Builder builder, CallbackInfo ci) {
		builder.define(CREATE_BIOTECH$SLIME_MIMIC, false);
		builder.define(CREATE_BIOTECH$BUTTER_ROTATION_AMPLIFIER, -1);
	}

	@Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
	private void createBiotech$saveSlimeMimicData(CompoundTag tag, CallbackInfo ci) {
		if (createBiotech$isSlimeMimic())
			tag.putBoolean(SlimeMimicHandler.SLIME_MIMIC_TAG, true);
	}

	@Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
	private void createBiotech$readSlimeMimicData(CompoundTag tag, CallbackInfo ci) {
		createBiotech$setSlimeMimic(tag.contains(SlimeMimicHandler.SLIME_MIMIC_TAG, Tag.TAG_BYTE)
			&& tag.getBoolean(SlimeMimicHandler.SLIME_MIMIC_TAG));
	}

	@Override
	public boolean createBiotech$isSlimeMimic() {
		return ((LivingEntity) (Object) this).getEntityData().get(CREATE_BIOTECH$SLIME_MIMIC);
	}

	@Override
	public void createBiotech$setSlimeMimic(boolean slimeMimic) {
		((LivingEntity) (Object) this).getEntityData().set(CREATE_BIOTECH$SLIME_MIMIC, slimeMimic);
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
}
