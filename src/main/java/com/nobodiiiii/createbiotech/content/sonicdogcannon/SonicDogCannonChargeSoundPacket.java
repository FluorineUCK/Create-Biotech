package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import com.nobodiiiii.createbiotech.client.SonicDogCannonChargeSoundHandler;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record SonicDogCannonChargeSoundPacket(int shooterId, Action action) {

	public SonicDogCannonChargeSoundPacket(RegistryFriendlyByteBuf buffer) {
		this(buffer.readVarInt(), buffer.readEnum(Action.class));
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(shooterId);
		buffer.writeEnum(action);
	}

	public void handle(LocalPlayer player) {
		SonicDogCannonChargeSoundHandler.handle(player, shooterId, action);
	}

	public enum Action {
		DEFAULT_START,
		VOICE_PACK_START,
		VOICE_PACK_LOOP,
		STOP
	}
}
