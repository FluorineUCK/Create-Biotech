package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** A captured source index and an address draft, never a client-supplied network or world target. */
public record ProductionPortRequest(int containerId, UUID nonce, long revision, Action action, int choice, String address) {
    public static final int MAX_CHOICES = 128;
    public static final int MAX_ADDRESS_LENGTH = 25;
    public enum Action { REFRESH, GRANT, REVOKE }

    public ProductionPortRequest {
        Objects.requireNonNull(nonce);
        Objects.requireNonNull(action);
        Objects.requireNonNull(address);
        if (containerId < 0 || revision < 0
                || (action == Action.GRANT ? choice < 0 || choice >= MAX_CHOICES : choice != -1)
                || !validDraft(address)
                || action == Action.GRANT && address.isBlank()
                || action == Action.REVOKE && !address.isEmpty())
            throw new IllegalArgumentException("invalid_port_request");
    }

    /** Same printable input boundary for the editor and network decoder; normalization remains server-owned. */
    public static boolean validDraft(String address) {
        if (address == null || address.length() > MAX_ADDRESS_LENGTH) return false;
        return address.codePoints().noneMatch(c -> Character.isISOControl(c) || c == 0x00a7
            || c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE);
    }

    public ProductionPortRequest(RegistryFriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readUUID(), buffer.readVarLong(), buffer.readEnum(Action.class),
            buffer.readVarInt(), buffer.readUtf(MAX_ADDRESS_LENGTH));
    }
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeUUID(nonce);
        buffer.writeVarLong(revision);
        buffer.writeEnum(action);
        buffer.writeVarInt(choice);
        buffer.writeUtf(address, MAX_ADDRESS_LENGTH);
    }
    public boolean matches(int id, UUID session, long expected) {
        return containerId == id && nonce.equals(session) && revision == expected;
    }
    public void handle(ServerPlayer player) {
        if (player.containerMenu instanceof ProductionPortMenu menu) menu.handle(player, this);
    }
}
