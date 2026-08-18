package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.allay.block.allayport.AllayPortWakeupHandler;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PackagerBlockEntity.class)
public abstract class PackagerBlockEntityMixin {
	@Inject(method = "wakeTheFrogs", at = @At("TAIL"), remap = false)
	private void createBiotech$wakeAllayPortAbove(CallbackInfo ci) {
		AllayPortWakeupHandler.tryWakePortAbove((PackagerBlockEntity) (Object) this);
	}
}
