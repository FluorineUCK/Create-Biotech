package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Copies only data, with a hard stack-safety ceiling in addition to server budgets. */
public final class NbtSnapshotValues {
    private static final int MAX_DEPTH = 128;
    private NbtSnapshotValues() {}

    public static DataValue copy(Tag tag, ReadLimits limits) {
        var result = copy(Objects.requireNonNull(tag), new SnapshotLimits.Budget(limits), limits, 0);
        SnapshotLimits.validate(result, limits);
        return result;
    }

    private static DataValue copy(Tag tag, SnapshotLimits.Budget budget, ReadLimits limits, int depth) {
        if (depth > MAX_DEPTH) throw SnapshotLimits.exceeded();
        budget.node();
        DataValue result;
        if (tag instanceof CompoundTag compound) {
            budget.children(compound.size());
            var fields = new LinkedHashMap<String, DataValue>();
            for (String key : compound.getAllKeys()) {
                budget.text(key);
                if (key.equals(com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryServiceItemData.COMPONENT_ID)) {
                    var denied = new DataValue.ErrorValue("access_denied", "access_denied");
                    SnapshotLimits.countValue(denied, budget);
                    fields.put(key, denied);
                } else fields.put(key, copy(Objects.requireNonNull(compound.get(key)), budget, limits, depth + 1));
            }
            result = new DataValue.RecordValue(fields);
        } else if (tag instanceof CollectionTag<?> list) {
            budget.children(list.size());
            var values = new ArrayList<DataValue>(list.size());
            for (Tag child : list) values.add(copy(child, budget, limits, depth + 1));
            result = new DataValue.ListValue(values);
        } else if (tag instanceof StringTag text) {
            String value = text.getAsString();
            budget.text(value);
            result = new DataValue.TextValue(value);
        } else if (tag instanceof NumericTag number) {
            result = switch (tag.getId()) {
                case Tag.TAG_BYTE, Tag.TAG_SHORT, Tag.TAG_INT, Tag.TAG_LONG -> new DataValue.IntegerValue(number.getAsLong());
                case Tag.TAG_FLOAT, Tag.TAG_DOUBLE -> new DataValue.DecimalValue(number.getAsDouble());
                default -> throw new IllegalArgumentException("Unsupported numeric NBT type");
            };
        } else throw new IllegalArgumentException("Unsupported NBT type");
        if (result.storageUnits() > limits.maxValueUnits()) throw SnapshotLimits.exceeded();
        return result;
    }
}
