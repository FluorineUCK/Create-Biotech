package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.experience.ExperienceFluidHelper;
import com.nobodiiiii.createbiotech.content.experience.LegacyExperienceCompat;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FluidTankBlockEntity.class, priority = 1001)
public abstract class FluidTankBlockEntityMixin {
	@Shadow(remap = false)
	protected FluidTank tankInventory;

	@Shadow(remap = false)
	public abstract boolean isController();

	@Unique
	private int createBiotech$legacyStoredExperience;

	@Inject(method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V", at = @At("HEAD"))
	private void createBiotech$migrateLegacyExperienceTankNbt(CompoundTag compound,
		HolderLookup.Provider registries, boolean clientPacket,
		CallbackInfo ci) {
		createBiotech$legacyStoredExperience = compound.getInt("StoredExperience");
		LegacyExperienceCompat.migrateTankNbt(compound, registries);
	}

	@Inject(method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V", at = @At("TAIL"))
	private void createBiotech$restoreLegacyExperienceContents(CompoundTag compound,
		HolderLookup.Provider registries, boolean clientPacket,
		CallbackInfo ci) {
		if (clientPacket || createBiotech$legacyStoredExperience <= 0 || !isController()) {
			createBiotech$legacyStoredExperience = 0;
			return;
		}

		if (tankInventory.getFluid()
			.isEmpty()) {
			tankInventory.fill(ExperienceFluidHelper.experienceStack(createBiotech$legacyStoredExperience),
				IFluidHandler.FluidAction.EXECUTE);
		}

		createBiotech$legacyStoredExperience = 0;
	}
}
