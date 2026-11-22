package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SpeakerPlaybackMp3Test {
    @Test void realStereoMpegFramesProduceBoundedNonSilentPcm() throws Exception {
        try (var decoder = SpeakerMp3Decoder.open(track())) {
            var pcm = new byte[SpeakerMp3Decoder.MAX_PCM_BYTES];
            int frames = 0;
            int nonzero = 0;
            for (int bytes; (bytes = decoder.next(pcm)) > 0; ) {
                assertTrue(++frames < 30);
                assertEquals(44100, decoder.sampleRate());
                assertEquals(2, decoder.channels());
                assertEquals(0, bytes % 4, "Complete interleaved 16-bit stereo samples");
                for (int i = 0; i < bytes; i++) if (pcm[i] != 0) nonzero++;
            }
            assertTrue(frames >= 3 && nonzero > 100, "The track produces decoded, non-silent audio");
            assertEquals(0, decoder.next(pcm), "End of track never silently restarts");
        }
    }

    @Test void rejectsTruncationAndOversizeBeforeOpeningDecoder() throws Exception {
        var bytes = track();
        assertThrows(IllegalArgumentException.class, () -> SpeakerMp3Decoder.open(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> SpeakerMp3Decoder.open(new byte[SpeakerMediaLimits.HARD_TRACK_BYTES + 1]));
    }

    private static byte[] track() throws Exception {
        try (var input = SpeakerPlaybackMp3Test.class.getResourceAsStream("/biocomputing/speaker-test-tone.mp3")) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }
}
