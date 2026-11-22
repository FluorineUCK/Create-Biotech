package com.nobodiiiii.createbiotech.content.biocomputing.device.label;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

/** World-axis displacement in blocks from the normal label anchor; never a target position. */
public record LabelOffset(double x, double y, double z) {
    public static final LabelOffset ZERO = new LabelOffset(0, 0, 0);
    private static final int MAX_AXIS = 8;
    private static final Set<String> AXES = Set.of("x", "y", "z");

    public LabelOffset {
        if (!(Math.abs(x) <= MAX_AXIS && Math.abs(y) <= MAX_AXIS && Math.abs(z) <= MAX_AXIS))
            throw new IllegalArgumentException("invalid_offset");
    }

    Vec3 anchor() { return new Vec3(0.5 + x, 1.35 + y, 0.5 + z); }

    static LabelOffset parse(DataValue value) {
        if (!(value instanceof DataValue.RecordValue r) || !r.fields().keySet().equals(AXES))
            throw new IllegalArgumentException("invalid_offset");
        return new LabelOffset(axis(r.field("x")), axis(r.field("y")), axis(r.field("z")));
    }

    private static double axis(DataValue v) {
        if (v instanceof DataValue.IntegerValue i) return i.value();
        if (v instanceof DataValue.DecimalValue d) return d.value();
        throw new IllegalArgumentException("invalid_offset");
    }

    DataValue.RecordValue value() {
        return new DataValue.RecordValue(Map.of("x", new DataValue.DecimalValue(x),
            "y", new DataValue.DecimalValue(y), "z", new DataValue.DecimalValue(z)));
    }

    CompoundTag save() {
        var tag = new CompoundTag();
        tag.putDouble("x", x); tag.putDouble("y", y); tag.putDouble("z", z);
        return tag;
    }

    static LabelOffset load(Tag raw) {
        if (!(raw instanceof CompoundTag tag) || !tag.getAllKeys().equals(AXES)
            || !AXES.stream().allMatch(k -> tag.contains(k, Tag.TAG_DOUBLE)))
            throw new IllegalArgumentException("invalid_offset");
        return new LabelOffset(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
    }
}
