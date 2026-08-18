package com.nobodiiiii.createbiotech.content.allay.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.allay.entity.courier.AllayCourierEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.AllayModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.AllayRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Vanilla Allay renderer with the courier hat and compact package as additional layers. The body,
 * wings, arms and holding pose remain entirely controlled by vanilla AllayRenderer/AllayModel.
 */
public class AllayCourierEntityRenderer extends AllayRenderer {

	private static final ModelResourceLocation CARGO_MODEL =
		ModelResourceLocation.standalone(CreateBiotech.asResource("item/allay_courier_package"));

	public AllayCourierEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
		model = new CourierAllayModel(context.bakeLayer(ModelLayers.ALLAY));
		layers.removeIf(layer -> layer instanceof ItemInHandLayer<?, ?>);
		addLayer(new AllayLogisticsHatRenderer(this));
		addLayer(new CourierAccessoriesLayer(this));
	}

	private static class CourierAllayModel extends AllayModel {

		private static final float HOLDING_ARM_X_ROT = -(float) Math.PI / 3.0f;
		private static final float HOLDING_ARM_Y_ROT = 0.27925268f;

		private final ModelPart modelRoot;
		private final ModelPart body;
		private final ModelPart rightArm;
		private final ModelPart leftArm;
		private boolean detachLoadedArms;

		private CourierAllayModel(ModelPart root) {
			super(root);
			modelRoot = root();
			body = modelRoot.getChild("body");
			rightArm = body.getChild("right_arm");
			leftArm = body.getChild("left_arm");
		}

		@Override
		public void setupAnim(Allay allay, float limbSwing, float limbSwingAmount, float ageInTicks,
			float netHeadYaw, float headPitch) {
			super.setupAnim(allay, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
			detachLoadedArms = allay instanceof AllayCourierEntity courier && !courier.getPackage().isEmpty();
			if (!detachLoadedArms) {
				return;
			}

			rightArm.setRotation(HOLDING_ARM_X_ROT, HOLDING_ARM_Y_ROT, 0.0f);
			leftArm.setRotation(HOLDING_ARM_X_ROT, -HOLDING_ARM_Y_ROT, 0.0f);
		}

		@Override
		public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay,
			int color) {
			if (!detachLoadedArms) {
				super.renderToBuffer(poseStack, buffer, packedLight, packedOverlay, color);
				return;
			}

			boolean rightArmVisible = rightArm.visible;
			boolean leftArmVisible = leftArm.visible;
			rightArm.visible = false;
			leftArm.visible = false;
			super.renderToBuffer(poseStack, buffer, packedLight, packedOverlay, color);
			rightArm.visible = rightArmVisible;
			leftArm.visible = leftArmVisible;

			if (!modelRoot.visible || !body.visible) {
				return;
			}
			if (rightArmVisible) {
				renderRotationIndependentArm(rightArm, poseStack, buffer, packedLight, packedOverlay, color);
			}
			if (leftArmVisible) {
				renderRotationIndependentArm(leftArm, poseStack, buffer, packedLight, packedOverlay, color);
			}
		}

		private void renderRotationIndependentArm(ModelPart arm, PoseStack poseStack, VertexConsumer buffer,
			int packedLight, int packedOverlay, int color) {
			Quaternionf bodyRotation = bodyRotation(body);
			Vector3f rotatedAnchor = new Vector3f(arm.x, arm.y, arm.z).rotate(bodyRotation);

			poseStack.pushPose();
			modelRoot.translateAndRotate(poseStack);
			poseStack.translate(body.x / 16.0f, body.y / 16.0f, body.z / 16.0f);
			poseStack.translate(
				(rotatedAnchor.x - arm.x) / 16.0f,
				(rotatedAnchor.y - arm.y) / 16.0f,
				(rotatedAnchor.z - arm.z) / 16.0f);
			arm.render(poseStack, buffer, packedLight, packedOverlay, color);
			poseStack.popPose();
		}
	}

	private static class CourierAccessoriesLayer extends RenderLayer<Allay, AllayModel> {

		private static final float CARGO_ANCHOR_Y = 0.5f;

		private CourierAccessoriesLayer(RenderLayerParent<Allay, AllayModel> renderer) {
			super(renderer);
		}

		@Override
		public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, Allay allay,
			float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
			float netHeadYaw, float headPitch) {
			if (!(allay instanceof AllayCourierEntity courier)) {
				return;
			}
			renderCargo(courier, poseStack, buffer, packedLight);
		}

		private void renderCargo(AllayCourierEntity courier, PoseStack poseStack,
			MultiBufferSource buffer, int packedLight) {
			if (courier.getPackage().isEmpty()) {
				return;
			}
			BakedModel cargoModel = Minecraft.getInstance().getModelManager().getModel(CARGO_MODEL);
			if (cargoModel == Minecraft.getInstance().getModelManager().getMissingModel()) {
				return;
			}

			poseStack.pushPose();
			ModelPart root = getParentModel().root();
			ModelPart body = root.getChild("body");
			if (body.isEmpty()) {
				poseStack.popPose();
				return;
			}

			root.translateAndRotate(poseStack);
			body.translateAndRotate(poseStack);
			cancelBodyRotationAround(poseStack, body, 0.0f, CARGO_ANCHOR_Y, 0.0f);
			poseStack.scale(1.0f, -1.0f, -1.0f);
			poseStack.translate(0.5f, 0.5f, 0.5f);
			PartialItemModelRenderer.of(courier.getPackage(), ItemDisplayContext.NONE, poseStack, buffer,
				OverlayTexture.NO_OVERLAY).render(cargoModel, packedLight);
			poseStack.popPose();
		}
	}

	private static Quaternionf bodyRotation(ModelPart body) {
		return new Quaternionf().rotationZYX(body.zRot, body.yRot, body.xRot);
	}

	private static void cancelBodyRotationAround(PoseStack poseStack, ModelPart body,
		float anchorX, float anchorY, float anchorZ) {
		poseStack.translate(anchorX / 16.0f, anchorY / 16.0f, anchorZ / 16.0f);
		poseStack.mulPose(bodyRotation(body).conjugate());
		poseStack.translate(-anchorX / 16.0f, -anchorY / 16.0f, -anchorZ / 16.0f);
	}
}
