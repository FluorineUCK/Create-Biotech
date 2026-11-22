package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import java.util.HashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Wire-only deduplication of a flat table already validated and budgeted by ValueNbtCodec.encode. */
final class EditorValueTableCompaction {
    private EditorValueTableCompaction() {}

    static CompoundTag compact(CompoundTag encoded) {
        var source = encoded.getList("Values", Tag.TAG_COMPOUND);
        int[] references = new int[source.size()];
        var rows = new ListTag();
        var names = new ListTag();
        var nameIds = new HashMap<Tag, Integer>();
        // Each key is a shallow row with integer references, never a recursively expanded value.
        var ids = new HashMap<CompoundTag, Integer>();
        for (int index = 0; index < source.size(); index++) {
            var row = source.getCompound(index).copy();
            switch (row.getString("Kind")) {
                case "nbt", "recipe" -> row.putInt("Value", references[row.getInt("Value")]);
                case "list" -> {
                    int[] items = row.getIntArray("Items");
                    for (int i = 0; i < items.length; i++) items[i] = references[items[i]];
                }
                case "record", "map" -> {
                    // Fields remain an ordered list: equal maps with different orders must not merge.
                    for (var fieldTag : row.getList("Fields", Tag.TAG_COMPOUND)) {
                        var field = (CompoundTag) fieldTag;
                        field.putInt("Value", references[field.getInt("Value")]);
                        var name = field.get("Name");
                        Integer nameId = nameIds.putIfAbsent(name, names.size());
                        if (nameId == null) { nameId = names.size(); names.add(name); }
                        field.putInt("Name", nameId);
                    }
                }
                default -> { }
            }
            Integer existing = ids.putIfAbsent(row, rows.size());
            references[index] = existing == null ? rows.size() : existing;
            if (existing == null) rows.add(row);
        }
        int root = references[encoded.getInt("Root")];
        if (root != rows.size() - 1) throw new IllegalStateException("Compacted root must remain the last value");
        var result = new CompoundTag();
        result.putInt("Version", encoded.getInt("Version"));
        result.putInt("Root", root); result.put("Values", rows);
        result.putInt("WireVersion", 1); result.put("FieldNames", names);
        return result;
    }

    /** Restore the ordinary archive shape before its existing type and logical-budget validation. */
    static CompoundTag expand(CompoundTag encoded) {
        if (!encoded.contains("WireVersion")) return encoded; // Historical unpooled wire archives.
        if (!encoded.contains("WireVersion", Tag.TAG_INT) || encoded.getInt("WireVersion") != 1)
            throw new IllegalArgumentException("Unsupported value wire version");
        var result = encoded.copy();
        var names = list(result, "FieldNames", Tag.TAG_BYTE_ARRAY);
        for (var name : names)
            if (((net.minecraft.nbt.ByteArrayTag) name).getAsByteArray().length % 2 != 0)
                throw new IllegalArgumentException("Invalid UTF-16 field name");
        for (var rowTag : list(result, "Values", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) rowTag;
            if (!row.getString("Kind").equals("record") && !row.getString("Kind").equals("map")) continue;
            for (var fieldTag : list(row, "Fields", Tag.TAG_COMPOUND)) {
                var field = (CompoundTag) fieldTag;
                if (!field.contains("Name", Tag.TAG_INT)) throw new IllegalArgumentException("Invalid field name reference");
                int ref = field.getInt("Name");
                if (ref < 0 || ref >= names.size()) throw new IllegalArgumentException("Invalid field name reference");
                // Reuse owned byte arrays, not expanded copies. ValueNbtCodec budgets each decoded occurrence.
                field.put("Name", names.get(ref));
            }
        }
        result.remove("WireVersion"); result.remove("FieldNames");
        return result;
    }

    private static ListTag list(CompoundTag tag, String key, int elementType) {
        if (!(tag.get(key) instanceof ListTag list) || !list.isEmpty() && list.getElementType() != elementType)
            throw new IllegalArgumentException("Invalid value wire list: " + key);
        return list;
    }
}
