package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** Only a server-captured menu choice. It never accepts a target or rule from the client. */
public record VirtualFilterRequest(int containerId, UUID nonce, Action action, int choice) {
    static final int MAX_CHOICES = 128;
    enum Action { REFRESH, GRANT, REVOKE, GRANT_LIST }
    public VirtualFilterRequest {
        Objects.requireNonNull(nonce); Objects.requireNonNull(action);
        if (containerId < 0 || (action == Action.GRANT || action == Action.GRANT_LIST ? choice < 0 || choice >= MAX_CHOICES : choice != -1))
            throw new IllegalArgumentException("invalid_filter_request");
    }
    public VirtualFilterRequest(RegistryFriendlyByteBuf b) { this(b.readVarInt(), b.readUUID(), b.readEnum(Action.class), b.readVarInt()); }
    public void write(RegistryFriendlyByteBuf b) { b.writeVarInt(containerId); b.writeUUID(nonce); b.writeEnum(action); b.writeVarInt(choice); }
    public void handle(ServerPlayer actor) { if (actor.containerMenu instanceof VirtualFilterMenu menu) menu.handle(actor, this); }
}
