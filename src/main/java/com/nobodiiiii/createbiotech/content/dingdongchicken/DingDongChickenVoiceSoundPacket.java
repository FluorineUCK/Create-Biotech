package com.nobodiiiii.createbiotech.content.dingdongchicken;

import com.nobodiiiii.createbiotech.client.DingDongChickenVoiceSoundHandler;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record DingDongChickenVoiceSoundPacket(int entityId) {

	public DingDongChickenVoiceSoundPacket(RegistryFriendlyByteBuf buffer) {
		this(buffer.readVarInt());
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(entityId);
	}

	public void handle(LocalPlayer player) {
		DingDongChickenVoiceSoundHandler.play(player, entityId);
	}
}
