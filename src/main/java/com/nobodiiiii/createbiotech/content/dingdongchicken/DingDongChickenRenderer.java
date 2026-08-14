package com.nobodiiiii.createbiotech.content.dingdongchicken;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPartialModels;
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

			// Full-size Create desk bell, standing upright on the chicken's neck.
			poseStack.mulPose(Axis.XP.rotationDegrees(180));
			poseStack.translate(-0.5F, 0, -0.5F);
			float animation = entity.getBellAnimation(partialTick);
			boolean animated = entity.isPowered() || animation > 0;
			BlockState bellState = AllBlocks.DESK_BELL.getDefaultState()
				.setValue(DeskBellBlock.POWERED, animated);
			int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0);

			// Create's powered block model intentionally contains only the stationary base. The
			// plunger and bell are rendered as independent partials below.
			CachedBuffers.block(bellState)
				.light(packedLight)
				.overlay(overlay)
				.renderInto(poseStack, buffer.getBuffer(RenderType.solid()));

			if (animated)
				renderAnimatedBell(poseStack, buffer, packedLight, overlay, bellState, animation,
					entity.getId() * 0.7548777F);
			poseStack.popPose();
		}

		private static void renderAnimatedBell(PoseStack poseStack, MultiBufferSource buffer,
			int packedLight, int overlay, BlockState bellState, float animation,
			float animationOffset) {
			float plungerOffset = (float) (1 - 4
				* Math.pow(Math.max(animation - 0.5F, 0) - 0.5F, 2));
			float swingStrength = (float) Math.pow(animation, 1.25F);

			CachedBuffers.partial(AllPartialModels.DESK_BELL_PLUNGER, bellState)
				.translate(0, plungerOffset * -0.75F / 16F, 0)
				.light(packedLight)
				.overlay(overlay)
				.renderInto(poseStack, buffer.getBuffer(RenderType.solid()));

			CachedBuffers.partial(AllPartialModels.DESK_BELL_BELL, bellState)
				.center()
				.translate(0, -1F / 16F, 0)
				.rotateXDegrees(swingStrength * 8
					* Mth.sin(animation * Mth.PI * 4 + animationOffset))
				.rotateZDegrees(swingStrength * 8
					* Mth.cos(animation * Mth.PI * 4 + animationOffset))
				.translate(0, 1F / 16F, 0)
				.scale(0.995F)
				.uncenter()
				.light(packedLight)
				.overlay(overlay)
				.renderInto(poseStack, buffer.getBuffer(RenderType.solid()));
		}
	}
}
