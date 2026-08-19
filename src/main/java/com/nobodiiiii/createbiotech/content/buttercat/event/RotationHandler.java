package com.nobodiiiii.createbiotech.content.buttercat.event;

import com.nobodiiiii.createbiotech.content.buttercat.ButterRotation;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.registry.CBMobEffects;

import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;


@EventBusSubscriber(modid = CreateBiotech.MOD_ID, value = Dist.CLIENT)
public class RotationHandler {
	private static final double MAX_CONTINUOUS_FRAME_GAP_TICKS = 20.0D;
	private static Player trackedPlayer;
	private static double lastFrameTime = Double.NaN;

	@SubscribeEvent
	public static void onRenderFrame(RenderFrameEvent.Pre event) {
		Player player = Minecraft.getInstance().player;
		if (player == null) {
			trackedPlayer = null;
			lastFrameTime = Double.NaN;
			return;
		}

		float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
		double frameTime = player.tickCount + partialTick;
		if (player != trackedPlayer || Double.isNaN(lastFrameTime)) {
			trackedPlayer = player;
			lastFrameTime = frameTime;
			return;
		}

		double elapsedTicks = frameTime - lastFrameTime;
		lastFrameTime = frameTime;
		if (elapsedTicks <= 0.0D || elapsedTicks > MAX_CONTINUOUS_FRAME_GAP_TICKS)
			return;

		MobEffectInstance effect = player.getEffect(CBMobEffects.BUTTER_ROTATION.getDelegate());
		if (effect == null)
			return;

		float newYaw = player.getYRot()
			+ ButterRotation.getTickAngleSpeed(effect.getAmplifier()) * (float) elapsedTicks;
		player.setYRot(newYaw);
		player.setYHeadRot(newYaw);
		player.setYBodyRot(newYaw);
		player.yRotO = newYaw;
		player.yHeadRotO = newYaw;
		player.yBodyRotO = newYaw;
	}
}

