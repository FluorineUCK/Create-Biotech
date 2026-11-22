package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.BrowserFence;

import java.util.Objects;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record PatternEditResultPacket(int containerId, BrowserFence fence, long intentId, String result) implements ClientboundPacketPayload {
	public static final int MAX_RESULT = 16;
	public PatternEditResultPacket {
		Objects.requireNonNull(fence); Objects.requireNonNull(result);
		if (containerId < 0 || intentId < 0 || result.length() > MAX_RESULT || !result.matches("[a-z_]+"))
			throw new IllegalArgumentException("invalid Pattern edit result");
	}
	public PatternEditResultPacket(RegistryFriendlyByteBuf buffer) { this(buffer.readVarInt(), PatternBrowserWire.readFence(buffer), buffer.readVarLong(), buffer.readUtf(MAX_RESULT)); }
	public void write(RegistryFriendlyByteBuf buffer) { buffer.writeVarInt(containerId); PatternBrowserWire.writeFence(buffer, fence); buffer.writeVarLong(intentId); buffer.writeUtf(result, MAX_RESULT); }
	@Override public net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider getTypeProvider() {
		return com.nobodiiiii.createbiotech.network.CBPackets.patternEditResultType();
	}
	@Override @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
	public void handle(net.minecraft.client.player.LocalPlayer player) {
		if (player.containerMenu instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu menu)
			menu.acceptEditResult(this);
	}
}
