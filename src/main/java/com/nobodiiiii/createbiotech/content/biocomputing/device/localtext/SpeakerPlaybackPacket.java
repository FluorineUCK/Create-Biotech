package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Bounded game-audio delivery; IN-WORLD serial frames contain only the reference and controls. */
public record SpeakerPlaybackPacket(ResourceLocation dimension, BlockPos speaker, UUID id, long preference,
        Action action, SpeakerMediaFormat format, int value, int range, byte[] bytes) {
    enum Action { BEGIN, DATA, END, STOP }
    static final int MAX_RANGE = 64;
    private static final byte[] EMPTY = new byte[0];

    public SpeakerPlaybackPacket {
        if (preference < 1 || !switch (action) {
            case BEGIN -> format != null && value >= 1 && value <= SpeakerMediaLimits.HARD_TRACK_BYTES && range >= 1 && range <= MAX_RANGE && bytes.length == 0;
            case DATA -> format == null && value >= 0 && range == 0 && bytes.length >= 1 && bytes.length <= SpeakerMediaChunk.CHUNK_BYTES
                && value <= SpeakerMediaLimits.HARD_TRACK_BYTES - bytes.length;
            case END, STOP -> format == null && value == 0 && range == 0 && bytes.length == 0;
        }) throw new IllegalArgumentException("invalid_speaker_delivery");
    }

    public SpeakerPlaybackPacket(RegistryFriendlyByteBuf in) {
        this(in.readResourceLocation(), in.readBlockPos(), in.readUUID(), in.readVarLong(), in.readEnum(Action.class),
            in.readBoolean() ? in.readEnum(SpeakerMediaFormat.class) : null, in.readVarInt(), in.readVarInt(),
            in.readByteArray(SpeakerMediaChunk.CHUNK_BYTES));
    }

    public void write(RegistryFriendlyByteBuf out) {
        out.writeResourceLocation(dimension); out.writeBlockPos(speaker); out.writeUUID(id); out.writeVarLong(preference);
        out.writeEnum(action); out.writeBoolean(format != null);
        if (format != null) out.writeEnum(format);
        out.writeVarInt(value); out.writeVarInt(range); out.writeByteArray(bytes);
    }

    static SpeakerPlaybackPacket begin(ResourceLocation dim, BlockPos pos, UUID id, long preference, SpeakerMediaFormat format, int value, int range) {
        return new SpeakerPlaybackPacket(dim, pos, id, preference, Action.BEGIN, format, value, range, EMPTY);
    }

    static SpeakerPlaybackPacket control(ResourceLocation dim, BlockPos pos, UUID id, long preference, Action action) {
        return new SpeakerPlaybackPacket(dim, pos, id, preference, action, null, 0, 0, EMPTY);
    }
}
