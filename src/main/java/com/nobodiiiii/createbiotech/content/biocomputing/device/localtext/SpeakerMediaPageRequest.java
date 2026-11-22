package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramMenu;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public record SpeakerMediaPageRequest(int containerId, UUID nonce, long intent, int page) {
    public SpeakerMediaPageRequest(RegistryFriendlyByteBuf b) {
        this(b.readVarInt(), b.readUUID(), b.readVarLong(), b.readVarInt());
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(containerId); b.writeUUID(nonce); b.writeVarLong(intent); b.writeVarInt(page);
    }
    public void handle(ServerPlayer player) {
        if (player.containerMenu instanceof ComputerProgramMenu menu) menu.handle(player, this);
    }
}
