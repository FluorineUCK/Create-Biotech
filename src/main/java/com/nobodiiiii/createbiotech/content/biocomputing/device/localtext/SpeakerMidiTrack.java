package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;

/** Client decoder and local playback handle, never a MIDI output port or an acoustic acknowledgement. */
final class SpeakerMidiTrack {
    private static final int TRACK_CHUNK = 0x4d54726b;
    // The file byte cap alone would allow millions of retained Java events.
    private static final int MAX_EVENTS = 65_536;
    private final Sequence sequence;

    private SpeakerMidiTrack(Sequence sequence) { this.sequence = sequence; }

    /** Decode on the audio worker, not during a client packet handler or a server tick. */
    static SpeakerMidiTrack decode(byte[] bytes) throws InvalidMidiDataException, IOException {
        if (bytes.length > SpeakerMediaLimits.HARD_TRACK_BYTES) throw invalid("media_file_limit");
        var snapshot = bytes.clone();
        if (!SpeakerMediaFormat.MIDI.matches(snapshot)) throw invalid("media_invalid");
        var file = ByteBuffer.wrap(snapshot);
        if (file.getShort(8) == 2) throw invalid("media_unsupported_midi_format");
        try {
            int remaining = MAX_EVENTS;
            for (int chunk = 8 + file.getInt(4); chunk < file.limit();) {
                int length = file.getInt(chunk + 4);
                if (file.getInt(chunk) == TRACK_CHUNK) remaining = scanTrack(file.slice(chunk + 8, length), remaining);
                chunk += 8 + length;
            }
        } catch (BufferUnderflowException malformed) {
            throw invalid("media_invalid");
        }
        return new SpeakerMidiTrack(MidiSystem.getSequence(new ByteArrayInputStream(snapshot)));
    }

    private static int scanTrack(ByteBuffer track, int remaining) throws InvalidMidiDataException {
        int running = 0;
        while (track.hasRemaining()) {
            if (remaining-- == 0) throw invalid("media_event_limit");
            variable(track); // event delta
            int initial = Byte.toUnsignedInt(track.get());
            int status = initial < 0x80 ? running : initial;
            if (status >= 0x80 && status < 0xf0) {
                running = status;
                int command = status & 0xf0;
                int dataBytes = command == 0xc0 || command == 0xd0 ? 1 : 2;
                for (int i = initial < 0x80 ? 1 : 0; i < dataBytes; i++)
                    if (track.get() < 0) throw invalid("media_invalid");
            } else if (status == 0xf0 || status == 0xf7 || status == 0xff) {
                int meta = status == 0xff ? Byte.toUnsignedInt(track.get()) : -1;
                int length = variable(track);
                if (length > track.remaining()) throw invalid("media_invalid");
                if (meta == 0x2f) {
                    if (length != 0 || track.hasRemaining()) throw invalid("media_invalid");
                    return remaining;
                }
                track.position(track.position() + length);
            } else throw invalid("media_invalid");
        }
        throw invalid("media_invalid"); // No End of Track.
    }

    private static int variable(ByteBuffer input) throws InvalidMidiDataException {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int next = Byte.toUnsignedInt(input.get());
            value = (value << 7) | (next & 0x7f);
            if (next < 0x80) return value;
        }
        throw invalid("media_invalid");
    }

    private static InvalidMidiDataException invalid(String code) { return new InvalidMidiDataException(code); }

    /** Open a private synthesizer, never the operating system's default MIDI receiver. Does not start it. */
    Playback open() throws MidiUnavailableException, InvalidMidiDataException {
        var synth = MidiSystem.getSynthesizer();
        try {
            synth.open();
            return open(synth.getReceiver(), synth);
        } catch (MidiUnavailableException | InvalidMidiDataException | RuntimeException failure) {
            synth.close();
            throw failure;
        }
    }

    Playback open(Receiver output) throws MidiUnavailableException, InvalidMidiDataException { return open(output, null); }

    private Playback open(Receiver output, Synthesizer synth) throws MidiUnavailableException, InvalidMidiDataException {
        Sequencer sequencer = null;
        try {
            sequencer = MidiSystem.getSequencer(false);
            sequencer.open();
            sequencer.setSequence(sequence);
            sequencer.getTransmitter().setReceiver(new Receiver() {
                @Override public void send(MidiMessage message, long time) {
                    if (message instanceof ShortMessage && message.getStatus() < 0xf0) output.send(message, time);
                }
                @Override public void close() {}
            });
            return new Playback(sequencer, output, synth);
        } catch (MidiUnavailableException | InvalidMidiDataException | RuntimeException failure) {
            try { if (sequencer != null) sequencer.close(); }
            finally { output.close(); }
            throw failure;
        }
    }

    /** One start per authorized playback; callers close on stop, completion or client detachment. */
    static final class Playback implements SpeakerAudioPlayback {
        private final Sequencer sequencer;
        private final Receiver output;
        private final Synthesizer synth;
        private boolean started, closed;

        private Playback(Sequencer sequencer, Receiver output, Synthesizer synth) {
            this.sequencer = sequencer; this.output = output; this.synth = synth;
        }

        @Override public synchronized void start() {
            if (closed || started) throw new IllegalStateException("Playback cannot be restarted");
            started = true;
            sequencer.start();
        }

        @Override public synchronized boolean isRunning() { return started && !closed && sequencer.isRunning(); }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            try { sequencer.close(); }
            finally {
                try { output.close(); }
                finally { if (synth != null) synth.close(); }
            }
        }
    }
}
