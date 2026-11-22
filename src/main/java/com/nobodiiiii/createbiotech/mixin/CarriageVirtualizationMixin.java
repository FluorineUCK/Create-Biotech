package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCarriageVirtualization;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

@Mixin(targets = "com.simibubi.create.content.trains.entity.Carriage$DimensionalCarriageEntity")
public abstract class CarriageVirtualizationMixin {
	@WrapOperation(method = "removeAndSaveEntity", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;discard()V"))
	private void createBiotech$discardSerializedCarriage(CarriageContraptionEntity entity,
		Operation<Void> original) {
		LibraryCarriageVirtualization.discardSerializedCarriage(entity,
			() -> original.call(entity));
	}
}
