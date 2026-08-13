package com.nobodiiiii.createbiotech.client;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class SonicDogCannonChargeSoundHandler {
	private static final Map<Integer, SonicDogCannonChargeSound> ACTIVE_SOUNDS = new HashMap<>();

	private SonicDogCannonChargeSoundHandler() {}

	public static void setPlaying(LocalPlayer localPlayer, int shooterId, boolean playing) {
		stop(shooterId);
		if (!playing)
			return;

		ClientLevel level = localPlayer.clientLevel;
		Entity entity = level.getEntity(shooterId);
		if (!(entity instanceof Player shooter))
			return;

		SonicDogCannonChargeSound sound = new SonicDogCannonChargeSound(shooter);
		ACTIVE_SOUNDS.put(shooterId, sound);
		Minecraft.getInstance().getSoundManager().play(sound);
	}

	private static void stop(int shooterId) {
		SonicDogCannonChargeSound sound = ACTIVE_SOUNDS.remove(shooterId);
		if (sound == null)
			return;
		sound.stopSound();
		Minecraft.getInstance().getSoundManager().stop(sound);
	}
}
