package com.nobodiiiii.createbiotech.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicHandler;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalCapturedRenderPlan;
import com.nobodiiiii.createbiotech.entity.SlimeBionicEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherSlimeMimicMixin {
	@WrapOperation(
		method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
	private <E extends Entity> void createBiotech$renderCompleteSlimeMimic(EntityRenderer<? super E> renderer,
		E entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
		Operation<Void> original) {
		if (!(entity instanceof LivingEntity living) || entity instanceof SlimeBionicEntity
			|| !SlimeMimicHandler.isSlimeMimic(living)
			|| living.isInvisible()) {
			original.call(renderer, entity, yaw, partialTick, poseStack, buffer, packedLight);
			return;
		}

		SurgicalCapturedRenderPlan.renderSlimeMimic(renderer, living, yaw, partialTick,
			poseStack, buffer, packedLight);
	}
}
