package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import java.util.Objects;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramMenu;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** Manual player import, not a device/serial command. Bound to an exact live menu. */
public record SpeakerMediaChunk(int containerId, UUID nonce, long intent, String name, int total, int offset, byte[] bytes) {
    public static final int CHUNK_BYTES = 24 * 1024;
    public static final int MAX_NAME = 120;

    public SpeakerMediaChunk {
        Objects.requireNonNull(nonce);
        Objects.requireNonNull(name);
        Objects.requireNonNull(bytes);
    }
    public SpeakerMediaChunk(RegistryFriendlyByteBuf b) {
        this(b.readVarInt(), b.readUUID(), b.readVarLong(), b.readUtf(MAX_NAME), b.readVarInt(), b.readVarInt(), b.readByteArray(CHUNK_BYTES));
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(containerId); b.writeUUID(nonce); b.writeVarLong(intent); b.writeUtf(name, MAX_NAME);
        b.writeVarInt(total); b.writeVarInt(offset); b.writeByteArray(bytes);
    }
    public void handle(ServerPlayer player) {
        if (player.containerMenu instanceof ComputerProgramMenu menu) menu.handle(player, this);
    }
}
