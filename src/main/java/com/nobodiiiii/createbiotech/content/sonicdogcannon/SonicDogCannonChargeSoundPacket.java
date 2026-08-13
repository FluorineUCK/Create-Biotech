package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import com.nobodiiiii.createbiotech.client.SonicDogCannonChargeSoundHandler;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record SonicDogCannonChargeSoundPacket(int shooterId, boolean playing) {

	public SonicDogCannonChargeSoundPacket(RegistryFriendlyByteBuf buffer) {
		this(buffer.readVarInt(), buffer.readBoolean());
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(shooterId);
		buffer.writeBoolean(playing);
	}

	public void handle(LocalPlayer player) {
		SonicDogCannonChargeSoundHandler.setPlaying(player, shooterId, playing);
	}
}
