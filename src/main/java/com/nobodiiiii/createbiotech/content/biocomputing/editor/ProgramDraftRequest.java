package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import java.util.*;
import com.nobodiiiii.createbiotech.network.CBPackets;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** Explicit editor-only load/save, not a program device operation or installation. */
public record ProgramDraftRequest(int containerId, UUID nonce, long intent, long revision, boolean save, byte[] document)
        implements ServerboundPacketPayload {
    public ProgramDraftRequest {
        Objects.requireNonNull(nonce); document = document.clone();
        if (containerId < 0 || intent < 1 || revision < 0 || document.length > ProgramEditorWire.MAX_BYTES
            || save == (document.length == 0)) throw new IllegalArgumentException("Invalid draft request");
    }
    public ProgramDraftRequest(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readUUID(), buffer.readVarLong(), buffer.readVarLong(), buffer.readBoolean(), readDocument(buffer));
    }
    static byte[] readDocument(RegistryFriendlyByteBuf buffer) { return buffer.readByteArray(ProgramEditorWire.MAX_BYTES); }
    @Override public byte[] document() { return document.clone(); }
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId); buffer.writeUUID(nonce); buffer.writeVarLong(intent); buffer.writeVarLong(revision);
        buffer.writeBoolean(save); buffer.writeByteArray(document);
    }
    @Override public PacketTypeProvider getTypeProvider() { return CBPackets.programDraftRequestType(); }
    @Override public void handle(ServerPlayer player) {
        if (player.containerMenu instanceof ComputerProgramMenu menu) menu.handle(player, this);
    }
}
