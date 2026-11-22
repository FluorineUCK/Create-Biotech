package com.nobodiiiii.createbiotech.content.biocomputing.library.menu;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryEntry;
import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.*;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public record LibraryBookRequest(int containerId, UUID nonce, long intent, long revision, Action action,
    int book, String name, LibraryEntry.Kind kind, byte[] value) implements ServerboundPacketPayload {
    public static final int MAX_NAME = com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookVolumes.MAX_NAME;
    public enum Action { REFRESH, SELECT, READ, PUT, DELETE, RENAME_VOLUME, CONFIGURE_SERVICE, DISCOVER_CONTROL, CONFIGURE_CONTROL, CONFIGURE_SERIAL }
    public LibraryBookRequest {
        Objects.requireNonNull(nonce); Objects.requireNonNull(action); Objects.requireNonNull(kind);
        Objects.requireNonNull(name); value = Objects.requireNonNull(value).clone();
        if (containerId < 0 || intent < 0 || revision < 0 || book < -1 || name.length() > MAX_NAME || value.length > ProgramEditorWire.MAX_BYTES)
            throw new IllegalArgumentException("Invalid Library request");
    }
    public LibraryBookRequest(RegistryFriendlyByteBuf b) {
        this(b.readVarInt(), b.readUUID(), b.readVarLong(), b.readVarLong(), b.readEnum(Action.class),
            b.readVarInt(), b.readUtf(MAX_NAME), b.readEnum(LibraryEntry.Kind.class), b.readByteArray(ProgramEditorWire.MAX_BYTES));
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(containerId); b.writeUUID(nonce); b.writeVarLong(intent); b.writeVarLong(revision);
        b.writeEnum(action); b.writeVarInt(book); b.writeUtf(name, MAX_NAME); b.writeEnum(kind); b.writeByteArray(value);
    }
    @Override public byte[] value() { return value.clone(); }
    @Override public net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider getTypeProvider() { return CBPackets.libraryBookRequestType(); }
    @Override public void handle(ServerPlayer player) {
        if (player.containerMenu instanceof LibraryBookMenu menu) menu.handle(player, this);
    }
}
