package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;

/** Exact item descriptions for checked packaging requests. Decoding creates descriptions, never world stacks. */
public final class ProductionMaterials {
    private ProductionMaterials() {}
    public static Map<StackKey, Long> decode(DataValue.RecordValue data, HolderLookup.Provider registries,
                                             NativeNbtCodec.Limits limits, int maxEntries) {
        if (!data.fields().keySet().equals(Set.of("items")) || !(data.field("items") instanceof DataValue.ListValue list)
            || list.values().isEmpty() || list.values().size() > maxEntries) throw invalid();
        var result = new LinkedHashMap<StackKey, Long>();
        for (var value : list.values()) {
            if (!(value instanceof DataValue.RecordValue row)
                || !(row.fields().keySet().equals(Set.of("item", "count")) || row.fields().keySet().equals(Set.of("item", "count", "components")))
                || !(row.field("item") instanceof DataValue.TextValue item) || !(row.field("count") instanceof DataValue.IntegerValue count)
                || count.value() <= 0) throw invalid();
            var id = ResourceLocation.tryParse(item.value());
            if (id == null || !id.toString().equals(item.value()) || !BuiltInRegistries.ITEM.containsKey(id)) throw invalid();
            var stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
            if (stack.isEmpty()) throw invalid();
            if (row.fields().containsKey("components")) {
                if (!(row.field("components") instanceof DataValue.NbtValue nativeTag) || nativeTag.tagId() != 10) throw invalid();
                var patch = DataComponentPatch.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), NativeNbtCodec.toTag(nativeTag, limits))
                    .result().orElseThrow(ProductionMaterials::invalid);
                // The vanilla convenience method silently restores the old patch on failure.
                // Material matching must reject invalid descriptions, never dispatch plain items instead.
                stack.applyComponents(patch);
                if (ItemStack.validateComponents(stack.getComponents()).error().isPresent()) throw invalid();
            }
            result.merge(new StackKey(stack), count.value(), Math::addExact);
        }
        long entries = 0;
        for (long count : result.values()) {
            entries += 1 + (count - 1) / Integer.MAX_VALUE;
            if (entries > maxEntries) throw new IllegalArgumentException("capacity");
        }
        return Collections.unmodifiableMap(result);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_material"); }
}
