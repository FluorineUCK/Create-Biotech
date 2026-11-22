package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** A per-screen visibility hint. Never authorizes configuration, takeover or dispatch. */
public record StockControls(int container, UUID nonce, boolean application, boolean route) {
    public StockControls(RegistryFriendlyByteBuf b) {
        this(b.readVarInt(), b.readUUID(), b.readBoolean(), b.readBoolean());
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(container); b.writeUUID(nonce); b.writeBoolean(application); b.writeBoolean(route);
    }
    public interface Listener { void showStockControls(StockControls state); }
}
