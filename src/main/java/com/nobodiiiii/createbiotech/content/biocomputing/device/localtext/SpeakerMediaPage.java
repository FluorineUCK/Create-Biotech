package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Metadata only, never carries imported bytes or local file paths. */
public record SpeakerMediaPage(int containerId, UUID nonce, long intent, String code, int page, int lastPage, List<Track> tracks) {
    static final int PAGE_SIZE = 12;
    public record Track(UUID id, String name, String format, int size) {
        Track(RegistryFriendlyByteBuf b) {
            this(b.readUUID(), b.readUtf(SpeakerMediaChunk.MAX_NAME), b.readUtf(4), b.readVarInt());
        }
        void write(RegistryFriendlyByteBuf b) {
            b.writeUUID(id); b.writeUtf(name, SpeakerMediaChunk.MAX_NAME); b.writeUtf(format, 4); b.writeVarInt(size);
        }
    }
    public SpeakerMediaPage { tracks = List.copyOf(tracks); }
    public SpeakerMediaPage(RegistryFriendlyByteBuf b) {
        this(b.readVarInt(), b.readUUID(), b.readVarLong(), b.readUtf(32), b.readVarInt(), b.readVarInt(), readTracks(b));
    }
    private static List<Track> readTracks(RegistryFriendlyByteBuf b) {
        int count = b.readVarInt();
        if (count < 0 || count > PAGE_SIZE) throw new io.netty.handler.codec.DecoderException("Invalid media catalog size");
        var tracks = new java.util.ArrayList<Track>(count);
        for (int i = 0; i < count; i++) tracks.add(new Track(b));
        return tracks;
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(containerId); b.writeUUID(nonce); b.writeVarLong(intent); b.writeUtf(code, 32);
        b.writeVarInt(page); b.writeVarInt(lastPage); b.writeVarInt(tracks.size());
        for (var track : tracks) track.write(b);
    }
}
