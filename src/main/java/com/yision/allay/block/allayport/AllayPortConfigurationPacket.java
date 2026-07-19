package com.yision.allay.block.allayport;

import com.simibubi.create.foundation.networking.BlockEntityConfigurationPacket;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.yision.allay.logistics.courier.AllayCourierReturnMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;

public class AllayPortConfigurationPacket extends BlockEntityConfigurationPacket<AllayPortBlockEntity> {

	private String newFilter;
	private boolean acceptPackages;
	private AllayCourierReturnMode returnMode;

	public AllayPortConfigurationPacket(BlockPos pos, String newFilter, boolean acceptPackages,
		AllayCourierReturnMode returnMode) {
		super(pos);
		this.newFilter = newFilter == null ? "" : newFilter;
		this.acceptPackages = acceptPackages;
		this.returnMode = returnMode == null ? AllayCourierReturnMode.DEFAULT_FOR_PORT : returnMode;
	}

	public AllayPortConfigurationPacket(RegistryFriendlyByteBuf buffer) {
		super(buffer.readBlockPos());
		readSettings(buffer);
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeBlockPos(pos);
		writeSettings(buffer);
	}

	protected void writeSettings(RegistryFriendlyByteBuf buffer) {
		buffer.writeBoolean(acceptPackages);
		buffer.writeUtf(newFilter);
		buffer.writeVarInt(returnMode.id());
	}

	protected void readSettings(RegistryFriendlyByteBuf buffer) {
		acceptPackages = buffer.readBoolean();
		newFilter = buffer.readUtf();
		returnMode = AllayCourierReturnMode.byId(buffer.readVarInt());
	}

	@Override
	protected void applySettings(ServerPlayer player, AllayPortBlockEntity be) {
		boolean filterChanged = !be.addressFilter.equals(newFilter) || be.acceptsPackages != acceptPackages;
		boolean modeChanged = be.getReturnMode() != returnMode;
		if (!filterChanged && !modeChanged) {
			return;
		}

		if (filterChanged) {
			be.addressFilter = newFilter;
			be.acceptsPackages = acceptPackages;
			be.filterChanged();
		}
		if (modeChanged) {
			be.setReturnMode(returnMode);
		}
		be.notifyUpdate();
	}

	@Override
	public PacketTypeProvider getTypeProvider() {
		return CBPackets.serverboundType();
	}
}
