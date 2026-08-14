package com.nobodiiiii.createbiotech.content.dingdongchicken;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.redstone.deskBell.DeskBellBlock;

import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

public class DingDongChickenRenderer
	extends MobRenderer<DingDongChickenEntity, DingDongChickenModel> {

	private static final ResourceLocation CHICKEN_TEXTURE =
		ResourceLocation.withDefaultNamespace("textures/entity/chicken.png");

	public DingDongChickenRenderer(EntityRendererProvider.Context context) {
		super(context, new DingDongChickenModel(context.bakeLayer(ModelLayers.CHICKEN)), 0.3F);
		addLayer(new BellHeadLayer(this));
	}

	@Override
	public ResourceLocation getTextureLocation(DingDongChickenEntity entity) {
		return CHICKEN_TEXTURE;
	}

	@Override
	protected float getBob(DingDongChickenEntity entity, float partialTick) {
		float flap = Mth.lerp(partialTick, entity.oFlap, entity.flap);
		float flapSpeed = Mth.lerp(partialTick, entity.oFlapSpeed, entity.flapSpeed);
		return (Mth.sin(flap) + 1.0F) * flapSpeed;
	}

	private static final class BellHeadLayer
		extends RenderLayer<DingDongChickenEntity, DingDongChickenModel> {

		private BellHeadLayer(DingDongChickenRenderer renderer) {
			super(renderer);
		}

		@Override
		public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
			DingDongChickenEntity entity, float limbSwing, float limbSwingAmount, float partialTick,
			float ageInTicks, float netHeadYaw, float headPitch) {
			if (entity.isInvisible())
				return;

			poseStack.pushPose();
			if (entity.isBaby())
				poseStack.translate(0, 5.0F / 16.0F, 2.0F / 16.0F);

			// Vanilla's chicken head pivots at (0, 15, -4) model pixels.
			poseStack.translate(0, 15.0F / 16.0F, -4.0F / 16.0F);
			poseStack.mulPose(Axis.YP.rotationDegrees(netHeadYaw));
			poseStack.mulPose(Axis.XP.rotationDegrees(headPitch));

			float animation = entity.getBellAnimation(partialTick);
			if (animation > 0) {
				float phase = (1 - animation) * Mth.PI * 8;
				poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.sin(phase) * animation * 5));
			}

			// Half-size Create desk bell, standing upright on the chicken's neck.
			poseStack.mulPose(Axis.XP.rotationDegrees(180));
			poseStack.scale(0.5F, 0.5F, 0.5F);
			poseStack.translate(-0.5F, 0, -0.5F);
			BlockState bellState = AllBlocks.DESK_BELL.getDefaultState()
				.setValue(DeskBellBlock.POWERED, entity.isPowered());
			CachedBuffers.block(bellState)
				.light(packedLight)
				.overlay(LivingEntityRenderer.getOverlayCoords(entity, 0))
				.renderInto(poseStack, buffer.getBuffer(RenderType.solid()));
			poseStack.popPose();
		}
	}
}
