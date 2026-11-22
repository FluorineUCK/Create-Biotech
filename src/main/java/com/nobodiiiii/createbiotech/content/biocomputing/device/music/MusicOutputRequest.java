package com.nobodiiiii.createbiotech.content.biocomputing.device.music;

import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** Only a captured menu choice; no target position, caller UUID, note or instrument or generic write in this packet. */
public record MusicOutputRequest(int containerId, UUID nonce, long revision, Action action, int choice) {
    public enum Action { REFRESH, GRANT, REVOKE }
    public MusicOutputRequest {
        Objects.requireNonNull(nonce); Objects.requireNonNull(action);
        if (containerId < 0 || revision < 0 || (action == Action.GRANT ? choice < 0 || choice >= 128 : choice != -1))
            throw new IllegalArgumentException("invalid_output_request");
    }
    public MusicOutputRequest(RegistryFriendlyByteBuf b) { this(b.readVarInt(), b.readUUID(), b.readVarLong(), b.readEnum(Action.class), b.readVarInt()); }
    public void write(RegistryFriendlyByteBuf b) { b.writeVarInt(containerId); b.writeUUID(nonce); b.writeVarLong(revision); b.writeEnum(action); b.writeVarInt(choice); }
    public boolean matches(int id, UUID session, long expected) { return containerId == id && nonce.equals(session) && revision == expected; }
    public void handle(ServerPlayer player) { if (player.containerMenu instanceof MusicOutputMenu menu) menu.handle(player, this); }
}
