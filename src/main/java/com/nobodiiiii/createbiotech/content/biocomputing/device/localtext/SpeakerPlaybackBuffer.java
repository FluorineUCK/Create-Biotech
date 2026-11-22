package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import java.util.Objects;
import java.util.UUID;

/** Connection-ordered transfer, exposed to the decoder only after the explicit end frame. */
final class SpeakerPlaybackBuffer {
    private final UUID session;
    private byte[] received;
    private int offset;

    SpeakerPlaybackBuffer(UUID session, int size) {
        this.session = Objects.requireNonNull(session);
        if (size < 1 || size > SpeakerMediaLimits.HARD_TRACK_BYTES) throw new IllegalArgumentException("media_file_limit");
        received = new byte[size];
    }

    boolean append(UUID id, int start, byte[] part) {
        if (!session.equals(id) || received == null || start != offset || part.length < 1
            || part.length > SpeakerMediaChunk.CHUNK_BYTES || part.length > received.length - offset) return false;
        System.arraycopy(part, 0, received, offset, part.length);
        offset += part.length;
        return true;
    }

    byte[] finish(UUID id) {
        if (!session.equals(id) || received == null || offset != received.length) return null;
        var result = received;
        received = null;
        return result;
    }
}
