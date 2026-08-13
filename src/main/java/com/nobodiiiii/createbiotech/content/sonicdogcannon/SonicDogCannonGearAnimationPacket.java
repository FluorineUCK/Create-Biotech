package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import com.nobodiiiii.createbiotech.client.SonicDogCannonItemRenderer;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;

public record SonicDogCannonGearAnimationPacket(int shooterId, InteractionHand hand, int chargeTicks) {

	public SonicDogCannonGearAnimationPacket(RegistryFriendlyByteBuf buffer) {
		this(buffer.readVarInt(), buffer.readEnum(InteractionHand.class), buffer.readVarInt());
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(shooterId);
		buffer.writeEnum(hand);
		buffer.writeVarInt(chargeTicks);
	}

	public void handle(LocalPlayer player) {
		SonicDogCannonItemRenderer.onFired(player, shooterId, hand, chargeTicks);
	}
}
