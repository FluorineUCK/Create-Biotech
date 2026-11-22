package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.mojang.logging.LogUtils;
import java.util.concurrent.*;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.JavaLayerException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** The Minecraft client owns this output. The server never opens an audio device. */
final class SpeakerMp3Audio implements SpeakerAudioPlayback {
    private static final Executor STREAM = new ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS, new SynchronousQueue<>(),
        runnable -> Thread.ofPlatform().daemon().name("CB speaker MP3 stream").unstarted(runnable));
    private final SpeakerMp3Decoder decoder;
    private final SourceDataLine line;
    private final byte[] pcm;
    private final int first;
    private volatile boolean started, closed;

    private SpeakerMp3Audio(SpeakerMp3Decoder decoder, SourceDataLine line, byte[] pcm, int first) {
        this.decoder = decoder; this.line = line; this.pcm = pcm; this.first = first;
    }

    static SpeakerAudioPlayback open(byte[] contents) throws JavaLayerException, LineUnavailableException {
        var decoder = SpeakerMp3Decoder.open(contents);
        SourceDataLine line = null;
        try {
            var pcm = new byte[SpeakerMp3Decoder.MAX_PCM_BYTES];
            int first = decoder.next(pcm);
            if (first == 0) throw new IllegalArgumentException("media_invalid");
            var format = new AudioFormat(decoder.sampleRate(), 16, decoder.channels(), true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, 4 * pcm.length);
            return new SpeakerMp3Audio(decoder, line, pcm, first);
        } catch (RuntimeException | JavaLayerException | LineUnavailableException failed) {
            try { if (line != null) line.close(); }
            finally { closeDecoder(decoder); }
            throw failed;
        }
    }

    @Override public synchronized void start() {
        if (closed || started) throw new IllegalStateException("Playback cannot be restarted");
        line.start();
        started = true;
        try { STREAM.execute(this::pump); }
        catch (RejectedExecutionException busy) {
            started = false;
            close();
            throw busy;
        }
    }

    private void pump() {
        try {
            for (int length = first; !closed && length > 0; length = closed ? 0 : decoder.next(pcm)) {
                for (int offset = 0; !closed && offset < length; ) {
                    int written = line.write(pcm, offset, length - offset);
                    if (written <= 0) return; // A closed platform line is never retried.
                    offset += written;
                }
            }
            if (!closed) line.drain();
        } catch (RuntimeException | JavaLayerException failure) {
            if (!closed) LogUtils.getLogger().debug("Biocomputing MP3 decoding or playback failed", failure);
        } finally {
            try { close(); }
            finally { closeDecoder(decoder); }
        }
    }

    @Override public boolean isRunning() { return started && !closed; }

    @Override public void close() {
        boolean closeDecoderHere;
        synchronized (this) {
            if (closed) return;
            closed = true;
            closeDecoderHere = !started;
        }
        try { line.close(); }
        finally { if (closeDecoderHere) closeDecoder(decoder); }
    }

    private static void closeDecoder(SpeakerMp3Decoder decoder) {
        try { decoder.close(); }
        catch (BitstreamException failure) { LogUtils.getLogger().debug("Could not close the MP3 input", failure); }
    }
}
