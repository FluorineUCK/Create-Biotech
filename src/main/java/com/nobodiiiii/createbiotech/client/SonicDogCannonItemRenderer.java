package com.nobodiiiii.createbiotech.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.sonicdogcannon.SonicDogCannonItem;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class SonicDogCannonItemRenderer extends CustomRenderedItemModelRenderer {

	public static final ResourceLocation GEAR_MODEL_LOCATION =
		CreateBiotech.asResource("item/sonic_dog_cannon/gear");
	private static final PartialModel GEAR = PartialModel.of(GEAR_MODEL_LOCATION);

	@Override
	protected void render(ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer,
		ItemDisplayContext transformType, PoseStack poseStack, MultiBufferSource buffer, int light, int overlay) {
		renderer.render(model.getOriginalModel(), light);

		float angle = getGearAngle(stack);
		poseStack.pushPose();
		// The gear's Blockbench pivot is [8, 7, 3.5], relative to the item model's [8, 8, 8] centre.
		poseStack.translate(0.0d, -1.0d / 16.0d, -4.5d / 16.0d);
		poseStack.mulPose(Axis.ZP.rotationDegrees(angle));
		poseStack.translate(0.0d, 1.0d / 16.0d, 4.5d / 16.0d);
		renderer.render(GEAR.get(), light);
		poseStack.popPose();
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
