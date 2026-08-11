package com.nobodiiiii.createbiotech.content.magmacubeburner;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.foundation.render.EntityRenderHelper;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.level.Level;

public class MagmaCubeBurnerRenderer extends SmartBlockEntityRenderer<MagmaCubeBurnerBlockEntity> {

	private static final float ENTITY_SCALE = .36f;

	@Nullable
	private MagmaCube renderedMagmaCube;
	@Nullable
	private Level renderedLevel;

	public MagmaCubeBurnerRenderer(BlockEntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	protected void renderSafe(MagmaCubeBurnerBlockEntity blockEntity, float partialTicks, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight, int packedOverlay) {
		Level level = blockEntity.getLevel();
		if (level == null)
			return;

		MagmaCube magmaCube = getOrCreateMagmaCube(level);
		if (magmaCube == null)
			return;
		magmaCube.setSize(blockEntity.getRenderedMagmaCubeSize(), false);

		float animationTime = level.getGameTime() + partialTicks;
		poseStack.pushPose();
		poseStack.translate(.5, .125 + Math.sin(animationTime * .08) * .015, .5);
		poseStack.scale(ENTITY_SCALE, ENTITY_SCALE, ENTITY_SCALE);
		EntityRenderHelper.render(EntityRenderHelper.settings(magmaCube)
			.packedLight(LightTexture.FULL_BRIGHT)
			.partialTicks(partialTicks)
			.ticks((int) level.getGameTime())
			.yaw(animationTime * 2)
			.bodyYaw(animationTime * 2)
			.headYaw(animationTime * 2), poseStack, buffer);
		poseStack.popPose();
	}

	@Nullable
	private MagmaCube getOrCreateMagmaCube(Level level) {
		if (renderedMagmaCube != null && renderedLevel == level)
			return renderedMagmaCube;

		MagmaCube magmaCube = EntityType.MAGMA_CUBE.create(level);
		if (magmaCube == null)
			return null;
		magmaCube.setNoAi(true);
		magmaCube.setSilent(true);
		magmaCube.setOnGround(true);
		renderedLevel = level;
		renderedMagmaCube = magmaCube;
		return magmaCube;
	}
}
