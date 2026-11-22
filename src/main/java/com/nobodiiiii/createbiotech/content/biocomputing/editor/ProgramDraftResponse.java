package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import java.util.*;
import com.nobodiiiii.createbiotech.network.CBPackets;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record ProgramDraftResponse(int containerId, UUID nonce, long intent, long revision, String code, String detail, byte[] document)
        implements ClientboundPacketPayload {
    public ProgramDraftResponse {
        Objects.requireNonNull(nonce); Objects.requireNonNull(detail); document = document.clone();
        if (containerId < 0 || intent < 1 || revision < 0 || !Set.of("ready", "saved", "stale", "invalid").contains(code)
            || detail.length() > ProgramEditorResponse.MAX_DETAIL || document.length > ProgramEditorWire.MAX_BYTES
            || !code.equals("ready") && document.length != 0) throw new IllegalArgumentException("Invalid draft response");
    }
    public ProgramDraftResponse(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readUUID(), buffer.readVarLong(), buffer.readVarLong(), buffer.readUtf(32),
            buffer.readUtf(ProgramEditorResponse.MAX_DETAIL), ProgramDraftRequest.readDocument(buffer));
    }
    @Override public byte[] document() { return document.clone(); }
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId); buffer.writeUUID(nonce); buffer.writeVarLong(intent); buffer.writeVarLong(revision);
        buffer.writeUtf(code, 32); buffer.writeUtf(detail, ProgramEditorResponse.MAX_DETAIL); buffer.writeByteArray(document);
    }
    @Override public PacketTypeProvider getTypeProvider() { return CBPackets.programDraftResponseType(); }
    @Override @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    public void handle(LocalPlayer player) {
        if (player.containerMenu instanceof ComputerProgramMenu menu) menu.accept(this);
    }
}
