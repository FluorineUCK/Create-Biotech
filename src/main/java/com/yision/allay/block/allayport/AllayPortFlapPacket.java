package com.yision.allay.block.allayport;

import com.simibubi.create.foundation.networking.BlockEntityDataPacket;
import com.nobodiiiii.createbiotech.network.CBPackets;

import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.minecraft.network.RegistryFriendlyByteBuf;

public class AllayPortFlapPacket extends BlockEntityDataPacket<AllayPortBlockEntity> {

	private final boolean inwards;

	public AllayPortFlapPacket(RegistryFriendlyByteBuf buffer) {
		super(buffer.readBlockPos());
		inwards = buffer.readBoolean();
	}

	public AllayPortFlapPacket(AllayPortBlockEntity blockEntity, boolean inwards) {
		super(blockEntity.getBlockPos());
		this.inwards = inwards;
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeBlockPos(pos);
		buffer.writeBoolean(inwards);
	}

	@Override
	protected void handlePacket(AllayPortBlockEntity blockEntity) {
		blockEntity.flap(inwards);
	}

	@Override
	public PacketTypeProvider getTypeProvider() {
		return CBPackets.clientboundType();
	}
}
