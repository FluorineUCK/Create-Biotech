package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record PatternBrowserResponsePacket(int containerId, long intentId, PatternBrowserPage page, PatternBrowserDiagnostics diagnostics) implements net.createmod.catnip.net.base.ClientboundPacketPayload {
    @Override public net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider getTypeProvider() {
        return com.nobodiiiii.createbiotech.network.CBPackets.patternBrowserResponseType();
    }
    @Override @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    public void handle(net.minecraft.client.player.LocalPlayer player) {
        if (player.containerMenu instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu menu)
            menu.browser().accept(this);
    }
    /** Explicit oversize result, rather than silently truncating component-rich selected facts. */
    public PatternBrowserResponsePacket bounded(net.minecraft.core.RegistryAccess registries) {
        var wire = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registries,
            net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        // Include the receiver's NBT allocation/depth limits, not only encoded byte length.
        try { write(wire); new PatternBrowserResponsePacket(wire); return this; }
        catch (IndexOutOfBoundsException | net.minecraft.nbt.NbtAccounterException tooLarge) {
            return new PatternBrowserResponsePacket(containerId, intentId,
                PatternBrowserPage.empty(PatternBrowserPage.Status.TOO_LARGE, page.fence(), page.pageIndex(), page.reindexing()), diagnostics);
        } finally { wire.release(); }
    }
    public PatternBrowserResponsePacket {
        Objects.requireNonNull(page); Objects.requireNonNull(diagnostics);
        if (containerId < 0 || intentId < 0) throw new IllegalArgumentException("negative browser response identity");
    }
    public PatternBrowserResponsePacket(RegistryFriendlyByteBuf b) { this(PatternBrowserWire.readEnvelope(b, PatternBrowserWire.MAX_RESPONSE_BYTES, PatternBrowserResponsePacket::read)); }
    private PatternBrowserResponsePacket(PatternBrowserResponsePacket value) { this(value.containerId, value.intentId, value.page, value.diagnostics); }
    public void write(RegistryFriendlyByteBuf target) {
        PatternBrowserWire.writeEnvelope(target, PatternBrowserWire.MAX_RESPONSE_BYTES, b -> {
            b.writeVarInt(containerId); b.writeVarLong(intentId); PatternBrowserWire.writePage(b, page); PatternBrowserWire.writeDiagnostics(b, diagnostics);
        });
    }
    private static PatternBrowserResponsePacket read(RegistryFriendlyByteBuf b) {
        return new PatternBrowserResponsePacket(b.readVarInt(), b.readVarLong(), PatternBrowserWire.readPage(b), PatternBrowserWire.readDiagnostics(b));
    }
}
