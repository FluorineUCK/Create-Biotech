package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import java.util.HexFormat;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpeakerMediaLibraryTest {
    private static final byte[] MIDI = HexFormat.of().parseHex("4d546864000000060000000100604d54726b0000000400ff2f00");
    private static final HolderLookup.Provider REGISTRIES = HolderLookup.Provider.create(java.util.stream.Stream.empty());

    @Test void legacyArchivePreservesItsIdAndContentsForMigration() {
        var id = UUID.randomUUID();
        var original = new CompoundTag();
        original.putInt("version", 1);
        var books = new ListTag();
        var track = new CompoundTag();
        track.putUUID("id", id);
        track.putUUID("uploader", UUID.randomUUID());
        track.putString("name", "theme.mid");
        track.putByteArray("bytes", MIDI.clone());
        books.add(track);
        original.put("tracks", books);
        var legacy = SpeakerMediaLibrary.load(original, REGISTRIES);
        assertEquals(id, legacy.catalog().getFirst().id());
        assertArrayEquals(MIDI, legacy.media(id).orElseThrow());
        legacy.media(id).orElseThrow()[0] = 0;
        assertArrayEquals(MIDI, legacy.media(id).orElseThrow());
        assertFalse(legacy.isDirty());
        assertEquals(original, legacy.save(new CompoundTag(), REGISTRIES));
    }

    @Test void recognizesFramedMp3WithId3PrefixAndRejectsTruncatedTrack() {
        byte[] mp3 = new byte[10 + 2 * 417];
        System.arraycopy(HexFormat.of().parseHex("49443303000000000000"), 0, mp3, 0, 10);
        byte[] header = HexFormat.of().parseHex("fffb9000");
        for (int start : new int[]{10, 427}) System.arraycopy(header, 0, mp3, start, header.length);
        assertTrue(SpeakerMediaFormat.MP3.matches(mp3));
        assertFalse(SpeakerMediaFormat.MP3.matches(MIDI));
        assertNull(SpeakerMediaFormat.fromName("../theme.mid"));
        mp3[427] = 0;
        assertFalse(SpeakerMediaFormat.MP3.matches(mp3));
    }
}
