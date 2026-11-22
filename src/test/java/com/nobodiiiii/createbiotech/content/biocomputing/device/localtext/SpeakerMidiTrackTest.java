package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.nio.ByteBuffer;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpeakerMidiTrackTest {
    // Standard MIDI file: Middle C begins at tick 0, ends at tick 32.
    private static final byte[] NOTE = HexFormat.of().parseHex(
        "4d546864000000060000000100604d54726b0000000c00903c4020803c0000ff2f00");

    @Test void sendsDecodedNotesOnlyAfterAnExplicitStartAndClosesItsOutput() throws Exception {
        var on = new CountDownLatch(1);
        var off = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var track = SpeakerMidiTrack.decode(NOTE);
        try (var playback = track.open(new Receiver() {
            @Override public void send(MidiMessage message, long time) {
                if (message instanceof ShortMessage note && note.getData1() == 60) {
                    if (note.getCommand() == ShortMessage.NOTE_ON && note.getData2() == 64) on.countDown();
                    if (note.getCommand() == ShortMessage.NOTE_OFF) off.countDown();
                }
            }
            @Override public void close() { closed.countDown(); }
        })) {
            assertFalse(playback.isRunning());
            assertEquals(1, on.getCount());
            playback.start();
            assertTrue(on.await(3, TimeUnit.SECONDS));
            assertTrue(off.await(3, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, playback::start);
        }
        assertEquals(0, closed.getCount());
    }

    @Test void rejectsATruncatedEventEvenWhenEveryContainerLengthIsValid() {
        var broken = NOTE.clone();
        broken[broken.length - 1] = 1; // END_OF_TRACK declares a nonexistent data byte.
        assertTrue(SpeakerMediaFormat.MIDI.matches(broken));
        assertEquals("media_invalid", assertThrows(InvalidMidiDataException.class, () -> SpeakerMidiTrack.decode(broken)).getMessage());
    }

    @Test void aSmallFileCannotExceedTheRetainedEventBudget() {
        int repeats = 70_000;
        int size = repeats * 3 + 4;
        var file = ByteBuffer.allocate(22 + size).put(NOTE, 0, 18).putInt(size);
        for (int i = 0; i < repeats; i++) file.put((byte)0).put((byte)0xc0).put((byte)0); // program change
        file.putInt(0x00ff2f00);
        assertTrue(SpeakerMediaFormat.MIDI.matches(file.array()));
        assertEquals("media_event_limit",
            assertThrows(InvalidMidiDataException.class, () -> SpeakerMidiTrack.decode(file.array())).getMessage());
    }
}
