package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.BrowserFence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternItemCodecs;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

public record PatternBrowserRequestPacket(int containerId, BrowserFence fence, long intentId,
    PatternBrowserFilter filter, int pageIndex, Optional<UUID> selection) implements net.createmod.catnip.net.base.ServerboundPacketPayload {
    @Override public net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider getTypeProvider() {
        return com.nobodiiiii.createbiotech.network.CBPackets.patternBrowserRequestType();
    }
    @Override public void handle(net.minecraft.server.level.ServerPlayer player) {
        if (player.containerMenu instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu menu)
            menu.enqueue(player, this);
    }
    public PatternBrowserRequestPacket {
        Objects.requireNonNull(fence); Objects.requireNonNull(filter); Objects.requireNonNull(selection);
        if (containerId < 0 || intentId < 0) throw new IllegalArgumentException("negative browser request identity");
    }
    public PatternBrowserRequestPacket(RegistryFriendlyByteBuf b) { this(PatternBrowserWire.readEnvelope(b, PatternBrowserWire.MAX_REQUEST_BYTES, PatternBrowserRequestPacket::read)); }
    private PatternBrowserRequestPacket(PatternBrowserRequestPacket value) { this(value.containerId, value.fence, value.intentId, value.filter, value.pageIndex, value.selection); }
    /** UI transport boundary: validation failure must never invoke the network sender. */
    public boolean sendIfBounded(net.minecraft.core.RegistryAccess registries, java.util.function.Consumer<PatternBrowserRequestPacket> sender) {
        var wire = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registries,
            net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        // Wire bytes alone omit NBT heap accounting and nesting depth. Use the real receiver.
        try { write(wire); new PatternBrowserRequestPacket(wire); }
        catch (IllegalArgumentException | IndexOutOfBoundsException | net.minecraft.nbt.NbtAccounterException tooLarge) { return false; }
        finally { wire.release(); }
        sender.accept(this);
        return true;
    }
    public void write(RegistryFriendlyByteBuf target) {
        PatternBrowserWire.writeEnvelope(target, PatternBrowserWire.MAX_REQUEST_BYTES, b -> {
            b.writeVarInt(containerId); PatternBrowserWire.writeFence(b, fence); b.writeVarLong(intentId);
            b.writeUtf(filter.search(), PatternBrowserFilter.MAX_SEARCH); b.writeBoolean(filter.exactItem().isPresent());
            filter.exactItem().ifPresent(key -> b.writeNbt(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternItemCodecs.saveStackKey(key, b.registryAccess())));
            b.writeBoolean(filter.kind().isPresent()); filter.kind().ifPresent(b::writeEnum);
            long[] names = filter.localizedItems().toLongArray();
            b.writeVarInt(names.length); for (long word : names) b.writeLong(word);
            b.writeInt(pageIndex); b.writeBoolean(selection.isPresent()); selection.ifPresent(b::writeUUID);
        });
    }
    private static PatternBrowserRequestPacket read(RegistryFriendlyByteBuf b) {
        int container = b.readVarInt(); var fence = PatternBrowserWire.readFence(b); long intent = b.readVarLong();
        String search = b.readUtf(PatternBrowserFilter.MAX_SEARCH);
        Optional<StackKey> exact = Optional.empty();
        if (b.readBoolean()) {
            var nbt = b.readNbt(net.minecraft.nbt.NbtAccounter.create(PatternBrowserWire.MAX_REQUEST_BYTES));
            if (!(nbt instanceof net.minecraft.nbt.CompoundTag compound)) throw new IllegalArgumentException("missing ghost stack" );
            exact = Optional.of(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternItemCodecs.loadStackKey(compound, b.registryAccess()).orElseThrow(() -> new IllegalArgumentException("invalid ghost stack")));
        }
        Optional<PatternKind> kind = b.readBoolean() ? Optional.of(b.readEnum(PatternKind.class)) : Optional.empty();
        int words = b.readVarInt();
        if (words < 0 || words > PatternBrowserWire.MAX_REQUEST_BYTES / Long.BYTES || (long) words * Long.BYTES > b.readableBytes())
            throw new IllegalArgumentException("Invalid localized item filter");
        long[] names = new long[words]; for (int i = 0; i < words; i++) names[i] = b.readLong();
        int page = b.readInt(); Optional<UUID> selected = b.readBoolean() ? Optional.of(b.readUUID()) : Optional.empty();
        return new PatternBrowserRequestPacket(container, fence, intent, new PatternBrowserFilter(search, exact, kind, java.util.BitSet.valueOf(names)), page, selected);
    }
}
