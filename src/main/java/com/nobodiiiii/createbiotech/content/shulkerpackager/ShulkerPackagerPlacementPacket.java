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

	private final Collection<ArmInteractionPoint> points;
	private final ListTag receivedTag;
	private final BlockPos pos;

	public ShulkerPackagerPlacementPacket(Collection<ArmInteractionPoint> points, BlockPos pos) {
		this.points = points;
		this.pos = pos;
		this.receivedTag = null;
	}

	public ShulkerPackagerPlacementPacket(FriendlyByteBuf buffer) {
		CompoundTag nbt = buffer.readNbt();
		receivedTag = nbt == null ? new ListTag() : nbt.getList("Points", Tag.TAG_COMPOUND);
		pos = buffer.readBlockPos();
		points = null;
	}

	public void write(FriendlyByteBuf buffer) {
		CompoundTag nbt = new CompoundTag();
		ListTag pointsNBT = new ListTag();
		points.stream()
			.map(aip -> aip.serialize(pos))
			.forEach(pointsNBT::add);
		nbt.put("Points", pointsNBT);
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
			packager.setInteractionPointTag(receivedTag);
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
