package com.nobodiiiii.createbiotech.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMixin {

	@Unique
	private static final ResourceLocation CREATE_BIOTECH$SLIME_LAYER_1 =
		CreateBiotech.asResource("textures/models/armor/slime_layer_1.png");

	@Unique
	private static final ResourceLocation CREATE_BIOTECH$SLIME_LAYER_2 =
		CreateBiotech.asResource("textures/models/armor/slime_layer_2.png");

	@WrapOperation(
		method = "renderModel(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/model/Model;ILnet/minecraft/resources/ResourceLocation;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/RenderType;armorCutoutNoCull(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"))
	private RenderType createBiotech$renderSlimeArmorTranslucently(ResourceLocation texture,
		Operation<RenderType> original) {
		if (texture.equals(CREATE_BIOTECH$SLIME_LAYER_1)
			|| texture.equals(CREATE_BIOTECH$SLIME_LAYER_2))
			return RenderType.entityTranslucent(texture);
		return original.call(texture);
	}
}
