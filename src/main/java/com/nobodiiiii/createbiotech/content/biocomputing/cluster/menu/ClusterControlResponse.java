package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.*;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

public record ClusterControlResponse(int containerId, UUID nonce, long intent, long revision,
    String code, String detail, ArchiveLimits archiveLimits, byte[] state) implements ClientboundPacketPayload {
    public static final int MAX_CODE = 32, MAX_DETAIL = 512;
    public ClusterControlResponse {
        nonce = Objects.requireNonNull(nonce); code = Objects.requireNonNull(code); detail = Objects.requireNonNull(detail);
        archiveLimits = Objects.requireNonNull(archiveLimits); state = Objects.requireNonNull(state).clone();
        if (containerId < 0 || intent < 0 || revision < 0 || !validCode(code)
            || detail.length() > MAX_DETAIL || state.length > ProgramEditorWire.MAX_BYTES)
            throw new IllegalArgumentException("invalid cluster control response");
    }
    public ClusterControlResponse(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readUUID(), buffer.readVarLong(), buffer.readVarLong(), buffer.readUtf(MAX_CODE),
            buffer.readUtf(MAX_DETAIL), new ArchiveLimits(buffer.readVarInt(), buffer.readVarLong(), buffer.readVarLong()), bytes(buffer));
    }
    private static byte[] bytes(RegistryFriendlyByteBuf buffer) {
        int length = buffer.readVarInt();
        if (length < 0 || length > ProgramEditorWire.MAX_BYTES) throw new IllegalArgumentException("cluster control state too large");
        byte[] state = new byte[length]; buffer.readBytes(state); return state;
    }
    @Override public byte[] state() { return state.clone(); }
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId); buffer.writeUUID(nonce); buffer.writeVarLong(intent); buffer.writeVarLong(revision);
        buffer.writeUtf(code, MAX_CODE); buffer.writeUtf(detail, MAX_DETAIL);
        buffer.writeVarInt(archiveLimits.maxNodes()); buffer.writeVarLong(archiveLimits.maxStringChars()); buffer.writeVarLong(archiveLimits.maxValueUnits());
        buffer.writeVarInt(state.length); buffer.writeBytes(state);
    }
    @Override public PacketTypeProvider getTypeProvider() { return CBPackets.clusterControlResponseType(); }
    @Override @OnlyIn(Dist.CLIENT) public void handle(LocalPlayer player) {
        if (player.containerMenu instanceof ClusterControllerMenu menu) menu.accept(this);
    }
    private static boolean validCode(String code) {
        if (code.isEmpty() || code.length() > MAX_CODE) return false;
        for (int index = 0; index < code.length(); index++) {
            char c = code.charAt(index);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_') return false;
        }
        return true;
    }
}
