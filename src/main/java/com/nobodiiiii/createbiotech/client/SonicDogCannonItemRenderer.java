package com.nobodiiiii.createbiotech.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.sonicdogcannon.SonicDogCannonItem;
import com.nobodiiiii.createbiotech.content.sonicdogcannon.SonicDogCannonUpgrade;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class SonicDogCannonItemRenderer extends CustomRenderedItemModelRenderer {

	public static final ResourceLocation GEAR_MODEL_LOCATION =
		CreateBiotech.asResource("item/sonic_dog_cannon/gear");
	public static final ResourceLocation SCOPE_MODEL_LOCATION =
		CreateBiotech.asResource("item/sonic_dog_cannon/scope");
	public static final ResourceLocation FOLDED_SCOPE_MODEL_LOCATION =
		CreateBiotech.asResource("item/sonic_dog_cannon/scope_folded");
	public static final ResourceLocation COLLAR_MODEL_LOCATION =
		CreateBiotech.asResource("item/sonic_dog_cannon/collar");
	private static final PartialModel GEAR = PartialModel.of(GEAR_MODEL_LOCATION);
	private static final PartialModel SCOPE = PartialModel.of(SCOPE_MODEL_LOCATION);
	private static final PartialModel FOLDED_SCOPE = PartialModel.of(FOLDED_SCOPE_MODEL_LOCATION);
	private static final PartialModel COLLAR = PartialModel.of(COLLAR_MODEL_LOCATION);

	@Override
	protected void render(ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer,
		ItemDisplayContext transformType, PoseStack poseStack, MultiBufferSource buffer, int light, int overlay) {
		renderer.render(model.getOriginalModel(), light);
		if (SonicDogCannonUpgrade.DOG_COLLAR.isInstalled(stack))
			renderer.render(COLLAR.get(), light);

		float angle = getGearAngle(stack);
		poseStack.pushPose();
		// The gear's Blockbench pivot is [8, 7, 3.5], relative to the item model's [8, 8, 8] centre.
		poseStack.translate(0.0d, -1.0d / 16.0d, -4.5d / 16.0d);
		poseStack.mulPose(Axis.ZP.rotationDegrees(angle));
		poseStack.translate(0.0d, 1.0d / 16.0d, 4.5d / 16.0d);
		renderer.render(GEAR.get(), light);
		poseStack.popPose();

		if (SonicDogCannonUpgrade.SCOPE.isInstalled(stack)) {
			poseStack.pushPose();
			if (shouldMirrorScope(transformType)) {
				// CustomRenderedItemModelRenderer has already moved the origin to the model's
				// [8, 8, 8] centre, so a bare negative scale moves the part to the opposite side.
				poseStack.scale(-1.0f, 1.0f, 1.0f);
			}
			renderer.render((SonicDogCannonItem.isScopeFolded(stack) ? FOLDED_SCOPE : SCOPE).get(), light);
			poseStack.popPose();
		}
	}

	private static boolean shouldMirrorScope(ItemDisplayContext transformType) {
		if (transformType == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
			|| transformType == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
			return false;
		if (transformType == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
			|| transformType == ItemDisplayContext.THIRD_PERSON_LEFT_HAND)
			return true;

		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && player.getMainArm() == HumanoidArm.LEFT;
	}

	private static float getGearAngle(ItemStack stack) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || !player.isUsingItem() || player.getUseItem() != stack)
			return 0.0f;

		float elapsed = player.getTicksUsingItem() + AnimationTickHolder.getPartialTicks();
		float acceleratingTicks = Math.min(elapsed, SonicDogCannonItem.FULL_CHARGE_TICKS);
		float angle = -0.375f * acceleratingTicks * acceleratingTicks;
		if (elapsed > SonicDogCannonItem.FULL_CHARGE_TICKS)
			angle -= 30.0f * (elapsed - SonicDogCannonItem.FULL_CHARGE_TICKS);
		return angle % 360.0f;
	}
}
