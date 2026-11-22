package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

/** Configurable quotas below invariant caps for import and save formats. */
public record SpeakerMediaLimits(int track, int world, int player, int count) {
    public static final int HARD_TRACK_BYTES = 8 * 1024 * 1024;
    public static final int HARD_WORLD_BYTES = 64 * 1024 * 1024;
    public static final int HARD_TRACKS = 256;
}
