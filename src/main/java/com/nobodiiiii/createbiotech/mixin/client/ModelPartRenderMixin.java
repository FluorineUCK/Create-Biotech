package com.nobodiiiii.createbiotech.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nobodiiiii.createbiotech.client.render.SlimeMimicRenderLayer;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalModelRenderContext;

import net.minecraft.client.model.geom.ModelPart;

@Mixin(ModelPart.class)
public abstract class ModelPartRenderMixin {

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
		at = @At("HEAD"), cancellable = true)
	private void createBiotech$redirectSlimeMimicPartRender(PoseStack poseStack, VertexConsumer consumer,
		int packedLight, int overlay, int color, CallbackInfo ci) {
		if (SlimeMimicRenderLayer.interceptModelPart((ModelPart) (Object) this, poseStack, packedLight, overlay))
			ci.cancel();
	}

	@WrapOperation(
		method = "compile(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/geom/ModelPart$Cube;compile(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
	private void createBiotech$filterSurgicalOriginalLayerCube(ModelPart.Cube cube, PoseStack.Pose pose,
		VertexConsumer consumer, int packedLight, int overlay, int color, Operation<Void> original) {
		if (!SurgicalModelRenderContext.isActive()) {
			original.call(cube, pose, consumer, packedLight, overlay, color);
			return;
		}
		SurgicalModelRenderContext.renderOriginalLayerCube(cube, pose,
			() -> original.call(cube, pose, consumer, packedLight, overlay, color));
	}
}
