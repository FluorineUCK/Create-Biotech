package com.yision.allay.block.allayport;

import com.simibubi.create.foundation.networking.BlockEntityDataPacket;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class AllayPortFlapPacket extends BlockEntityDataPacket<AllayPortBlockEntity> {

	private final boolean inwards;
	private final @Nullable UUID subLevelId;

	public AllayPortFlapPacket(RegistryFriendlyByteBuf buffer) {
		super(buffer.readBlockPos());
		inwards = buffer.readBoolean();
		subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
	}

	public AllayPortFlapPacket(AllayPortBlockEntity blockEntity, boolean inwards) {
		super(blockEntity.getBlockPos());
		this.inwards = inwards;
		this.subLevelId = blockEntity.getLevel() == null ? null
			: SubLevelCompat.getSpaceId(blockEntity.getLevel(), blockEntity.getBlockPos());
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeBlockPos(pos);
		buffer.writeBoolean(inwards);
		buffer.writeBoolean(subLevelId != null);
		if (subLevelId != null) {
			buffer.writeUUID(subLevelId);
		}
	}

	@Override
	protected void handlePacket(AllayPortBlockEntity blockEntity) {
		if (blockEntity.getLevel() != null
			&& !SubLevelCompat.matchesSpace(blockEntity.getLevel(), pos, subLevelId)) {
			return;
		}
		blockEntity.flap(inwards);
	}

	@Override
	public PacketTypeProvider getTypeProvider() {
		return CBPackets.clientboundType();
	}
}
