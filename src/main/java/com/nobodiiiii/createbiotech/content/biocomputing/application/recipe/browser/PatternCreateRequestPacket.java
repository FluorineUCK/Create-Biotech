package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.BrowserFence;

import java.util.Objects;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** The server selects the writable source page; clients provide only recipe facts and request fences. */
public record PatternCreateRequestPacket(int containerId, BrowserFence fence, long intentId, String editedJson)
	implements ServerboundPacketPayload {
	public PatternCreateRequestPacket {
		Objects.requireNonNull(fence); Objects.requireNonNull(editedJson);
		if (containerId < 0 || intentId < 0 || editedJson.length() > PatternEditRequestPacket.MAX_JSON)
			throw new IllegalArgumentException("invalid Pattern create request");
	}
	public PatternCreateRequestPacket(RegistryFriendlyByteBuf buffer) {
		this(buffer.readVarInt(), PatternBrowserWire.readFence(buffer), buffer.readVarLong(),
			buffer.readUtf(PatternEditRequestPacket.MAX_JSON));
	}
	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(containerId); PatternBrowserWire.writeFence(buffer, fence);
		buffer.writeVarLong(intentId); buffer.writeUtf(editedJson, PatternEditRequestPacket.MAX_JSON);
	}
	@Override public net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider getTypeProvider() {
		return com.nobodiiiii.createbiotech.network.CBPackets.patternCreateRequestType();
	}
	@Override public void handle(net.minecraft.server.level.ServerPlayer player) {
		if (player.containerMenu instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu menu)
			menu.handleCreate(player, this);
	}
}
