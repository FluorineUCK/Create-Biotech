package com.nobodiiiii.createbiotech.content.biocomputing.library.inventory;

import io.netty.buffer.Unpooled;
import java.util.Objects;
import com.nobodiiiii.createbiotech.network.CBPackets;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** Manual creative inventory intent. Private service data is never an input to the operation. */
public record LibraryCreativeInventoryRequest(Action action, int slot, int button, ClickType click, byte[] cursor) implements ServerboundPacketPayload {
    /** Protocol envelope, below the serverbound custom-payload ceiling; not a gameplay capacity. */
    public static final int MAX_BYTES = 24 * 1024;
    public enum Action { CLICK, SET_CURSOR, REPLACE_SLOT, CLEAR_ALL, HOTBAR_PICK_ALL }
    public LibraryCreativeInventoryRequest {
        Objects.requireNonNull(action); Objects.requireNonNull(click); cursor = Objects.requireNonNull(cursor).clone();
        if (cursor.length == 0 || cursor.length > MAX_BYTES) throw new IllegalArgumentException("Invalid inventory projection size");
    }
    public LibraryCreativeInventoryRequest(RegistryFriendlyByteBuf buffer) {
        this(buffer.readEnum(Action.class), buffer.readVarInt(), buffer.readVarInt(), buffer.readEnum(ClickType.class), buffer.readByteArray(MAX_BYTES));
    }
    @Override public byte[] cursor() { return cursor.clone(); }
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeEnum(action); buffer.writeVarInt(slot); buffer.writeVarInt(button); buffer.writeEnum(click); buffer.writeByteArray(cursor);
    }
    public static LibraryCreativeInventoryRequest of(Action action, int slot, int button, ClickType click, ItemStack cursor, RegistryAccess registries) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(256, MAX_BYTES), registries);
        try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, cursor);
            byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes);
            return new LibraryCreativeInventoryRequest(action, slot, button, click, bytes);
        } finally { buffer.release(); }
    }
    public ItemStack decodeCursor(RegistryAccess registries) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(cursor), registries);
        try {
            var stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            if (buffer.isReadable() || !stack.isEmpty() && stack.getCount() > stack.getMaxStackSize())
                throw new IllegalArgumentException("Invalid inventory projection");
            return stack;
        } finally { buffer.release(); }
    }
    @Override public PacketTypeProvider getTypeProvider() { return CBPackets.libraryCreativeInventoryRequestType(); }
    @Override public void handle(ServerPlayer player) {
        if (!LibraryCreativeInventory.apply(player, this)) LibraryCreativeItems.reportRejected(player);
    }
}
