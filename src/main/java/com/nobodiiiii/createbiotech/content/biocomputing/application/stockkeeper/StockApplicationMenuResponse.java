package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;

public record StockApplicationMenuResponse(int container, UUID previous, UUID next, String code, byte[] bytes) {
    public StockApplicationMenuResponse(RegistryFriendlyByteBuf b) {
        this(b.readVarInt(), b.readUUID(), b.readUUID(), b.readUtf(64), b.readByteArray(ProgramEditorWire.MAX_BYTES));
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(container); b.writeUUID(previous); b.writeUUID(next); b.writeUtf(code, 64); b.writeByteArray(bytes);
    }
    DataValue.RecordValue view() { return (DataValue.RecordValue)ProgramEditorWire.decodeValue(bytes, StockApplicationMenu.VIEW_LIMITS); }
    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    public void handle(net.minecraft.client.player.LocalPlayer player) {
        if (player.containerMenu instanceof StockApplicationMenu menu) menu.accept(this);
    }
}
