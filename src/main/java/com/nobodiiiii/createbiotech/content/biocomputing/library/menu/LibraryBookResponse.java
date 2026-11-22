package com.nobodiiiii.createbiotech.content.biocomputing.library.menu;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.*;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.*;

public record LibraryBookResponse(int containerId, UUID nonce, long intent, long revision, String code,
    ArchiveLimits limits, byte[] state) implements ClientboundPacketPayload {
    public LibraryBookResponse {
        Objects.requireNonNull(nonce); Objects.requireNonNull(code); Objects.requireNonNull(limits);
        state = Objects.requireNonNull(state).clone();
        if (containerId < 0 || intent < 0 || revision < 0 || code.length() > 32 || state.length > ProgramEditorWire.MAX_BYTES)
            throw new IllegalArgumentException("Invalid Library response");
    }
    public LibraryBookResponse(RegistryFriendlyByteBuf b) {
        this(b.readVarInt(), b.readUUID(), b.readVarLong(), b.readVarLong(), b.readUtf(32),
            new ArchiveLimits(b.readVarInt(), b.readVarLong(), b.readVarLong()), b.readByteArray(ProgramEditorWire.MAX_BYTES));
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(containerId); b.writeUUID(nonce); b.writeVarLong(intent); b.writeVarLong(revision); b.writeUtf(code, 32);
        b.writeVarInt(limits.maxNodes()); b.writeVarLong(limits.maxStringChars()); b.writeVarLong(limits.maxValueUnits()); b.writeByteArray(state);
    }
    @Override public byte[] state() { return state.clone(); }
    @Override public net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider getTypeProvider() { return CBPackets.libraryBookResponseType(); }
    @Override @OnlyIn(Dist.CLIENT) public void handle(LocalPlayer player) {
        if (player.containerMenu instanceof LibraryBookMenu menu) menu.accept(this);
    }
}
