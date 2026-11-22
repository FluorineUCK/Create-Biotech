package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** Indices refer only to this menu's server-owned source/component catalog, not arbitrary world positions. */
public record ReaderRequest(int containerId, UUID nonce, long revision, Action action, int choice, List<Integer> components) {
    public static final int MAX_CHOICES = 128;
    public enum Action { REFRESH, GRANT, REVOKE }
    public ReaderRequest {
        Objects.requireNonNull(nonce); Objects.requireNonNull(action); components = List.copyOf(components);
        if (containerId < 0 || revision < 0 || components.size() > MAX_CHOICES || new HashSet<>(components).size() != components.size()
            || components.stream().anyMatch(i -> i < 0 || i >= MAX_CHOICES)
            || (action == Action.GRANT ? choice < 0 || choice >= MAX_CHOICES || components.isEmpty() : choice != -1 || !components.isEmpty()))
            throw new IllegalArgumentException("invalid_reader_request");
    }
    public ReaderRequest(RegistryFriendlyByteBuf b) { this(b.readVarInt(), b.readUUID(), b.readVarLong(), b.readEnum(Action.class), b.readVarInt(), readIndices(b)); }
    private static List<Integer> readIndices(RegistryFriendlyByteBuf b) {
        int size = b.readVarInt(); if (size < 0 || size > MAX_CHOICES) throw new IllegalArgumentException("invalid_reader_request");
        var result = new ArrayList<Integer>(); for (int i = 0; i < size; i++) result.add(b.readVarInt()); return result;
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(containerId); b.writeUUID(nonce); b.writeVarLong(revision); b.writeEnum(action); b.writeVarInt(choice);
        b.writeVarInt(components.size()); components.forEach(b::writeVarInt);
    }
    public boolean matches(int id, UUID session, long expected) { return containerId == id && nonce.equals(session) && revision == expected; }
    public void handle(ServerPlayer player) { if (player.containerMenu instanceof ReaderMenu menu) menu.handle(player, this); }
}
