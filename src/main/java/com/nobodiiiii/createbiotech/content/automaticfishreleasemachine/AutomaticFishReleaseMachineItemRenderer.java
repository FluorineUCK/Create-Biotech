package com.nobodiiiii.createbiotech.content.automaticfishreleasemachine;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.SalmonModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class AutomaticFishReleaseMachineItemRenderer extends CustomRenderedItemModelRenderer {

	@Nullable
	private SalmonModel<Entity> fishModel;

	@Override
	protected void render(ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer,
		ItemDisplayContext transformType, PoseStack poseStack, MultiBufferSource buffer, int light, int overlay) {
		renderer.render(model.getOriginalModel(), light);
		renderBladeClamps(renderer, poseStack, light);

		boolean guiLighting = transformType == ItemDisplayContext.GUI;
		if (guiLighting)
			Lighting.setupForEntityInInventory();
		try {
			renderFishRing(poseStack, buffer, light, overlay);
		} finally {
			if (guiLighting)
				Lighting.setupFor3DItems();
		}
	}

	private static void renderBladeClamps(PartialItemModelRenderer renderer, PoseStack poseStack, int light) {
		for (int bladeIndex = 0; bladeIndex < AutomaticFishReleaseMachineRenderer.BLADE_COUNT; bladeIndex++) {
			float clampRadius =
				((bladeIndex & 1) == 0
					? AutomaticFishReleaseMachineRenderer.CARDINAL_BLADE_CLAMP_RADIUS
					: AutomaticFishReleaseMachineRenderer.INTERMEDIATE_BLADE_CLAMP_RADIUS)
					+ AutomaticFishReleaseMachineRenderer.BLADE_CLAMP_OUTWARD_OFFSET;
			poseStack.pushPose();
			poseStack.mulPose(Axis.YP.rotationDegrees(
				bladeIndex * AutomaticFishReleaseMachineRenderer.SLOT_ANGLE));
			poseStack.translate(0, 0, -clampRadius);
			renderer.renderSolid(AutomaticFishReleaseMachineRenderer.BLADE_CLAMP.get(), light);
			poseStack.popPose();
		}
	}

	private void renderFishRing(PoseStack poseStack, MultiBufferSource buffer, int light, int overlay) {
		SalmonModel<Entity> model = getFishModel();
		if (model == null)
			return;

		for (int fishIndex = 0; fishIndex < AutomaticFishReleaseMachineRenderer.BLADE_COUNT; fishIndex++) {
			float gapAngle = AutomaticFishReleaseMachineRenderer.FIRST_GAP_ANGLE
				+ fishIndex * AutomaticFishReleaseMachineRenderer.SLOT_ANGLE;
			poseStack.pushPose();
			poseStack.mulPose(Axis.YP.rotationDegrees(gapAngle));
			poseStack.translate(0, 0, -AutomaticFishReleaseMachineRenderer.FISH_RING_RADIUS);
			poseStack.mulPose(Axis.YP.rotationDegrees(
				AutomaticFishReleaseMachineRenderer.FISH_IN_PLANE_ROTATION));
			poseStack.mulPose(Axis.YP.rotationDegrees(90));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-90));
			poseStack.translate(0, 0, AutomaticFishReleaseMachineRenderer.FISH_TAIL_OFFSET);
			poseStack.scale(
				-AutomaticFishReleaseMachineRenderer.FISH_SCALE,
				-AutomaticFishReleaseMachineRenderer.FISH_SCALE,
				AutomaticFishReleaseMachineRenderer.FISH_SCALE);
			poseStack.translate(0, -1.501f, 0);
			model.renderToBuffer(poseStack,
				buffer.getBuffer(RenderType.entityCutoutNoCull(
					AutomaticFishReleaseMachineRenderer.SALMON_TEXTURE)),
				light, overlay, -1);
			poseStack.popPose();
		}
	}

	private @Nullable SalmonModel<Entity> getFishModel() {
		if (fishModel == null && Minecraft.getInstance().getEntityModels() != null)
			fishModel = new SalmonModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.SALMON));
		return fishModel;
	}
}
