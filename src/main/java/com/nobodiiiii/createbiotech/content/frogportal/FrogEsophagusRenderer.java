package com.nobodiiiii.createbiotech.content.frogportal;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer;
import net.minecraft.core.Direction;

/**
 * Uses the vanilla End Portal shader on a thin, upright portal plane.
 */
public class FrogEsophagusRenderer extends TheEndPortalRenderer<FrogEsophagusBlockEntity> {

	private static final float PORTAL_THICKNESS = 0.25f;
	private static final float PORTAL_OFFSET = (1.0f - PORTAL_THICKNESS) / 2.0f;

	public FrogEsophagusRenderer(BlockEntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public void render(FrogEsophagusBlockEntity blockEntity, float partialTick, PoseStack poseStack,
		MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
		poseStack.pushPose();
		if (blockEntity.getBlockState().getValue(FrogPortalBehaviour.AXIS) == Direction.Axis.X) {
			poseStack.translate(0.0f, 0.0f, PORTAL_OFFSET);
			poseStack.scale(1.0f, 1.0f, PORTAL_THICKNESS);
		} else {
			poseStack.translate(PORTAL_OFFSET, 0.0f, 0.0f);
			poseStack.scale(PORTAL_THICKNESS, 1.0f, 1.0f);
		}
		super.render(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
		poseStack.popPose();
	}
}
