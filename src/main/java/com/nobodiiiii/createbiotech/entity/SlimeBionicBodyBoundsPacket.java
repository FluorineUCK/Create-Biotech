package com.nobodiiiii.createbiotech.entity;

import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Lets the measuring client finalize the authoritative bounds of legacy and newly packed bodies. */
public record SlimeBionicBodyBoundsPacket(int entityId, SurgicalAssembly.BodyBounds bounds) {
	private static final double MAX_REPORT_DISTANCE_SQR = 128.0d * 128.0d;

	public SlimeBionicBodyBoundsPacket(FriendlyByteBuf buffer) {
		this(buffer.readVarInt(), readBounds(buffer));
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeVarInt(entityId);
		buffer.writeFloat(bounds.width());
		buffer.writeFloat(bounds.height());
		buffer.writeFloat(bounds.depth());
		buffer.writeFloat(bounds.centerX());
		buffer.writeFloat(bounds.minY());
		buffer.writeFloat(bounds.centerZ());
		buffer.writeFloat(bounds.legLength());
	}

	public void handle(ServerPlayer player) {
		if (player == null)
			return;
		Entity found = player.level().getEntity(entityId);
		if (!(found instanceof SlimeBionicEntity bionic)
			|| player.distanceToSqr(bionic) > MAX_REPORT_DISTANCE_SQR)
			return;
		SurgicalAssembly assembly = bionic.getAssembly();
		if (assembly == null || !reasonableCorrection(assembly.bodyBounds(), bounds))
			return;
		bionic.setAssembly(assembly.withBodyBounds(bounds));
	}

	private static SurgicalAssembly.BodyBounds readBounds(FriendlyByteBuf buffer) {
		SurgicalAssembly.BodyBounds bounds = SurgicalAssembly.BodyBounds.create(
			buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
			buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
		if (bounds == null)
			throw new IllegalArgumentException("Invalid bionic body bounds");
		return bounds;
	}

	private static boolean reasonableCorrection(SurgicalAssembly.BodyBounds existing,
		SurgicalAssembly.BodyBounds measured) {
		return existing == null || close(existing.width(), measured.width())
			&& close(existing.height(), measured.height()) && close(existing.depth(), measured.depth())
			&& closeOffset(existing.centerX(), measured.centerX())
			&& closeOffset(existing.minY(), measured.minY())
			&& closeOffset(existing.centerZ(), measured.centerZ())
			&& reasonableLegLength(existing.legLength(), measured.legLength());
	}

	private static boolean close(float expected, float measured) {
		return Math.abs(expected - measured) <= Math.max(0.5f, expected * 0.25f);
	}

	private static boolean closeOffset(float expected, float measured) {
		return Math.abs(expected - measured) <= 0.5f;
	}

	private static boolean reasonableLegLength(float expected, float measured) {
		return expected == 0.0f || measured > 0.0f && close(expected, measured);
	}
}
