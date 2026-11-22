package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryTrackAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpeakerMediaPlaybackSourceTest {
    @Test void decodesAPhysicalVersionReferenceWithoutAcceptingAFileOrUnrepresentablePosition() {
        var device = UUID.randomUUID();
        var version = new LibraryMutation.Version(UUID.randomUUID(), "theme", 2);
        assertEquals(new SpeakerMediaPlaybackSource.Reference(new BlockPos(-4, 7, -9), device, 5, version),
            SpeakerMediaPlaybackSource.Reference.decode(reference(new LibraryTrackAddress(-4, 7, -9), device, 5, version)));
        assertThrows(IllegalArgumentException.class, () -> SpeakerMediaPlaybackSource.Reference.decode(
            reference(new LibraryTrackAddress(-4, 8192, -9), device, 5, version)));
        assertThrows(IllegalArgumentException.class, () -> SpeakerMediaPlaybackSource.Reference.decode(
            reference(new LibraryTrackAddress(-4, 7, -9), device, 0, version)));
        assertThrows(IllegalArgumentException.class, () -> SpeakerMediaPlaybackSource.Reference.decode(text("theme.mid")));
    }

    @Test void explicitFileSchemaAndQuotaAreRecheckedAtOutput() {
        var midi = HexFormat.of().parseHex("4d546864000000060000000100604d54726b0000000400ff2f00");
        var entry = record("schema", text(SpeakerMediaLibraryFile.SCHEMA), "name", text("theme.mid"),
            "format", text("MIDI"), "payload", text(Base64.getEncoder().encodeToString(midi)));
        var decoded = new SpeakerMediaPlaybackSource.Prepared(entry, midi.length).decode();
        assertEquals(SpeakerMediaFormat.MIDI, decoded.format());
        assertArrayEquals(midi, decoded.bytes());
        assertThrows(IllegalArgumentException.class, () -> new SpeakerMediaPlaybackSource.Prepared(entry, midi.length - 1).decode());
        assertThrows(IllegalArgumentException.class, () -> new SpeakerMediaPlaybackSource.Prepared(text("theme.mid"), midi.length).decode());
    }

    private static DataValue.RecordValue reference(LibraryTrackAddress library, UUID device, int sequence, LibraryMutation.Version version) {
        return record("library", library.value(), "device", text(device.toString()), "sequence",
            new DataValue.IntegerValue(sequence), "version", version.value());
    }

    private static DataValue.RecordValue record(String a, DataValue av, String b, DataValue bv, String c, DataValue cv, String d, DataValue dv) {
        return new DataValue.RecordValue(Map.of(a, av, b, bv, c, cv, d, dv));
    }

    private static DataValue.TextValue text(String text) { return new DataValue.TextValue(text); }
}
