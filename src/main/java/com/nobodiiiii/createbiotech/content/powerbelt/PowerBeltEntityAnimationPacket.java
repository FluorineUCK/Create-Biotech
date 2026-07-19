package com.nobodiiiii.createbiotech.content.powerbelt;

import com.nobodiiiii.createbiotech.client.PowerBeltClientAnimationHandler;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

public class PowerBeltEntityAnimationPacket {

	private static final float MAX_SURFACE_MOVEMENT = 1.0f;

	private final int entityId;
	private final float distance;

	public PowerBeltEntityAnimationPacket(int entityId, float distance) {
		this.entityId = entityId;
		this.distance = distance;
	}

	public PowerBeltEntityAnimationPacket(FriendlyByteBuf buffer) {
		this(buffer.readVarInt(), buffer.readFloat());
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeVarInt(entityId);
		buffer.writeFloat(distance);
	}

	public void handle(LocalPlayer player) {
		if (!Float.isFinite(distance))
			return;

		float clampedDistance = Mth.clamp(distance, 0, MAX_SURFACE_MOVEMENT);
		PowerBeltClientAnimationHandler.handleSurfaceMovement(entityId, clampedDistance);
	}
}
