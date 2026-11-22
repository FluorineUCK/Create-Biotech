package com.nobodiiiii.createbiotech.content.biocomputing.device.narrator;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorSpeechQueue.Offer.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorSpeechQueue.Tick.*;

class NarratorSpeechQueueTest {
    @Test void clientMuteAndDisconnectDiscardAllQueuedSpeechWithoutReplaying() {
        var backend = new Backend();
        var queue = new NarratorSpeechQueue(backend, new NarratorSpeechQueue.Limits(2, 20, 3));
        assertEquals(MUTED, queue.offer("old"));
        queue.setEnabled(true);
        assertEquals(QUEUED, queue.offer("first"));
        assertEquals(QUEUED, queue.offer("second"));
        assertEquals(FULL, queue.offer("third"));
        assertEquals(SUBMITTED, queue.tick(10));
        assertEquals(IDLE, queue.tick(29));
        assertEquals(List.of("first"), backend.submitted);

        queue.setEnabled(false);
        assertEquals(1, backend.cleared);
        assertEquals(IDLE, queue.tick(100));
        queue.setEnabled(true);
        assertEquals(IDLE, queue.tick(101));
        assertEquals(QUEUED, queue.offer("fresh"));
        assertEquals(SUBMITTED, queue.tick(102));
        assertEquals(List.of("first", "fresh"), backend.submitted);

        assertEquals(QUEUED, queue.offer("pending at disconnect"));
        queue.disconnect();
        assertEquals(2, backend.cleared);
        assertEquals(MUTED, queue.offer("while disconnected"));
        assertEquals(IDLE, queue.tick(1000));
        assertEquals(List.of("first", "fresh"), backend.submitted);
    }

    @Test void unavailableBackendDoesNotQueueOrReplaySpeechWhenItRecovers() {
        var backend = new Backend();
        var queue = new NarratorSpeechQueue(backend, new NarratorSpeechQueue.Limits(2, 20, 3));
        queue.setEnabled(true);
        backend.available = false;
        assertEquals(NarratorSpeechQueue.Offer.UNAVAILABLE, queue.offer("not admitted"));
        backend.available = true;
        assertEquals(QUEUED, queue.offer("platform fails during this call"));
        assertEquals(QUEUED, queue.offer("pending when it fails"));
        backend.fail = true;
        assertEquals(NarratorSpeechQueue.Tick.UNAVAILABLE, queue.tick(10));
        assertEquals(1, backend.cleared);
        backend.fail = false;
        assertEquals(IDLE, queue.tick(11));
        assertEquals(QUEUED, queue.offer("fresh request"));
        assertEquals(SUBMITTED, queue.tick(12));
        assertEquals(List.of("fresh request"), backend.submitted);
    }

    private static final class Backend implements NarratorSpeechQueue.Backend {
        private final List<String> submitted = new ArrayList<>();
        private int cleared;
        private boolean available = true;
        private boolean fail;
        @Override public boolean available() { return available; }
        @Override public void speak(String text) {
            if (fail) throw new IllegalStateException("test speech backend failed");
            submitted.add(text);
        }
        @Override public void clear() { cleared++; }
    }
}
