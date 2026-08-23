package com.nobodiiiii.createbiotech.mixin.client;

import com.nobodiiiii.createbiotech.content.smartglue.SmartSuperGlueItem;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalTableClientHandler;
import com.simibubi.create.content.contraptions.glue.SuperGlueSelectionHandler;

import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SuperGlueSelectionHandler.class)
public abstract class MixinSuperGlueSelectionHandler {

	@Inject(method = "isGlue(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true,
		remap = true)
	private void createBiotech$excludeSmartGlue(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (stack.getItem() instanceof SmartSuperGlueItem)
			cir.setReturnValue(false);
		else if (SurgicalTableClientHandler.shouldOverrideCreateGlue(stack))
			cir.setReturnValue(false);
	}
}
