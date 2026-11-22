package com.nobodiiiii.createbiotech.content.biocomputing.device.narrator;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** Only the sending connection's own opt-in changes. No selection of another player or speaker. */
public record NarratorPreferencePacket(long generation, boolean enabled) {
    public NarratorPreferencePacket {
        if (generation <= 0) throw new IllegalArgumentException("invalid_narration_generation");
    }
    public NarratorPreferencePacket(RegistryFriendlyByteBuf buffer) { this(buffer.readVarLong(), buffer.readBoolean()); }
    public void write(RegistryFriendlyByteBuf buffer) { buffer.writeVarLong(generation); buffer.writeBoolean(enabled); }
    public void handle(ServerPlayer player) { NarratorOutputAudience.preference(player, this); }
}
