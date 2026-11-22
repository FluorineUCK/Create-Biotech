package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import java.io.ByteArrayInputStream;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;

/** Client-only decode: at most one MP3 frame in interleaved signed little-endian PCM. */
final class SpeakerMp3Decoder implements AutoCloseable {
    static final int MAX_PCM_BYTES = 1152 * 2 * Short.BYTES;
    private final Bitstream bits;
    private final Decoder decoder = new Decoder();
    private int remaining, sampleRate, channels;
    private boolean closed;

    /** Consumes the owned, transient media snapshot. The caller must not mutate it. */
    static SpeakerMp3Decoder open(byte[] contents) {
        if (contents.length > SpeakerMediaLimits.HARD_TRACK_BYTES) throw invalid();
        var bounds = SpeakerMediaFormat.mp3Bounds(contents);
        if (bounds == null) throw invalid();
        return new SpeakerMp3Decoder(new Bitstream(new ByteArrayInputStream(contents, bounds.start(), bounds.length())), bounds.frames());
    }

    private SpeakerMp3Decoder(Bitstream bits, int frames) { this.bits = bits; remaining = frames; }
    int sampleRate() { return sampleRate; }
    int channels() { return channels; }

    /** One frame or zero at EOF. Sample rate and channel count cannot change downstream. */
    int next(byte[] pcm) throws JavaLayerException {
        if (closed) throw new IllegalStateException("media_closed");
        if (pcm.length < MAX_PCM_BYTES) throw new IllegalArgumentException("media_pcm_buffer_small");
        if (remaining == 0) return 0;
        var header = bits.readFrame();
        if (header == null) throw invalid();
        try {
            int rate = header.frequency(), count = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
            if (sampleRate != 0 && (sampleRate != rate || channels != count)) throw invalid();
            if (!(decoder.decodeFrame(header, bits) instanceof SampleBuffer samples)) throw invalid();
            int length = samples.getBufferLength();
            if (length < 1 || length > MAX_PCM_BYTES / Short.BYTES || length % count != 0
                || samples.getSampleFrequency() != rate || samples.getChannelCount() != count) throw invalid();
            var decoded = samples.getBuffer();
            for (int i = 0; i < length; i++) {
                short sample = decoded[i];
                pcm[i * 2] = (byte)sample;
                pcm[i * 2 + 1] = (byte)(sample >>> 8);
            }
            sampleRate = rate;
            channels = count;
            remaining--;
            return length * Short.BYTES;
        } finally {
            bits.closeFrame();
        }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("media_invalid"); }

    @Override public void close() throws BitstreamException {
        if (closed) return;
        closed = true;
        bits.close();
    }
}
