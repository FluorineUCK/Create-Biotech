package com.nobodiiiii.createbiotech.content.biocomputing.device.narrator;

import java.util.ArrayDeque;
import java.util.Objects;

/** Client-thread queue and consent state, deliberately separate from the vanilla narrator. */
final class NarratorSpeechQueue {
    interface Backend {
        boolean available();
        /** Submit to an isolated platform instance, replacing its previous utterance if still active. */
        void speak(String text);
        void clear();
    }

    record Limits(int capacity, int minimumGapTicks, int ticksPerCodePoint) {
        Limits {
            if (capacity < 1 || capacity > 16 || minimumGapTicks < 1 || minimumGapTicks > 1200
                || ticksPerCodePoint < 1 || ticksPerCodePoint > 20)
                throw new IllegalArgumentException("invalid_speech_limits");
        }

        long spacing(String text) {
            return Math.max(minimumGapTicks, (long)text.codePointCount(0, text.length()) * ticksPerCodePoint);
        }
    }

    enum Offer { QUEUED, MUTED, FULL, INVALID_TEXT, UNAVAILABLE }
    enum Tick { IDLE, SUBMITTED, UNAVAILABLE }

    private final Backend backend;
    private final Limits limits;
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private long nextSubmission = Long.MIN_VALUE;
    private boolean enabled;

    NarratorSpeechQueue(Backend backend, Limits limits) {
        this.backend = Objects.requireNonNull(backend);
        this.limits = Objects.requireNonNull(limits);
    }

    void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (!enabled) clear();
    }

    void disconnect() { setEnabled(false); }

    Offer offer(String text) {
        if (!enabled) return Offer.MUTED;
        try { NarratorOutputText.checkedBytes(text, NarratorOutputText.FORMAT_MAX_BYTES); }
        catch (IllegalArgumentException invalid) { return Offer.INVALID_TEXT; }
        if (!backend.available()) return Offer.UNAVAILABLE;
        if (pending.size() >= limits.capacity()) return Offer.FULL;
        pending.addLast(text);
        return Offer.QUEUED;
    }

    Tick tick(long now) {
        if (!enabled || pending.isEmpty() || now < nextSubmission) return Tick.IDLE;
        if (!backend.available()) {
            clear();
            return Tick.UNAVAILABLE;
        }
        String text = pending.removeFirst();
        try { backend.speak(text); }
        catch (RuntimeException unavailable) {
            clear();
            return Tick.UNAVAILABLE;
        }
        long gap = limits.spacing(text);
        nextSubmission = now > Long.MAX_VALUE - gap ? Long.MAX_VALUE : now + gap;
        return Tick.SUBMITTED;
    }

    private void clear() {
        pending.clear();
        nextSubmission = Long.MIN_VALUE;
        try { backend.clear(); }
        catch (RuntimeException unavailable) { /* Muted locally even if the platform backend failed. */ }
    }
}
