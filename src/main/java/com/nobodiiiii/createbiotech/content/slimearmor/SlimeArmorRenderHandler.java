package com.nobodiiiii.createbiotech.content.slimearmor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.foundation.render.EntityRenderHelper;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

import org.jetbrains.annotations.Nullable;

/**
 * Renders a player wearing the full slime set while crouching as a small slime instead of Create's
 * cardboard package. Runs at {@link EventPriority#HIGHEST} and cancels the render event, which both
 * hides the vanilla player model and prevents Create's cardboard box renderer (priority HIGH) from
 * firing, since it does not receive cancelled events.
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = CreateBiotech.MOD_ID)
public class SlimeArmorRenderHandler {

	@Nullable
	private static Slime cachedSlime;
	@Nullable
	private static Level cachedLevel;

	private SlimeArmorRenderHandler() {}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void playerRendersAsSlimeWhenSneaking(RenderPlayerEvent.Pre event) {
		Player player = event.getEntity();
		if (!SlimeArmorHandler.testForSlimeStealth(player))
			return;

		event.setCanceled(true);

		if (player == Minecraft.getInstance().player
			&& Minecraft.getInstance().options.getCameraType() == CameraType.FIRST_PERSON)
			return;

		Slime slime = getOrCreateSlime(player.level());
		if (slime == null)
			return;

		PoseStack ms = event.getPoseStack();
		ms.pushPose();

		Vec3 renderOffset = event.getRenderer()
			.getRenderOffset((AbstractClientPlayer) player, event.getPartialTick());
		ms.translate(0, -renderOffset.y, 0);

		float movement = (float) player.position()
			.subtract(player.xo, player.yo, player.zo)
			.length();

		if (player.onGround())
			ms.translate(0,
				Math.min(Math.abs(Mth.cos((AnimationTickHolder.getRenderTime() % 256) / 2.0f)) * -renderOffset.y,
					movement * 5),
				0);

		float interpolatedYaw = Mth.lerp(event.getPartialTick(), player.yRotO, player.getYRot());
		float scale = player.getScale();
		ms.scale(scale, scale, scale);

		EntityRenderHelper.render(EntityRenderHelper.settings(slime)
			.packedLight(event.getPackedLight())
			.partialTicks(event.getPartialTick())
			.yaw(interpolatedYaw)
			.bodyYaw(interpolatedYaw)
			.headYaw(interpolatedYaw)
			.pitch(0f)
			.dispatcherYaw(interpolatedYaw), ms, event.getMultiBufferSource());

		ms.popPose();
	}

	@Nullable
	private static Slime getOrCreateSlime(Level level) {
		if (cachedSlime != null && cachedLevel == level)
			return cachedSlime;

		Slime slime = EntityType.SLIME.create(level);
		if (slime == null)
			return null;
		slime.setNoAi(true);
		slime.setSize(1, false);
		cachedLevel = level;
		cachedSlime = slime;
		return slime;
	}
}
