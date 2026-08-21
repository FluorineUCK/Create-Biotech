package com.nobodiiiii.createbiotech.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.content.processing.basin.BasinContainedSlimeRenderer;
import com.nobodiiiii.createbiotech.content.processing.basin.BasinEntityProcessing;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinRenderer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

@Mixin(BasinRenderer.class)
public abstract class BasinRendererMixin {

	@WrapOperation(
		method = "renderSafe(Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/neoforged/neoforge/items/IItemHandlerModifiable;getStackInSlot(I)Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack createBiotech$hideCapturedSmallSlimeItemsInBasin(IItemHandlerModifiable inventory, int slot,
		Operation<ItemStack> original) {
		ItemStack stack = original.call(inventory, slot);
		return BasinEntityProcessing.isCapturedSmallSlimeItem(stack) ? ItemStack.EMPTY : stack;
	}

	@Inject(
		method = "renderSafe(Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		// BasinRenderer returns early for the normal DOWN-facing basin. Inject at
		// every return so contained slimes render for both basin output modes.
		at = @At("RETURN"))
	private void createBiotech$renderContainedSmallSlimes(BasinBlockEntity basin, float partialTicks,
		PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay, CallbackInfo ci) {
		BasinContainedSlimeRenderer.render(basin, partialTicks, poseStack, buffer, packedLight);
	}
}
