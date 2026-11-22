package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.UUID;
import com.nobodiiiii.createbiotech.network.CBPackets;
import net.minecraft.server.level.ServerPlayer;

/** Opens a checked confirmation page only. It never dispatches stock or automatically confirms. */
public record StockNativeOrderRequest(int container, UUID request, byte[] order, String address) {
    public StockNativeOrderRequest {
        if (container < 0 || request == null || order == null || order.length < 1 || order.length > ProgramEditorWire.MAX_BYTES
            || address == null || address.length() > StockApplicationMenuRequest.MAX_ADDRESS) throw new IllegalArgumentException("invalid_native_order");
        order = order.clone();
    }
    @Override public byte[] order() { return order.clone(); }
    public static StockNativeOrderRequest create(int container, PackageOrderWithCrafts order, String address, HolderLookup.Provider registries) {
        var limits = new StockApplication.Limits(StockApplicationValues.MAX_MEMBERS,0,StockApplicationValues.FORMAT.maxValueUnits(),
            StockApplicationValues.MAX_TEXT,StockApplicationMenuRequest.MAX_ADDRESS);
        var value = StockNativeOrder.capture(order,registries,limits);
        return new StockNativeOrderRequest(container,UUID.randomUUID(),ProgramEditorWire.encodeValue(value,StockApplicationMenu.VIEW_LIMITS),address);
    }
    public StockNativeOrderRequest(RegistryFriendlyByteBuf b) {
        this(b.readVarInt(),b.readUUID(),b.readByteArray(ProgramEditorWire.MAX_BYTES),b.readUtf(StockApplicationMenuRequest.MAX_ADDRESS));
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(container); b.writeUUID(request); b.writeByteArray(order); b.writeUtf(address,StockApplicationMenuRequest.MAX_ADDRESS);
    }
    public void handle(ServerPlayer actor) {
        if (actor.containerMenu.containerId != container) return;
        try {
            var value = ProgramEditorWire.decodeValue(order,StockApplicationMenu.VIEW_LIMITS);
            if (!(value instanceof DataValue.NbtValue nbt)) throw new IllegalArgumentException("invalid_native_order");
            var code = StockApplicationMenu.openNativeOrder(actor,nbt,address);
            if (!code.equals("ok")) CBPackets.sendToPlayer(new StockNativeOrderResponse(container,request,code),actor);
        } catch (IllegalArgumentException failure) {
            CBPackets.sendToPlayer(new StockNativeOrderResponse(container,request,RetiredStockOrders.rejectionCode(failure)),actor);
        }
    }
}
