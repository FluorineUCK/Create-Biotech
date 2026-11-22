package com.nobodiiiii.createbiotech.content.biocomputing.device.narrator;

import java.nio.charset.StandardCharsets;

/** Bounds plain speech on both sides of the network; never interprets markup or commands. */
public final class NarratorOutputText {
    public static final int FORMAT_MAX_BYTES = 512;
    private NarratorOutputText() {}

    public static int checkedBytes(String text, int limit) {
        if (text == null) throw new IllegalArgumentException("invalid_speech_text");
        if (text.length() > Math.min(limit, FORMAT_MAX_BYTES)) throw new IllegalArgumentException("speech_text_limit");
        if (text.isBlank()) throw new IllegalArgumentException("invalid_speech_text");
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            if (Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029
                || codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)
                throw new IllegalArgumentException("invalid_speech_text");
            i += Character.charCount(codePoint);
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > Math.min(limit, FORMAT_MAX_BYTES)) throw new IllegalArgumentException("speech_text_limit");
        return bytes;
    }
}
