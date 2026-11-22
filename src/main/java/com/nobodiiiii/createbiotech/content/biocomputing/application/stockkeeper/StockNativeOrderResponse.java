package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Preview rejection only: no admission, dispatch or retry capability. */
public record StockNativeOrderResponse(int container, UUID request, String code) {
    public StockNativeOrderResponse {
        Objects.requireNonNull(request);
        if (container < 0 || code == null || code.isBlank() || code.length() > 64)
            throw new IllegalArgumentException("invalid_native_feedback");
    }
    public StockNativeOrderResponse(RegistryFriendlyByteBuf b) {
        this(b.readVarInt(), b.readUUID(), b.readUtf(64));
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(container); b.writeUUID(request); b.writeUtf(code, 64);
    }
    public boolean matches(int activeContainer, UUID activeRequest) {
        return container == activeContainer && request.equals(activeRequest);
    }
    public interface Listener { void rejectNativeOrder(StockNativeOrderResponse response); }

    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    public void handle(net.minecraft.client.player.LocalPlayer player) {
        if (player.containerMenu.containerId == container
            && net.minecraft.client.Minecraft.getInstance().screen instanceof Listener listener)
            listener.rejectNativeOrder(this);
    }
}
