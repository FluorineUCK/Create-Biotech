package com.nobodiiiii.createbiotech.content.shulkerpackager;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.network.CBPackets;
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
	@Nullable
	private final UUID subLevelId;
	private final UUID requestNonce;
	private final boolean exceedsOutputLimit;
	private final int requestedPointCount;

	public ShulkerPackagerPlacementPacket(Collection<ShulkerPackagerTarget> points, BlockPos pos,
		@Nullable UUID subLevelId, UUID requestNonce) {
		if (points.size() > ShulkerPackagerBlockEntity.MAX_OUTPUTS)
			throw new IllegalArgumentException("Too many shulker packager outputs: " + points.size());
		this.points = new ListTag();
		exceedsOutputLimit = false;
		requestedPointCount = points.size();
		for (ShulkerPackagerTarget point : points) {
			if (this.points.size() >= ShulkerPackagerBlockEntity.MAX_OUTPUTS)
				break;
			this.points.add(point.serialize(pos));
		}
		this.pos = pos;
		this.subLevelId = subLevelId;
		this.requestNonce = requestNonce;
	}

	public ShulkerPackagerPlacementPacket(FriendlyByteBuf buffer) {
		CompoundTag nbt = buffer.readNbt();
		ListTag decodedPoints = nbt == null ? new ListTag() : nbt.getList("Points", Tag.TAG_COMPOUND);
		requestedPointCount = decodedPoints.size();
		exceedsOutputLimit = decodedPoints.size() > ShulkerPackagerBlockEntity.MAX_OUTPUTS;
		points = exceedsOutputLimit ? new ListTag() : decodedPoints;
		pos = buffer.readBlockPos();
		subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
		requestNonce = buffer.readUUID();
	}

	public void write(FriendlyByteBuf buffer) {
		if (exceedsOutputLimit || points.size() > ShulkerPackagerBlockEntity.MAX_OUTPUTS)
			throw new IllegalStateException("Refusing to encode too many shulker packager outputs");
		CompoundTag nbt = new CompoundTag();
		nbt.put("Points", points);
		buffer.writeNbt(nbt);
		buffer.writeBlockPos(pos);
		buffer.writeBoolean(subLevelId != null);
		if (subLevelId != null)
			buffer.writeUUID(subLevelId);
		buffer.writeUUID(requestNonce);
	}

	public void handle(ServerPlayer player) {
		if (player == null)
			return;
		Level world = player.level();
		if (world == null || !world.isLoaded(pos) || !SubLevelCompat.matchesSpace(world, pos, subLevelId)) {
			reject(player);
			return;
		}
		BlockEntity blockEntity = world.getBlockEntity(pos);
		if (!(blockEntity instanceof ShulkerPackagerBlockEntity packager)) {
			reject(player);
			return;
		}
		if (exceedsOutputLimit || points.size() > ShulkerPackagerBlockEntity.MAX_OUTPUTS) {
			reject(player);
			return;
		}
		// The nonce is issued only after this player successfully placed this exact BE.
		// It is the authority for the asynchronous reply; rechecking physical reach here
		// would race a moving sublevel between the clientbound request and serverbound reply.
		if (!packager.consumePlacementConfiguration(player, requestNonce)) {
			reject(player);
			return;
		}

		ListTag validatedPoints = new ListTag();
		Set<ShulkerPackagerTarget.Address> seenTargets = new HashSet<>();
		for (Tag tag : points) {
			if (!(tag instanceof CompoundTag pointTag))
				continue;
			ShulkerPackagerTarget point = ShulkerPackagerTarget.fromTag(pointTag, world, pos);
			if (point == null)
				continue;
			// Match Aero:Addition's binding model: an address remains bound while structures
			// move, and physical range/selectability/loading are runtime transfer gates. The
			// server still verifies the explicit logical-space identity before accepting it.
			if (!SubLevelCompat.matchesSpace(world, point.getPos(), point.targetSubLevelId()))
				continue;
			if (!seenTargets.add(point.address()))
				continue;
			validatedPoints.add(point.forAnchor(subLevelId)
				.serialize(pos));
		}
		packager.setInteractionPointTag(validatedPoints);
		CBPackets.sendToPlayer(new ClientBoundResult(requestNonce, true, requestedPointCount,
			validatedPoints.size()), player);
	}

	private void reject(ServerPlayer player) {
		CBPackets.sendToPlayer(new ClientBoundResult(requestNonce, false, requestedPointCount, 0), player);
	}

	public static class ClientBoundRequest {

		private final BlockPos pos;
		@Nullable
		private final UUID subLevelId;
		private final UUID requestNonce;
		private final int placementSequence;

		public ClientBoundRequest(BlockPos pos, @Nullable UUID subLevelId, UUID requestNonce,
			int placementSequence) {
			this.pos = pos;
			this.subLevelId = subLevelId;
			this.requestNonce = requestNonce;
			this.placementSequence = placementSequence;
		}

		public ClientBoundRequest(FriendlyByteBuf buffer) {
			this.pos = buffer.readBlockPos();
			this.subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
			this.requestNonce = buffer.readUUID();
			this.placementSequence = buffer.readVarInt();
		}

		public void write(FriendlyByteBuf buffer) {
			buffer.writeBlockPos(pos);
			buffer.writeBoolean(subLevelId != null);
			if (subLevelId != null)
				buffer.writeUUID(subLevelId);
			buffer.writeUUID(requestNonce);
			buffer.writeVarInt(placementSequence);
		}

		public void handle(LocalPlayer player) {
			ShulkerPackagerConnectionHandler.flushSettings(pos, subLevelId, requestNonce, placementSequence);
		}
	}

	public static class ClientBoundResult {

		private final UUID requestNonce;
		private final boolean accepted;
		private final int requestedOutputs;
		private final int acceptedOutputs;

		public ClientBoundResult(UUID requestNonce, boolean accepted, int requestedOutputs, int acceptedOutputs) {
			this.requestNonce = requestNonce;
			this.accepted = accepted;
			this.requestedOutputs = requestedOutputs;
			this.acceptedOutputs = acceptedOutputs;
		}

		public ClientBoundResult(FriendlyByteBuf buffer) {
			requestNonce = buffer.readUUID();
			accepted = buffer.readBoolean();
			requestedOutputs = buffer.readVarInt();
			acceptedOutputs = buffer.readVarInt();
		}

		public void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(requestNonce);
			buffer.writeBoolean(accepted);
			buffer.writeVarInt(requestedOutputs);
			buffer.writeVarInt(acceptedOutputs);
		}

		public void handle(LocalPlayer player) {
			ShulkerPackagerConnectionHandler.handlePlacementResult(requestNonce, accepted, requestedOutputs,
				acceptedOutputs);
		}
	}
}
