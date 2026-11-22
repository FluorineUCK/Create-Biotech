package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** One passive output face, with no oscillator, serial endpoint or program-ISA dependency. */
public final class WitchReceiverFilter {
    public static final String ROOT = "BiocomputingWitchReceiverFilter";
    private static final ResourceLocation WITCH = ResourceLocation.withDefaultNamespace("witch");
    public static boolean supports(HardwareSelection resident) {
        return resident.ready() && resident.resident().filter(r -> WITCH.equals(r.entityType())).isPresent();
    }
    public enum Width {
        NARROW, STANDARD, WIDE;
        public String id() { return name().toLowerCase(Locale.ROOT); }
        public Width next() { return values()[(ordinal()+1)%values().length]; }
        public RadioSerialNetwork.Radio apply(RadioSerialNetwork.Radio radio, int narrowLimit) {
            int base = radio.filter().width();
            int width = switch (this) {
                case NARROW -> Math.min(base, narrowLimit);
                case STANDARD -> base;
                case WIDE -> (int)Math.min(Integer.MAX_VALUE, 2L * base);
            };
            return radio.withReceiveFilter(new RadioSerialNetwork.Band(radio.filter().center(), width));
        }
    }
    public record Setting(Optional<Direction> output, Width width) {
        public static final Setting OFF = new Setting(Optional.empty(),Width.STANDARD);
        public Setting { Objects.requireNonNull(output); Objects.requireNonNull(width); }
        private String outputName() { return output.map(Direction::getSerializedName).orElse("none"); }
        public DataValue.RecordValue encode() {
            return new DataValue.RecordValue(Map.of("output",new DataValue.TextValue(outputName()),
                "width",new DataValue.TextValue(width.id())));
        }
        public static Setting decode(DataValue.RecordValue fields) {
            if (fields.fields().keySet().equals(Set.of("output","width"))
                && fields.field("output") instanceof DataValue.TextValue f && fields.field("width") instanceof DataValue.TextValue w)
                return parse(f.value(),w.value());
            throw new IllegalArgumentException("invalid_witch_filter");
        }
        private static Setting parse(String f,String w) {
            for (Width width : Width.values()) if (width.id().equals(w)) {
                if (f.equals("none")) return new Setting(Optional.empty(),width);
                for (Direction face : Direction.values()) if (face.getSerializedName().equals(f))
                    return new Setting(Optional.of(face),width);
            }
            throw new IllegalArgumentException("invalid_witch_filter");
        }
    }
    private boolean rewire;
    public boolean needsRewire() { return rewire; }
    private Tag saved;
    private Setting setting = Setting.OFF;
    private boolean valid = true;
    public Setting setting() { return setting; }
    public boolean valid() { return valid; }
    public Tag write() { return saved == null ? null : saved.copy(); }
    public void configure(Setting next) {
        if (!valid) throw new IllegalArgumentException("invalid_witch_filter");
        var tag = new CompoundTag();
        tag.putInt("Version",2);
        tag.putString("Output",next.outputName());
        tag.putString("Width",next.width().id());
        read(tag);
    }
    public void read(Tag raw) {
        saved = raw == null ? null : raw.copy();
        setting = Setting.OFF;
        rewire = false;
        valid = raw == null;
        if (!(raw instanceof CompoundTag tag) || !tag.getAllKeys().equals(Set.of("Version","Output","Width"))
            || !tag.contains("Version", Tag.TAG_INT) || (tag.getInt("Version") != 1 && tag.getInt("Version") != 2)
            || !tag.contains("Width",Tag.TAG_STRING) || !tag.contains("Output",Tag.TAG_STRING)) return;
        try { setting = Setting.parse(tag.getString("Output"),tag.getString("Width")); valid = true; rewire = tag.getInt("Version") == 1 && setting.output().isPresent(); }
        catch (IllegalArgumentException invalid) { /* Preserve opaque save; do not drive a receiver. */ }
    }
}
