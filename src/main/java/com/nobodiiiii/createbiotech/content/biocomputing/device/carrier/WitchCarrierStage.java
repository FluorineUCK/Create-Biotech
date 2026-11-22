package com.nobodiiiii.createbiotech.content.biocomputing.device.carrier;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.CarrierHardwareConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierClock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** A witch's optional wired clock stage. Independent of its passive receive filter and of program execution. */
public final class WitchCarrierStage {
    public static final String ROOT = "BiocomputingWitchCarrier";
    public enum Mode {
        OFF, DIVIDE, MULTIPLY;
        public String id() { return name().toLowerCase(Locale.ROOT); }
        private static Mode parse(String value) {
            for (var mode : values()) if (mode.id().equals(value)) return mode;
            throw new IllegalArgumentException("invalid_witch_carrier");
        }
    }

    public record Setting(Mode mode, Optional<Direction> input, Optional<Direction> output, int factor) {
        public static final Setting OFF = new Setting(Mode.OFF, Optional.empty(), Optional.empty(), 1);
        public Setting {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(input);
            Objects.requireNonNull(output);
            if (mode == Mode.OFF ? input.isPresent() || output.isPresent() || factor != 1
                    : input.isEmpty() || output.isEmpty() || input.equals(output) || factor < 1)
                throw new IllegalArgumentException("invalid_witch_carrier");
        }

        private static Optional<Direction> face(String name) {
            if ("none".equals(name)) return Optional.empty();
            for (var face : Direction.values()) if (face.getSerializedName().equals(name)) return Optional.of(face);
            throw new IllegalArgumentException("invalid_witch_carrier");
        }

        private static String name(Optional<Direction> face) {
            return face.map(Direction::getSerializedName).orElse("none");
        }

        public DataValue.RecordValue encode() {
            return new DataValue.RecordValue(Map.of("mode", new DataValue.TextValue(mode.id()),
                "input", new DataValue.TextValue(name(input)), "output", new DataValue.TextValue(name(output)),
                "factor", new DataValue.IntegerValue(factor)));
        }

        public static Setting decode(DataValue.RecordValue raw) {
            if (raw.fields().keySet().equals(Set.of("mode", "input", "output", "factor"))
                    && raw.field("mode") instanceof DataValue.TextValue mode
                    && raw.field("input") instanceof DataValue.TextValue input
                    && raw.field("output") instanceof DataValue.TextValue output
                    && raw.field("factor") instanceof DataValue.IntegerValue factor
                    && factor.value() >= 1 && factor.value() <= Integer.MAX_VALUE)
                return new Setting(Mode.parse(mode.value()), face(input.value()), face(output.value()), (int) factor.value());
            throw new IllegalArgumentException("invalid_witch_carrier");
        }
    }

    private Setting setting = Setting.OFF;
    private CarrierConverterState converter;
    private Tag invalid;

    public Setting setting() { return setting; }
    public boolean valid() { return invalid == null; }

    /** Start a new physical lock on a mode, face or factor change. Never count elapsed wall/world time. */
    public boolean configure(Setting next, CarrierHardwareConfig.Limits limits) {
        if (!valid()) {
            if (!Setting.OFF.equals(next)) throw new IllegalArgumentException("invalid_witch_carrier");
            read(null); // Explicit local reset is the only edit allowed on an opaque save.
            return true;
        }
        if (next.factor() > limits.maxFactor()) throw new IllegalArgumentException("carrier_factor_out_of_range");
        if (setting.equals(next)) return false;
        var replacement = next.mode() == Mode.OFF ? null : new CarrierConverterState(next.mode() == Mode.MULTIPLY);
        if (replacement != null) replacement.setFactor(next.factor(), limits);
        converter = replacement;
        setting = next;
        return true;
    }

    public boolean advance(long tick, CarrierClock.Output input, CarrierHardwareConfig.Limits limits) {
        return converter != null && converter.advance(tick, input, limits);
    }
    public CarrierClock.Output output() {
        return converter != null ? converter.output() : CarrierClock.Output.unavailable(CarrierClock.State.UNAVAILABLE);
    }

    public Tag write() {
        if (!valid()) return invalid.copy();
        if (converter == null) return null;
        var tag = new CompoundTag();
        tag.putInt("Version", 1);
        tag.putString("Mode", setting.mode().id());
        tag.putString("Input", Setting.name(setting.input()));
        tag.putString("Output", Setting.name(setting.output()));
        tag.put("Converter", converter.write());
        return tag;
    }

    public void read(Tag raw) {
        setting = Setting.OFF;
        converter = null;
        invalid = null;
        if (raw == null) return;
        try {
            if (!(raw instanceof CompoundTag tag) || !tag.getAllKeys().equals(Set.of("Version","Input","Output","Mode","Converter"))
                    || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
                    || !tag.contains("Input", Tag.TAG_STRING) || !tag.contains("Output", Tag.TAG_STRING)
                    || !tag.contains("Mode", Tag.TAG_STRING) || !tag.contains("Converter", Tag.TAG_COMPOUND))
                throw new IllegalArgumentException("invalid_witch_carrier");
            var mode = Mode.parse(tag.getString("Mode"));
            if (mode == Mode.OFF) throw new IllegalArgumentException("invalid_witch_carrier");
            var saved = tag.getCompound("Converter");
            var restored = new CarrierConverterState(mode == Mode.MULTIPLY);
            restored.read(saved);
            if (!restored.valid()) throw new IllegalArgumentException("invalid_witch_carrier");
            setting = new Setting(mode, Setting.face(tag.getString("Input")), Setting.face(tag.getString("Output")),
                saved.getInt("Factor"));
            converter = restored;
        } catch (IllegalArgumentException malformed) {
            invalid = raw.copy();
        }
    }
}
