package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import com.nobodiiiii.createbiotech.client.SonicDogCannonClientEffects;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

public record SonicDogCannonFirePacket(int shooterId, InteractionHand hand, Vec3 direction) {

	public SonicDogCannonFirePacket(RegistryFriendlyByteBuf buffer) {
		this(buffer.readVarInt(), buffer.readEnum(InteractionHand.class),
			new Vec3(buffer.readFloat(), buffer.readFloat(), buffer.readFloat()));
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(shooterId);
		buffer.writeEnum(hand);
		buffer.writeFloat((float) direction.x);
		buffer.writeFloat((float) direction.y);
		buffer.writeFloat((float) direction.z);
	}

	public void handle(LocalPlayer player) {
		SonicDogCannonClientEffects.fire(player, shooterId, hand, direction);
	}
}
