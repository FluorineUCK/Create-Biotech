package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Registry adaptation is confined to the client, not the terminal model or projector. */
public record TerminalItemDisplay(ItemStack stack, String id, long count, Optional<String> problem) {
    public static TerminalItemDisplay resolve(DataValue value) {
        if (!(value instanceof DataValue.RecordValue record) || !(record.fields().get("item") instanceof DataValue.TextValue item)
            || !(record.fields().get("count") instanceof DataValue.IntegerValue count) || count.value() < 0)
            return new TerminalItemDisplay(ItemStack.EMPTY, "", 0, Optional.of("invalid_item"));
        var id = ResourceLocation.tryParse(item.value());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return new TerminalItemDisplay(ItemStack.EMPTY, item.value(), count.value(), Optional.of("unknown_item"));
        return new TerminalItemDisplay(new ItemStack(BuiltInRegistries.ITEM.get(id), (int)Math.min(count.value(), Integer.MAX_VALUE)), item.value(), count.value(), Optional.empty());
    }
}
