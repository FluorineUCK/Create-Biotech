package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramMenu;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public record SpeakerMediaStoreRequest(int containerId, UUID nonce, long intent, UUID file, String port,
    BlockPos library, BlockPos track, int slot, String volume, String entry) {
    public SpeakerMediaStoreRequest(RegistryFriendlyByteBuf b) {
        this(b.readVarInt(), b.readUUID(), b.readVarLong(), b.readUUID(), b.readUtf(32), b.readBlockPos(), b.readBlockPos(),
            b.readVarInt(), b.readUtf(128), b.readUtf(128));
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(containerId); b.writeUUID(nonce); b.writeVarLong(intent); b.writeUUID(file); b.writeUtf(port, 32);
        b.writeBlockPos(library); b.writeBlockPos(track); b.writeVarInt(slot); b.writeUtf(volume, 128); b.writeUtf(entry, 128);
    }
    public void handle(ServerPlayer player) {
        if (player.containerMenu instanceof ComputerProgramMenu menu) menu.handle(player, this);
    }
}
