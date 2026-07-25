package com.nobodiiiii.createbiotech.content.ghasthotairballoon;

import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBConfigs;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public class GhastBalloonMagnetTargetPacket {

	private final int entityId;
	@Nullable
	private final BlockPos targetPos;
	@Nullable
	private final UUID targetSubLevelId;

	public GhastBalloonMagnetTargetPacket(int entityId, @Nullable BlockPos target) {
		this(entityId, target, null);
	}

	public GhastBalloonMagnetTargetPacket(int entityId, @Nullable BlockPos target,
		@Nullable UUID targetSubLevelId) {
		this.entityId = entityId;
		this.targetPos = target;
		this.targetSubLevelId = target == null ? null : targetSubLevelId;
	}

	public GhastBalloonMagnetTargetPacket(FriendlyByteBuf buffer) {
		this.entityId = buffer.readVarInt();
		if (buffer.readBoolean()) {
			this.targetPos = buffer.readBlockPos();
			this.targetSubLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
		} else {
			this.targetPos = null;
			this.targetSubLevelId = null;
		}
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeVarInt(entityId);
		buffer.writeBoolean(targetPos != null);
		if (targetPos == null)
			return;
		buffer.writeBlockPos(targetPos);
		buffer.writeBoolean(targetSubLevelId != null);
		if (targetSubLevelId != null)
			buffer.writeUUID(targetSubLevelId);
	}

	public void handle(ServerPlayer player) {
		apply(player);
	}

	private void apply(ServerPlayer player) {
		if (player == null || player.isSpectator())
			return;
		Level level = player.level();
		if (level == null)
			return;
		Entity entity = level.getEntity(entityId);
		if (!(entity instanceof GhastHotAirBalloonEntity balloon))
			return;
		if (balloon.getControllingPlayer().filter(player.getUUID()::equals).isEmpty())
			return;
		if (!(balloon.getVehicle() instanceof Ghast ghast) || !ghast.isAlive())
			return;

		if (targetPos == null) {
			balloon.clearMagnetTarget();
			return;
		}

		// A replacement request is authoritative. If its target became invalid between the
		// client scan and server handling, do not keep steering toward the previous station.
		balloon.clearMagnetTarget();
		if (!level.isLoaded(targetPos))
			return;
		if (!SubLevelCompat.matchesSpace(level, targetPos, targetSubLevelId))
			return;
		BlockEntity be = level.getBlockEntity(targetPos);
		if (!(be instanceof GhastHotAirBalloonAssemblyStationBlockEntity station))
			return;
		if (!station.isReadyToAccept())
			return;

		var targetWorld = GhastHotAirBalloonAssemblyStationBlock.getGhastDockingWorldPosition(level, targetPos);
		if (ghast.position().distanceToSqr(targetWorld)
			> getMaxDistanceSqr())
			return;

		balloon.setMagnetTarget(targetPos, targetSubLevelId);
	}

	private static double getMaxDistanceSqr() {
		double distance = CBConfigs.SERVER.ghastHotAirBalloon.magnetMaxDistance.get();
		return distance * distance;
	}
}
