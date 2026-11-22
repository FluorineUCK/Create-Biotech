package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.BrowserFence;

import java.util.Objects;
import java.util.UUID;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Contains editable facts only: the source page is always recovered server-side from patternId. */
public record PatternEditRequestPacket(int containerId, BrowserFence fence, long intentId, UUID patternId, String editedJson)
	implements ServerboundPacketPayload {
	public static final int MAX_JSON = net.minecraft.world.item.component.WritableBookContent.PAGE_EDIT_LENGTH;
	public PatternEditRequestPacket {
		Objects.requireNonNull(fence); Objects.requireNonNull(patternId); Objects.requireNonNull(editedJson);
		if (containerId < 0 || intentId < 0 || editedJson.length() > MAX_JSON)
			throw new IllegalArgumentException("invalid Pattern edit request");
	}
	public PatternEditRequestPacket(RegistryFriendlyByteBuf buffer) {
		this(buffer.readVarInt(), PatternBrowserWire.readFence(buffer), buffer.readVarLong(), buffer.readUUID(), buffer.readUtf(MAX_JSON));
	}
	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(containerId); PatternBrowserWire.writeFence(buffer, fence); buffer.writeVarLong(intentId); buffer.writeUUID(patternId);
		buffer.writeUtf(editedJson, MAX_JSON);
	}
	@Override public net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider getTypeProvider() {
		return com.nobodiiiii.createbiotech.network.CBPackets.patternEditRequestType();
	}
	@Override public void handle(net.minecraft.server.level.ServerPlayer player) {
		if (player.containerMenu instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu menu)
			menu.handleEdit(player, this);
	}
}
