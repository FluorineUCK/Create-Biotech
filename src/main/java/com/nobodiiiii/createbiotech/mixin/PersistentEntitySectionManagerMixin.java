package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryContraptionMovementHandler;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/** Runs after EntityStorage synchronously serialized the chunk and before vanilla retires it. */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin {
	@Inject(method = "unloadEntity", at = @At("HEAD"))
	private void createBiotech$detachSerializedContraptionOwner(EntityAccess entity,
		CallbackInfo ci) {
		LibraryContraptionMovementHandler.onSerializedChunkUnload(entity);
	}
}
