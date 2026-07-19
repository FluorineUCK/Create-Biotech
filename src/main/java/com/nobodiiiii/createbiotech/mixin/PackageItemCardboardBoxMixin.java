package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.simibubi.create.content.logistics.box.PackageItem;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

@Mixin(PackageItem.class)
public abstract class PackageItemCardboardBoxMixin {

	@Inject(method = "getContents", at = @At("RETURN"), cancellable = true)
	private static void createBiotech$addVirtualFallbackContents(ItemStack box,
		CallbackInfoReturnable<ItemStackHandler> cir) {
		cir.setReturnValue(CapturedEntityBoxHelper.applyVirtualSelfFallbackContents(box, cir.getReturnValue()));
	}
}
