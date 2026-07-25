package com.nobodiiiii.createbiotech.content.powerbelt;

import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.content.kinetics.belt.BeltSlope;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class PowerBeltSurfaceMovementPacket {

	private final BlockPos pos;
	private final float surfaceSpeed;
	@Nullable
	private final UUID subLevelId;

	public PowerBeltSurfaceMovementPacket(BlockPos pos, float surfaceSpeed, @Nullable UUID subLevelId) {
		this.pos = pos;
		this.surfaceSpeed = surfaceSpeed;
		this.subLevelId = subLevelId;
	}

	public PowerBeltSurfaceMovementPacket(FriendlyByteBuf buffer) {
		this(buffer.readBlockPos(), buffer.readFloat(), buffer.readBoolean() ? buffer.readUUID() : null);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeBlockPos(pos);
		buffer.writeFloat(surfaceSpeed);
		buffer.writeBoolean(subLevelId != null);
		if (subLevelId != null)
			buffer.writeUUID(subLevelId);
	}

	public void handle(ServerPlayer player) {
		apply(player);
	}

	private void apply(ServerPlayer player) {
		if (player == null || player.isSpectator() || player.getAbilities().flying)
			return;
		if (!Float.isFinite(surfaceSpeed))
			return;

		Level level = player.level();
		if (level == null)
			return;
		if (!level.isLoaded(pos) || !SubLevelCompat.matchesSpace(level, pos, subLevelId))
			return;
		if (!SubLevelCompat.canEntityInteractWith(level, pos, player))
			return;
		double distanceSqr = SubLevelCompat.distanceSquared(level, player.position(), Vec3.atCenterOf(pos));
		if (!Double.isFinite(distanceSqr) || distanceSqr > 16.0)
			return;
		BlockState state = level.getBlockState(pos);
		if (!PowerBeltBlock.isPowerBelt(state)
			|| state.getValue(PowerBeltBlock.SLOPE) != BeltSlope.HORIZONTAL)
			return;
		if (!PowerBeltBlock.isEntityOnBeltSurface(level, pos, player))
			return;

		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity instanceof PowerBeltBlockEntity powerBelt)
			powerBelt.addSurfaceMovement(Mth.clamp(surfaceSpeed, -getMaxPlayerSurfaceSpeed(),
				getMaxPlayerSurfaceSpeed()));
	}

	private static float getMaxPlayerSurfaceSpeed() {
		return CBConfigs.SERVER.powerBelt.maxPlayerSurfaceSpeed.get().floatValue();
	}
}
