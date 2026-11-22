package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.Objects;
import java.util.UUID;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public record ClusterControlRequest(int containerId, UUID nonce, long intent, long revision,
    ClusterControlSession.Action action, byte[] value) implements ServerboundPacketPayload {
    public ClusterControlRequest {
        nonce = Objects.requireNonNull(nonce); action = Objects.requireNonNull(action);
        value = Objects.requireNonNull(value).clone();
        if (containerId < 0 || intent < 0 || revision < 0 || value.length > ProgramEditorWire.MAX_BYTES
            || !action.requiresValue() && value.length != 0
            || action.requiresValue() && value.length == 0)
            throw new IllegalArgumentException("invalid cluster control request");
    }
    public ClusterControlRequest(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readUUID(), buffer.readVarLong(), buffer.readVarLong(), action(buffer.readVarInt()), bytes(buffer));
    }
    private static ClusterControlSession.Action action(int ordinal) {
        var values = ClusterControlSession.Action.values();
        if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("invalid cluster control action");
        return values[ordinal];
    }
    private static byte[] bytes(RegistryFriendlyByteBuf buffer) {
        int length = buffer.readVarInt();
        if (length < 0 || length > ProgramEditorWire.MAX_BYTES) throw new IllegalArgumentException("cluster control value too large");
        byte[] value = new byte[length]; buffer.readBytes(value); return value;
    }
    @Override public byte[] value() { return value.clone(); }
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId); buffer.writeUUID(nonce); buffer.writeVarLong(intent); buffer.writeVarLong(revision);
        buffer.writeVarInt(action.ordinal()); buffer.writeVarInt(value.length); buffer.writeBytes(value);
    }
    @Override public PacketTypeProvider getTypeProvider() { return CBPackets.clusterControlRequestType(); }
    @Override public void handle(ServerPlayer player) {
        if (player.containerMenu instanceof ClusterControllerMenu menu) menu.handle(player, this);
    }
}
