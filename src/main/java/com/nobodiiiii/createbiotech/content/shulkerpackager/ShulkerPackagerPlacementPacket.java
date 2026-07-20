package com.nobodiiiii.createbiotech.content.shulkerpackager;

import java.util.Collection;

import com.nobodiiiii.createbiotech.network.CBPackets;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public class ShulkerPackagerPlacementPacket {

	private final ListTag points;
	private final BlockPos pos;

	public ShulkerPackagerPlacementPacket(Collection<ArmInteractionPoint> points, BlockPos pos) {
		this.points = new ListTag();
		for (ArmInteractionPoint point : points)
			this.points.add(point.serialize(pos));
		this.pos = pos;
	}

	public ShulkerPackagerPlacementPacket(FriendlyByteBuf buffer) {
		CompoundTag nbt = buffer.readNbt();
		points = nbt == null ? new ListTag() : nbt.getList("Points", Tag.TAG_COMPOUND);
		pos = buffer.readBlockPos();
	}

	public void write(FriendlyByteBuf buffer) {
		CompoundTag nbt = new CompoundTag();
		nbt.put("Points", points);
		buffer.writeNbt(nbt);
		buffer.writeBlockPos(pos);
	}

	public void handle(ServerPlayer player) {
		if (player == null)
			return;
		Level world = player.level();
		if (world == null || !world.isLoaded(pos))
			return;
		BlockEntity blockEntity = world.getBlockEntity(pos);
		if (blockEntity instanceof ShulkerPackagerBlockEntity packager)
			packager.setInteractionPointTag(points);
	}

	public static class ClientBoundRequest {

		private final BlockPos pos;

		public ClientBoundRequest(BlockPos pos) {
			this.pos = pos;
		}

		public ClientBoundRequest(FriendlyByteBuf buffer) {
			this.pos = buffer.readBlockPos();
		}

		public void write(FriendlyByteBuf buffer) {
			buffer.writeBlockPos(pos);
		}

		public void handle(LocalPlayer player) {
			ShulkerPackagerConnectionHandler.flushSettings(pos);
		}
	}
}
