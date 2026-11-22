package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.mojang.serialization.Codec;
import java.util.Objects;
import net.minecraft.util.StringRepresentable;

/** Capability of one cable material, independent of port frame limits and codec. */
public record SerialWireMedium(Material material, SharedSerialChannel.Limits limits) {
    public enum Mode { SHARED, FULL_DUPLEX }

    /** Physical identity is independent of configurable rates, including equal-rate materials. */
    public enum Material implements StringRepresentable {
        BASIC("basic", Mode.SHARED),
        FAST("fast", Mode.SHARED),
        DUPLEX("duplex", Mode.FULL_DUPLEX);

        public static final Codec<Material> CODEC = StringRepresentable.fromEnum(Material::values);
        private final String id;
        private final Mode mode;
        Material(String id, Mode mode) { this.id = id; this.mode = mode; }
        @Override public String getSerializedName() { return id; }
        public Mode mode() { return mode; }
    }

    public SerialWireMedium {
        Objects.requireNonNull(material);
        Objects.requireNonNull(limits);
        if (material.mode() == Mode.FULL_DUPLEX && limits.maxPorts() < 2)
            throw new IllegalArgumentException("A full-duplex cable must support two endpoints");
    }

    public Mode mode() { return material.mode(); }

    public static SerialWireMedium shared(SharedSerialChannel.Limits limits) {
        return new SerialWireMedium(Material.BASIC, limits);
    }

    public static SerialWireMedium fullDuplex(SharedSerialChannel.Limits limits) {
        return new SerialWireMedium(Material.DUPLEX, limits);
    }
}
