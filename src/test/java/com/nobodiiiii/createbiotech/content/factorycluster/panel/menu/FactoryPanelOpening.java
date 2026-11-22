package com.nobodiiiii.createbiotech.content.factorycluster.panel.menu;

import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** The complete and deliberately minimal Factory Panel menu-opening payload. */
public record FactoryPanelOpening(SpaceAddress panelAddress, UUID panelId,
	UUID menuSessionId, UUID clusterId, UUID epochId) {
	public FactoryPanelOpening {
		Objects.requireNonNull(panelAddress, "panelAddress");
		Objects.requireNonNull(panelId, "panelId");
		Objects.requireNonNull(menuSessionId, "menuSessionId");
		Objects.requireNonNull(clusterId, "clusterId");
		Objects.requireNonNull(epochId, "epochId");
	}

	public FactoryPanelOpening(RegistryFriendlyByteBuf buffer) {
		this(readAddress(buffer), buffer.readUUID(), buffer.readUUID(), buffer.readUUID(),
			buffer.readUUID());
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		Objects.requireNonNull(buffer, "buffer");
		writeAddress(buffer, panelAddress);
		buffer.writeUUID(panelId);
		buffer.writeUUID(menuSessionId);
		buffer.writeUUID(clusterId);
		buffer.writeUUID(epochId);
	}

	private static void writeAddress(RegistryFriendlyByteBuf buffer, SpaceAddress address) {
		buffer.writeResourceLocation(address.dimension().location());
		buffer.writeBoolean(address.subLevelId() != null);
		if (address.subLevelId() != null) buffer.writeUUID(address.subLevelId());
		buffer.writeBlockPos(address.localPos());
	}

	private static SpaceAddress readAddress(RegistryFriendlyByteBuf buffer) {
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
			buffer.readResourceLocation());
		UUID subLevel = buffer.readBoolean() ? buffer.readUUID() : null;
		return new SpaceAddress(dimension, subLevel, buffer.readBlockPos());
	}
}
