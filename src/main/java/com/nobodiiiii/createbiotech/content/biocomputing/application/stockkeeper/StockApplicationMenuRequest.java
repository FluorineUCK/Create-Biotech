package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public record StockApplicationMenuRequest(int container, UUID nonce, Action action, int choice, String key, long quantity, String address) {
    public enum Action { OPEN, REFRESH, BACK, BIND, REVOKE, DISCARD, CONFIRM, GRANT_MATERIALS, REVOKE_MATERIALS, CONFIRM_NATIVE, QUERY_CONTROLS }
    static final int MAX_ADDRESS = 128, MAX_KEY = 512;
    public StockApplicationMenuRequest {
        Objects.requireNonNull(nonce); Objects.requireNonNull(action); Objects.requireNonNull(key); Objects.requireNonNull(address);
        if (container < 0 || choice < -1 || key.length() > MAX_KEY || address.length() > MAX_ADDRESS)
            throw new IllegalArgumentException("invalid_request");
    }
    public static StockApplicationMenuRequest open(int id) {
        return new StockApplicationMenuRequest(id, new UUID(0, 0), Action.OPEN, -1, "", 0, "");
    }
    public static StockApplicationMenuRequest controls(int id, UUID nonce) {
        return new StockApplicationMenuRequest(id, nonce, Action.QUERY_CONTROLS, -1, "", 0, "");
    }
    public StockApplicationMenuRequest(RegistryFriendlyByteBuf b) {
        this(b.readVarInt(), b.readUUID(), b.readEnum(Action.class), b.readVarInt(), b.readUtf(MAX_KEY), b.readVarLong(), b.readUtf(MAX_ADDRESS));
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(container); b.writeUUID(nonce); b.writeEnum(action); b.writeVarInt(choice);
        b.writeUtf(key, MAX_KEY); b.writeVarLong(quantity); b.writeUtf(address, MAX_ADDRESS);
    }
    public void handle(ServerPlayer actor) {
        if (actor.containerMenu.containerId != container) return;
        if (action == Action.QUERY_CONTROLS) {
            var result = StockApplicationMenu.controls(actor, nonce);
            com.nobodiiiii.createbiotech.network.CBPackets.sendToPlayer(result, actor);
        } else if (action == Action.OPEN) StockApplicationMenu.openFromNative(actor);
        else if (actor.containerMenu instanceof StockApplicationMenu menu) menu.handle(actor, this);
    }
}
