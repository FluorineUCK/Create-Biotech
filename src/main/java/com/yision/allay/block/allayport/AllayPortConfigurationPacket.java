package com.yision.allay.block.allayport;

import com.simibubi.create.foundation.networking.BlockEntityConfigurationPacket;
import com.simibubi.create.foundation.utility.AdventureUtil;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.yision.allay.logistics.courier.AllayCourierReturnMode;
import com.yision.allay.logistics.address.AllayAddressRules;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class AllayPortConfigurationPacket extends BlockEntityConfigurationPacket<AllayPortBlockEntity> {

	private String newFilter;
	private boolean acceptPackages;
	private AllayCourierReturnMode returnMode;
	private @Nullable UUID subLevelId;

	public AllayPortConfigurationPacket(BlockPos pos, String newFilter, boolean acceptPackages,
		AllayCourierReturnMode returnMode, @Nullable UUID subLevelId) {
		super(pos);
		this.newFilter = AllayAddressRules.normalizePortAddress(newFilter);
		this.acceptPackages = acceptPackages;
		this.returnMode = returnMode == null ? AllayCourierReturnMode.DEFAULT_FOR_PORT : returnMode;
		this.subLevelId = subLevelId;
	}

	public AllayPortConfigurationPacket(RegistryFriendlyByteBuf buffer) {
		super(buffer.readBlockPos());
		readSettings(buffer);
		readSpaceIdentity(buffer);
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeBlockPos(pos);
		writeSettings(buffer);
		buffer.writeBoolean(subLevelId != null);
		if (subLevelId != null) {
			buffer.writeUUID(subLevelId);
		}
	}

	protected void writeSettings(RegistryFriendlyByteBuf buffer) {
		buffer.writeBoolean(acceptPackages);
		buffer.writeUtf(newFilter, AllayAddressRules.MAX_PORT_ADDRESS_LENGTH);
		buffer.writeVarInt(returnMode.id());
	}

	protected void readSettings(RegistryFriendlyByteBuf buffer) {
		acceptPackages = buffer.readBoolean();
		newFilter = AllayAddressRules.normalizePortAddress(
			buffer.readUtf(AllayAddressRules.MAX_PORT_ADDRESS_LENGTH));
		returnMode = AllayCourierReturnMode.byId(buffer.readVarInt());
	}

	private void readSpaceIdentity(RegistryFriendlyByteBuf buffer) {
		subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
	}

	@Override
	public void handle(ServerPlayer player) {
		if (player == null || player.isSpectator() || AdventureUtil.isAdventure(player)) {
			return;
		}
		if (!(player.containerMenu instanceof AllayPortMenu menu)
			|| !menu.getBlockPos().equals(pos)) {
			return;
		}
		Level level = player.level();
		if (!level.isLoaded(pos)) {
			return;
		}
		if (!SubLevelCompat.matchesSpace(level, pos, subLevelId)) {
			return;
		}
		double interactionRange = player.blockInteractionRange() + maxRange();
		if (new AABB(pos).distanceToSqr(SubLevelCompat.toLocal(level, pos, player.getEyePosition()))
			>= interactionRange * interactionRange) {
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (!(blockEntity instanceof AllayPortBlockEntity allayPort)
			|| menu.getBlockEntity() != allayPort) {
			return;
		}

		applySettings(player, allayPort);
		if (causeUpdate()) {
			allayPort.sendData();
			allayPort.setChanged();
		}
	}

	@Override
	protected void applySettings(ServerPlayer player, AllayPortBlockEntity be) {
		if (be.getLevel() == null) {
			return;
		}
		if (!SubLevelCompat.matchesSpace(be.getLevel(), pos, subLevelId)) {
			return;
		}
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
