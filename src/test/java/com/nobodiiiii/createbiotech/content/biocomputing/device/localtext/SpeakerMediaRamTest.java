package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFileRam;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpeakerMediaRamTest {
    private static final byte[] MIDI = HexFormat.of().parseHex("4d546864000000060000000100604d54726b0000000400ff2f00");
    private final ComputerFileRam ram = new ComputerFileRam();
    private final UUID owner = UUID.randomUUID();

    @Test void exactEncodingAbortsPartialOutputAndCannotMutateASealedFile() throws IOException {
        var shortWrite = ram.reserve(owner, "wire", 3, 3, ignored -> true).orElseThrow();
        assertFalse(shortWrite.writeExact(out -> out.write(1)));
        assertEquals(0, ram.reservedBytes());
        assertTrue(ram.file(owner).isEmpty());

        var overrun = ram.reserve(owner, "wire", 3, 3, ignored -> true).orElseThrow();
        assertThrows(IOException.class, () -> overrun.writeExact(out -> out.write(new byte[]{1, 2, 3, 4})));
        assertEquals(0, ram.reservedBytes());

        var failed = ram.reserve(owner, "wire", 3, 3, ignored -> true).orElseThrow();
        assertThrows(IOException.class, () -> failed.writeExact(out -> { out.write(1); throw new IOException("truncated"); }));
        assertEquals(0, ram.reservedBytes());

        var exact = ram.reserve(owner, "wire", 3, 3, ignored -> true).orElseThrow();
        OutputStream[] escaped = new OutputStream[1];
        byte[] bytes = {1, 2, 3};
        assertTrue(exact.writeExact(out -> { escaped[0] = out; out.write(bytes); }));
        assertTrue(ram.file(owner).isEmpty());
        assertTrue(exact.seal(ignored -> true));
        assertThrows(IOException.class, () -> escaped[0].write(4));
        assertArrayEquals(bytes, exact.slice(0, 3));
    }

    @Test void unfinishedAndCompletedImportBothReserveRamUntilDiscarded() {
        var lease = ram.reserve(owner, "theme.mid", MIDI.length, MIDI.length, available -> available == 0).orElseThrow();
        assertEquals(MIDI.length, ram.reservedBytes());
        assertTrue(ram.file(owner).isEmpty());
        assertTrue(ram.reserve(UUID.randomUUID(), "other.mid", MIDI.length, 100, ignored -> true).isEmpty());
        assertTrue(lease.append(0, Arrays.copyOfRange(MIDI, 0, 10)));
        assertTrue(lease.append(10, Arrays.copyOfRange(MIDI, 10, MIDI.length)));
        assertEquals("media_staged", SpeakerMediaUpload.finish(lease, "theme.mid"));
        assertEquals(MIDI.length, ram.file(owner).orElseThrow().size());
        assertEquals(MIDI.length, ram.reservedBytes());
        ram.clear(owner);
        assertEquals(0, ram.reservedBytes());
        assertTrue(ram.file(owner).isEmpty());
        assertFalse(lease.append(0, MIDI));
    }

    @Test void unavailableRamDoesNotAllocateAndClosingPartialImportReleasesOnlyItsReservation() {
        assertTrue(ram.reserve(owner, "theme.mid", MIDI.length, MIDI.length - 1, unused -> fail()).isEmpty());
        assertTrue(ram.reserve(owner, "theme.mid", MIDI.length, MIDI.length, unused -> false).isEmpty());
        assertEquals(0, ram.reservedBytes());
        var lease = ram.reserve(owner, "theme.mid", MIDI.length, MIDI.length, unused -> true).orElseThrow();
        assertTrue(lease.append(0, new byte[]{1}));
        ram.clear(UUID.randomUUID());
        assertEquals(MIDI.length, ram.reservedBytes());
        lease.abort();
        assertEquals(0, ram.reservedBytes());
    }

    @Test void invalidMediaIsNotPublishedAndReleasesRam() {
        var lease = ram.reserve(owner, "theme.mp3", MIDI.length, 1024, unused -> true).orElseThrow();
        assertTrue(lease.append(0, MIDI));
        assertEquals("media_invalid", SpeakerMediaUpload.finish(lease, "theme.mp3"));
        assertTrue(ram.file(owner).isEmpty());
        assertEquals(0, ram.reservedBytes());
    }
}
