package com.nobodiiiii.createbiotech.mixin.client;

import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nobodiiiii.createbiotech.client.render.SlimeMimicRenderLayer;

/** Optional hook: absence or API drift in Lionfish must never prevent the client from loading. */
@Pseudo
@Mixin(targets = "com.github.L_Ender.lionfishapi.client.model.tools.AdvancedModelBox", remap = false)
public abstract class LionfishAdvancedModelBoxMixin {

	@Dynamic("Lionfish API optional model-part render method")
	@Inject(
		method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void createBiotech$redirectSlimeMimicPartRender(PoseStack poseStack, VertexConsumer consumer,
		int packedLight, int overlay, int color, CallbackInfo ci) {
		try {
			if (SlimeMimicRenderLayer.interceptLionfishModelPart(
				(Object) this, poseStack, packedLight, overlay))
				ci.cancel();
		} catch (RuntimeException | LinkageError ignored) {
			// Lionfish is optional. API drift falls through to its original renderer.
		}
	}
}
