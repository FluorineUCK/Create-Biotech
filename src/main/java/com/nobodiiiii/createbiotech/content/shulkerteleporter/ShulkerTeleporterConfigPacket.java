package com.nobodiiiii.createbiotech.content.shulkerteleporter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public class ShulkerTeleporterConfigPacket {

	private final BlockPos pos;
	private final String ownAddress;
	private final String targetAddress;
	private final List<String> candidateAddresses;
	@Nullable
	private final UUID subLevelId;

	public ShulkerTeleporterConfigPacket(BlockPos pos, String ownAddress, String targetAddress,
		List<String> candidateAddresses, @Nullable UUID subLevelId) {
		this.pos = pos;
		this.ownAddress = ownAddress;
		this.targetAddress = targetAddress;
		this.candidateAddresses = ShulkerTeleporterBlockEntity.normalizeCandidateAddresses(candidateAddresses);
		this.subLevelId = subLevelId;
	}

	public ShulkerTeleporterConfigPacket(FriendlyByteBuf buffer) {
		pos = buffer.readBlockPos();
		ownAddress = buffer.readUtf(ShulkerTeleporterBlockEntity.MAX_ADDRESS_LENGTH);
		targetAddress = buffer.readUtf(ShulkerTeleporterBlockEntity.MAX_ADDRESS_LENGTH);
		int size = buffer.readVarInt();
		if (size < 0 || size > ShulkerTeleporterBlockEntity.MAX_CANDIDATE_ADDRESSES)
			throw new IllegalArgumentException("Invalid Shulker Teleporter candidate address count " + size);
		List<String> addresses = new ArrayList<>(size);
		for (int i = 0; i < size; i++)
			addresses.add(buffer.readUtf(ShulkerTeleporterBlockEntity.MAX_ADDRESS_LENGTH));
		candidateAddresses = ShulkerTeleporterBlockEntity.normalizeCandidateAddresses(addresses);
		subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeBlockPos(pos);
		buffer.writeUtf(ownAddress, ShulkerTeleporterBlockEntity.MAX_ADDRESS_LENGTH);
		buffer.writeUtf(targetAddress, ShulkerTeleporterBlockEntity.MAX_ADDRESS_LENGTH);
		buffer.writeVarInt(candidateAddresses.size());
		for (String candidateAddress : candidateAddresses)
			buffer.writeUtf(candidateAddress, ShulkerTeleporterBlockEntity.MAX_ADDRESS_LENGTH);
		buffer.writeBoolean(subLevelId != null);
		if (subLevelId != null)
			buffer.writeUUID(subLevelId);
	}

	public void handle(ServerPlayer player) {
		if (player == null)
			return;
		if (!(player.containerMenu instanceof ShulkerTeleporterMenu menu)
			|| !menu.getBlockPos().equals(pos)
			|| !Objects.equals(menu.getSubLevelId(), subLevelId))
			return;
		Level level = player.level();
		if (!level.isLoaded(pos) || !SubLevelCompat.matchesSpace(level, pos, subLevelId))
			return;
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (!(blockEntity instanceof ShulkerTeleporterBlockEntity teleporter)
			|| menu.getBlockEntity() != teleporter
			|| !teleporter.canPlayerUse(player))
			return;
		teleporter.setConfiguration(ownAddress, targetAddress, candidateAddresses);
	}
}
