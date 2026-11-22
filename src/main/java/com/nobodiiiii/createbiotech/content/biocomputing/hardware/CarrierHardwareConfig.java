package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Physical clock hardware only; RF coding, serial data rate and CPU budgets are separate. */
public final class CarrierHardwareConfig {
    private final ModConfigSpec.IntValue maxPathBlocks, maxFactor, defaultDivisor, defaultMultiplier, lockTicks;

    public CarrierHardwareConfig(ModConfigSpec.Builder builder) {
        builder.push("carrier");
        maxPathBlocks = builder.comment("Maximum blocks sampled on one directed carrier path.")
            .defineInRange("maxPathBlocks", 64, 1, 256);
        maxFactor = builder.defineInRange("maxFactor", 16, 1, 65536);
        defaultDivisor = builder.defineInRange("defaultDivisor", 3, 1, 65536);
        defaultMultiplier = builder.defineInRange("defaultMultiplier", 2, 1, 65536);
        lockTicks = builder.comment("Active device ticks required to acquire a valid reference.")
            .defineInRange("lockTicks", 20, 1, 1200);
        builder.pop();
    }

    public record Limits(int maxPathBlocks, int maxFactor, int defaultDivisor, int defaultMultiplier, int lockTicks) {
        public Limits {
            if (maxPathBlocks < 1 || maxFactor < 1 || defaultDivisor < 1 || defaultMultiplier < 1 || lockTicks < 1)
                throw new IllegalArgumentException("Invalid carrier hardware limits");
        }
        public int initialFactor(boolean multiply) { return Math.min(maxFactor, multiply ? defaultMultiplier : defaultDivisor); }
    }

    public Limits current(boolean loaded) {
        return new Limits(value(maxPathBlocks, loaded), value(maxFactor, loaded), value(defaultDivisor, loaded),
            value(defaultMultiplier, loaded), value(lockTicks, loaded));
    }

    private static int value(ModConfigSpec.IntValue option, boolean loaded) { return loaded ? option.get() : option.getDefault(); }
}
