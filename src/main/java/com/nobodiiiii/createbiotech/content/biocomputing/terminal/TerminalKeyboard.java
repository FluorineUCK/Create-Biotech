package com.nobodiiiii.createbiotech.content.biocomputing.terminal;

import java.util.UUID;

/** One physical Computer's transient, bounded terminal sample. Never part of a checkpoint. */
public final class TerminalKeyboard {
    public static final int REFRESH_TICKS = 5;
    private static final int LEASE_TICKS = REFRESH_TICKS * 3;
    private UUID owner;
    private long bits;
    private int remaining;

    public void update(UUID source, long state) {
        TerminalKey.requireBits(state);
        if (state == 0) {
            if (source.equals(owner)) clear();
        } else {
            owner = source; bits = state; remaining = LEASE_TICKS;
        }
    }
    public long sample() { return bits; }
    public void tick() { if (remaining > 0 && --remaining == 0) clear(); }
    public void clear() { owner = null; bits = 0; remaining = 0; }
}
