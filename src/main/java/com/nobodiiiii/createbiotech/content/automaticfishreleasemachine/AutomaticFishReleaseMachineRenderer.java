package com.nobodiiiii.createbiotech.content.automaticfishreleasemachine;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlock;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.model.SalmonModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

public class AutomaticFishReleaseMachineRenderer
	extends WaterWheelRenderer<AutomaticFishReleaseMachineBlockEntity> {

	public static final ResourceLocation BLADE_CLAMP_MODEL_LOCATION =
		CreateBiotech.asResource("block/automatic_fish_release_machine/blade_clamp");
	private static final PartialModel BLADE_CLAMP = PartialModel.of(BLADE_CLAMP_MODEL_LOCATION);
	private static final ResourceLocation SALMON_TEXTURE =
		ResourceLocation.withDefaultNamespace("textures/entity/fish/salmon.png");
	private static final int BLADE_COUNT = 16;
	private static final float FISH_RING_RADIUS = 2.47f;
	private static final float FISH_SCALE = 0.8f;
	private static final float FISH_IN_PLANE_ROTATION = -12.25f;
	private static final float CARDINAL_BLADE_CLAMP_RADIUS = 2.125f;
	private static final float INTERMEDIATE_BLADE_CLAMP_RADIUS = 2.1875f;
	private static final float SLOT_ANGLE = 360.0f / BLADE_COUNT;
	private static final float FIRST_GAP_ANGLE = SLOT_ANGLE / 2.0f;

	private final SalmonModel<Entity> fishModel;

	public AutomaticFishReleaseMachineRenderer(BlockEntityRendererProvider.Context context) {
		super(context, true);
		fishModel = new SalmonModel<>(context.bakeLayer(ModelLayers.SALMON));
	}

	@Override
	protected void renderSafe(AutomaticFishReleaseMachineBlockEntity blockEntity, float partialTicks,
		PoseStack poseStack, MultiBufferSource buffer, int light, int overlay) {
		super.renderSafe(blockEntity, partialTicks, poseStack, buffer, light, overlay);

		Direction.Axis rotationAxis = blockEntity.getBlockState()
			.getValue(LargeWaterWheelBlock.AXIS);
		float wheelAngle =
			KineticBlockEntityRenderer.getAngleForBe(blockEntity, blockEntity.getBlockPos(), rotationAxis);

		poseStack.pushPose();
		poseStack.translate(0.5f, 0.5f, 0.5f);
		alignVerticalModelToAxis(poseStack, rotationAxis);
		poseStack.mulPose(Axis.YP.rotation(wheelAngle));

		for (int fishIndex = 0; fishIndex < BLADE_COUNT; fishIndex++)
			renderFishInGap(poseStack, buffer, light, overlay, FIRST_GAP_ANGLE + fishIndex * SLOT_ANGLE);
		for (int bladeIndex = 0; bladeIndex < BLADE_COUNT; bladeIndex++)
			renderBladeClamp(blockEntity, poseStack, buffer, light, bladeIndex);

		poseStack.popPose();
	}

	private void renderFishInGap(PoseStack poseStack, MultiBufferSource buffer, int light, int overlay,
		float gapAngle) {
		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees(gapAngle));
		poseStack.translate(0, 0, -FISH_RING_RADIUS);

		// Salmon models are long on Z, tall on Y and thin on X. This cyclic rotation
		// keeps them parallel to the tangent blades, with their bellies facing the
		// wheel centre and their thin dimension aligned with its axle.
		poseStack.mulPose(Axis.YP.rotationDegrees(FISH_IN_PLANE_ROTATION));
		poseStack.mulPose(Axis.YP.rotationDegrees(90));
		poseStack.mulPose(Axis.ZP.rotationDegrees(-90));
		poseStack.scale(-FISH_SCALE, -FISH_SCALE, FISH_SCALE);
		poseStack.translate(0, -1.501f, 0);

		fishModel.renderToBuffer(poseStack, buffer.getBuffer(RenderType.entityCutoutNoCull(SALMON_TEXTURE)),
			light, overlay, -1);
		poseStack.popPose();
	}

	private static void renderBladeClamp(AutomaticFishReleaseMachineBlockEntity blockEntity, PoseStack poseStack,
		MultiBufferSource buffer, int light, int bladeIndex) {
		float clampRadius =
			(bladeIndex & 1) == 0 ? CARDINAL_BLADE_CLAMP_RADIUS : INTERMEDIATE_BLADE_CLAMP_RADIUS;
		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees(bladeIndex * SLOT_ANGLE));
		poseStack.translate(-0.5f, -0.5f, -clampRadius - 0.5f);
		CachedBuffers.partial(BLADE_CLAMP, blockEntity.getBlockState())
			.light(light)
			.renderInto(poseStack, buffer.getBuffer(RenderType.solid()));
		poseStack.popPose();
	}

	private static void alignVerticalModelToAxis(PoseStack poseStack, Direction.Axis axis) {
		switch (axis) {
		case X -> poseStack.mulPose(Axis.ZP.rotationDegrees(-90));
		case Z -> poseStack.mulPose(Axis.XP.rotationDegrees(90));
		default -> {
		}
		}
	}
}
