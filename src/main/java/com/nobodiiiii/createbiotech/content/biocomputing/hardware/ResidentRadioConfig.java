package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owned pilot settings; the host registers this below the existing biocomputing config. */
public final class ResidentRadioConfig {
    private static final int MAX_FRAME_BYTES = 65536, MAX_RECEIVE_FRAMES = 64;
    private static final long MAX_INTERFACE_RATE = 1048576L, MAX_RECEIVE_BYTES = 4194304L;
    private final ModConfigSpec.IntValue witchNarrowWidth;
    private final ModConfigSpec.IntValue presets, first, spacing, transmitWidth, receiveWidth;
    private final ModConfigSpec.IntValue symbols, density, range, signalToNoise, activePorts;
    private final ModConfigSpec.IntValue frameBytes, receiveFrames;
    private final ModConfigSpec.LongValue interfaceBits, receiveBytes, framingBits;

    public ResidentRadioConfig(ModConfigSpec.Builder builder) {
        builder.comment("Base winding of a wandering-trader transceiver. Frequencies and signal strengths are game units.")
            .push("radio");
        presets = builder.defineInRange("stablePresets", 16, 1, ResidentRadioHardware.MAX_PRESETS);
        first = builder.defineInRange("firstCarrier", 100, 0, Integer.MAX_VALUE);
        spacing = builder.defineInRange("carrierSpacing", 8, 1, Integer.MAX_VALUE);
        transmitWidth = builder.defineInRange("transmitWidth", 6, 1, Integer.MAX_VALUE);
        receiveWidth = builder.defineInRange("receiveFilterWidth", 12, 1, Integer.MAX_VALUE);
        witchNarrowWidth = builder.comment("Maximum passband of the witch narrow receiver filter. A wider emission cannot lock.")
            .defineInRange("witchNarrowWidth", 6, 1, Integer.MAX_VALUE);
        symbols = builder.defineInRange("symbolsPerTick", 16, 1, 4096);
        density = builder.defineInRange("bitsPerSymbol", 2, 1, 64);
        range = builder.comment("Signal falls to zero at this radius; only loaded devices participate.")
            .defineInRange("rangeBlocks", 64, 1, 4096);
        signalToNoise = builder.comment("Required wanted-to-aggregate-interference ratio; equality is not enough to decode.")
            .defineInRange("minimumSignalToNoise", 1, 1, 64);
        activePorts = builder.defineInRange("activePorts", 32, 2, ResidentRadioHardware.MAX_ACTIVE_PORTS);
        framingBits = builder.defineInRange("framingBits", 32L, 0L, 65536L);
        interfaceBits = builder.defineInRange("interfaceBitsPerTick", 128L, 1L, MAX_INTERFACE_RATE);
        frameBytes = builder.defineInRange("frameBytes", 4096, 1, MAX_FRAME_BYTES);
        receiveFrames = builder.defineInRange("receiveFrames", 4, 1, MAX_RECEIVE_FRAMES);
        receiveBytes = builder.defineInRange("receiveBytes", 16384L, 1L, MAX_RECEIVE_BYTES);
        builder.pop();
    }

    /** Decode-only ceiling for a retired resident's complete frames. Not a live hardware profile. */
    public static SerialHardware.Port retentionCeiling() {
        return new SerialHardware.Port(MAX_INTERFACE_RATE, MAX_FRAME_BYTES, MAX_RECEIVE_FRAMES, MAX_RECEIVE_BYTES);
    }

    public int witchNarrowWidth(boolean loaded) { return value(witchNarrowWidth, loaded); }

    public ResidentRadioHardware current(boolean loaded) {
        return new ResidentRadioHardware(
            new ResidentRadioHardware.Oscillator(value(presets, loaded), value(first, loaded), value(spacing, loaded),
                value(transmitWidth, loaded), value(receiveWidth, loaded)),
            new ResidentRadioHardware.Link(value(symbols, loaded), value(density, loaded), value(range, loaded),
                value(signalToNoise, loaded), value(activePorts, loaded), value(framingBits, loaded)),
            new SerialHardware.Port(value(interfaceBits, loaded), value(frameBytes, loaded),
                value(receiveFrames, loaded), value(receiveBytes, loaded)));
    }

    private static <T> T value(ModConfigSpec.ConfigValue<T> config, boolean loaded) {
        return loaded ? config.get() : config.getDefault();
    }
}

