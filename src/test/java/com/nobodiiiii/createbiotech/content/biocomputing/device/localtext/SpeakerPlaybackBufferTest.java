package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpeakerPlaybackBufferTest {
    @Test void onlyCompleteOrderedBytesFromTheCurrentSessionAreExposedOnce() {
        var session = UUID.randomUUID();
        var buffer = new SpeakerPlaybackBuffer(session, 4);
        assertFalse(buffer.append(UUID.randomUUID(), 0, new byte[]{99}));
        assertTrue(buffer.append(session, 0, new byte[]{1, 2}));
        assertFalse(buffer.append(session, 1, new byte[]{99}), "Overlapping frames must not change received data");
        assertNull(buffer.finish(session), "An incomplete track is not handed to the decoder");
        assertTrue(buffer.append(session, 2, new byte[]{3, 4}));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, buffer.finish(session));
        assertNull(buffer.finish(session), "Replay must not restart playback");
    }

    @Test void invalidSizesAndOverrunsCannotAllocateOrExtendTheBuffer() {
        var session = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
            () -> new SpeakerPlaybackBuffer(session, SpeakerMediaLimits.HARD_TRACK_BYTES + 1));
        var buffer = new SpeakerPlaybackBuffer(session, 2);
        assertFalse(buffer.append(session, 0, new byte[]{1, 2, 3}));
        assertTrue(buffer.append(session, 0, new byte[]{4, 5}));
        assertArrayEquals(new byte[]{4, 5}, buffer.finish(session));
    }
}
