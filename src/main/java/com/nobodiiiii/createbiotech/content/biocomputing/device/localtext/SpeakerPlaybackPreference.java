package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public record SpeakerPlaybackPreference(long generation, boolean enabled) {
    public SpeakerPlaybackPreference {
        if (generation < 1) throw new IllegalArgumentException("invalid_speaker_preference");
    }

    public SpeakerPlaybackPreference(RegistryFriendlyByteBuf in) { this(in.readVarLong(), in.readBoolean()); }
    public void write(RegistryFriendlyByteBuf out) { out.writeVarLong(generation); out.writeBoolean(enabled); }
    public void handle(ServerPlayer player) { SpeakerPlaybackServer.preference(player, this); }
}
