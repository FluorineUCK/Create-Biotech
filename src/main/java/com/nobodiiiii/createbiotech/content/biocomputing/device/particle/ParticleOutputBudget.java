package com.nobodiiiii.createbiotech.content.biocomputing.device.particle;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;

/** One counter per loaded dimension, used on its server tick thread. Never queued or saved. */
final class ParticleOutputBudget {
    private static final Map<ServerLevel, ParticleOutputBudget> LEVELS = new WeakHashMap<>();
    private long tick = Long.MIN_VALUE;
    private int used;

    static ParticleOutputBudget of(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, ignored -> new ParticleOutputBudget());
    }

    int remaining(long now, int limit) { return Math.max(0, limit - (now == tick ? used : 0)); }

    boolean consume(long now, int count, int limit) {
        if (count < 1 || count > remaining(now, limit)) return false;
        if (tick != now) { tick = now; used = 0; }
        used += count;
        return true;
    }
}
