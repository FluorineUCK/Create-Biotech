package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import static com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaLimits.*;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;


/** Read-only adapter for older world archives. It never creates or edits that archive. */
final class SpeakerMediaLibrary extends SavedData {
    private static final String KEY = "create_biotech_speaker_media";
    private static final Factory<SpeakerMediaLibrary> FACTORY = new Factory<>(SpeakerMediaLibrary::new, SpeakerMediaLibrary::load);
    record Track(UUID id, String name, SpeakerMediaFormat format, int size) {}
    private record Entry(Track track, UUID uploader, byte[] bytes) {}
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private int bytes;

    static Optional<SpeakerMediaLibrary> get(MinecraftServer server) {
        return Optional.ofNullable(server.overworld().getDataStorage().get(FACTORY, KEY));
    }

    List<Track> catalog() { return entries.values().stream().map(Entry::track).toList(); }
    Optional<byte[]> media(UUID id) {
        var entry = entries.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.bytes().clone());
    }

    private void store(UUID id, UUID actor, String name, SpeakerMediaFormat format, byte[] media) {
        entries.put(id, new Entry(new Track(id, name, format, media.length), actor, media));
        bytes += media.length;
    }

    static SpeakerMediaLibrary load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("version") != 1 || !tag.contains("tracks", Tag.TAG_LIST)) throw new IllegalArgumentException("invalid speaker media library");
        var result = new SpeakerMediaLibrary();
        var tracks = (ListTag)tag.get("tracks");
        if ((!tracks.isEmpty() && tracks.getElementType() != Tag.TAG_COMPOUND) || tracks.size() > HARD_TRACKS)
            throw new IllegalArgumentException("invalid speaker media track list");
        for (var raw : tracks) {
            var entry = (CompoundTag)raw;
            var name = entry.getString("name");
            var format = SpeakerMediaFormat.fromName(name);
            var media = entry.getByteArray("bytes");
            var id = entry.getUUID("id");
            if (name.length() > SpeakerMediaChunk.MAX_NAME || format == null || media.length == 0 || media.length > HARD_TRACK_BYTES
                || media.length > HARD_WORLD_BYTES - result.bytes || result.entries.containsKey(id))
                throw new IllegalArgumentException("invalid speaker media track");
            result.store(id, entry.getUUID("uploader"), name, format, media);
        }
        return result;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var tracks = new ListTag();
        for (var entry : entries.values()) {
            var track = new CompoundTag();
            track.putUUID("id", entry.track().id());
            track.putUUID("uploader", entry.uploader());
            track.putString("name", entry.track().name());
            track.putByteArray("bytes", entry.bytes().clone());
            tracks.add(track);
        }
        tag.putInt("version", 1);
        tag.put("tracks", tracks);
        return tag;
    }
}
