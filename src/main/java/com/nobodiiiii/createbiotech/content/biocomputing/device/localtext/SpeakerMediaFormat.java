package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import java.util.Locale;

/** Cheap container framing check only; track admission does not claim successful decoding. */
enum SpeakerMediaFormat {
    MIDI, MP3;

    private static final int MTHD = 0x4d546864, MTRK = 0x4d54726b;
    private static final int[] MPEG1_KBPS = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] MPEG2_KBPS = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
    private static final int[] BASE_HZ = {44100, 48000, 32000};

    static SpeakerMediaFormat fromName(String name) {
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
            || name.indexOf(':') >= 0 || name.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT)) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mid") || lower.endsWith(".midi")) return MIDI;
        return lower.endsWith(".mp3") ? MP3 : null;
    }

    boolean matches(byte[] bytes) { return this == MIDI ? midi(bytes) : mp3Bounds(bytes) != null; }

    private static boolean midi(byte[] b) {
        if (b.length < 14 || readInt(b, 0) != MTHD) return false;
        int header = readInt(b, 4), format = readShort(b, 8), tracks = readShort(b, 10);
        if (header < 6 || header > b.length - 8 || format > 2 || tracks == 0 || (format == 0 && tracks != 1)
            || readShort(b, 12) == 0) return false;
        int found = 0;
        for (int p = 8 + header; p < b.length;) {
            if (b.length - p < 8) return false;
            int kind = readInt(b, p), length = readInt(b, p + 4);
            if (length < 0 || length > b.length - p - 8) return false;
            if (kind == MTRK && (length < 4 || ++found > tracks)) return false;
            p += 8 + length;
        }
        return found == tracks;
    }

    record Mp3Bounds(int start, int length, int frames) {}

    /** Strip optional ID3 metadata before the decoder; frames remain in the original buffer. */
    static Mp3Bounds mp3Bounds(byte[] b) {
        int p = 0, end = b.length;
        if (end >= 10 && (readInt(b, 0) >>> 8) == 0x494433) { // ID3v2
            int version = b[3] & 255, size = 0;
            if (version < 2 || version > 4) return null;
            for (int i = 6; i < 10; i++) {
                if (b[i] < 0) return null;
                size = (size << 7) | b[i];
            }
            p = 10 + size + (version == 4 && (b[5] & 0x10) != 0 ? 10 : 0);
        }
        if (end >= 128 && (readInt(b, end - 128) >>> 8) == 0x544147) end -= 128; // ID3v1
        int start = p;
        int frames = 0;
        while (end - p >= 4) {
            int h = readInt(b, p), version = (h >>> 19) & 3, kbps = (h >>> 12) & 15, rate = (h >>> 10) & 3;
            if ((h & 0xffe00000) != 0xffe00000 || version == 1 || ((h >>> 17) & 3) != 1
                || kbps == 0 || kbps == 15 || rate == 3) return null;
            int divisor = version == 3 ? 1 : version == 2 ? 2 : 4;
            int length = (version == 3 ? MPEG1_KBPS[kbps] * 144000 : MPEG2_KBPS[kbps] * 72000)
                / (BASE_HZ[rate] / divisor) + ((h >>> 9) & 1);
            if (length > end - p) return null;
            p += length;
            frames++;
        }
        return frames > 0 && p == end ? new Mp3Bounds(start, end - start, frames) : null;
    }

    private static int readShort(byte[] b, int p) { return ((b[p] & 255) << 8) | (b[p + 1] & 255); }
    private static int readInt(byte[] b, int p) { return (readShort(b, p) << 16) | readShort(b, p + 2); }
}
