package com.nobodiiiii.createbiotech.content.biocomputing.device.narrator;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Small bounded delivery. The generation fences messages overtaken by local mute/world change. */
public record NarratorSpeechPacket(ResourceLocation dimension, long generation, String text) {
    public NarratorSpeechPacket {
        Objects.requireNonNull(dimension);
        if (generation <= 0) throw new IllegalArgumentException("invalid_narration_generation");
        NarratorOutputText.checkedBytes(text, NarratorOutputText.FORMAT_MAX_BYTES);
    }
    public NarratorSpeechPacket(RegistryFriendlyByteBuf buffer) {
        this(buffer.readResourceLocation(), buffer.readVarLong(), buffer.readUtf(NarratorOutputText.FORMAT_MAX_BYTES));
    }
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeResourceLocation(dimension);
        buffer.writeVarLong(generation);
        buffer.writeUtf(text, NarratorOutputText.FORMAT_MAX_BYTES);
    }
}
