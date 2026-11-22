package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Immutable Create PackageOrderWithCrafts CODEC snapshot, not a catalog row or a dispatch capability.
 * Item registry/component validation belongs to the server capture boundary. Archive validation here
 * checks the bounded native envelope without requiring a running registry or reinterpreting components. */
final class StockNativeOrder {
    private StockNativeOrder() {}
    static DataValue.NbtValue recapture(DataValue.NbtValue value, HolderLookup.Provider registries, StockApplication.Limits limits) {
        validate(value, Math.min(limits.entries(), StockApplicationValues.MAX_MEMBERS));
        var bounds = StockApplicationValues.FORMAT;
        var tag = NativeNbtCodec.toTag(value, new NativeNbtCodec.Limits(Integer.MAX_VALUE,bounds.maxValueUnits(),bounds));
        var order = PackageOrderWithCrafts.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE),tag)
            .result().orElseThrow(StockNativeOrder::invalid);
        var canonical = capture(order, registries, limits);
        if (!canonical.equals(value)) throw invalid();
        return canonical;
    }
    static DataValue.NbtValue capture(PackageOrderWithCrafts order, HolderLookup.Provider registries, StockApplication.Limits limits) {
        int maximum = Math.min(limits.entries(), StockApplicationValues.MAX_MEMBERS);
        long entries = order.stacks().size();
        if (order.isEmpty()) throw invalid();
        if (entries > maximum || order.orderedCrafts().size() > maximum) throw new IllegalArgumentException("capacity");
        for (var craft : order.orderedCrafts()) {
            entries += craft.pattern().stacks().size();
            if (entries > maximum) throw new IllegalArgumentException("capacity");
        }
        if (RetiredStockOrders.containsToken(order)) throw new IllegalArgumentException(RetiredStockOrders.CODE);
        // Validate components without replacing them by names or mutating the caller's ItemStacks.
        var stacks = new ArrayList<>(order.stacks());
        order.orderedCrafts().forEach(craft -> stacks.addAll(craft.pattern().stacks()));
        for (var item : stacks) if (!item.stack.isEmpty() && ItemStack.validateComponents(item.stack.getComponents()).error().isPresent()) throw invalid();
        var tag = PackageOrderWithCrafts.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), order)
            .result().orElseThrow(StockNativeOrder::invalid);
        var bounds = StockApplicationValues.FORMAT;
        var nbt = NativeNbtCodec.snapshot(tag, new NativeNbtCodec.Limits(Integer.MAX_VALUE, bounds.maxValueUnits(), bounds));
        validate(nbt, maximum);
        StockApplicationValues.checkText(nbt, Math.min(limits.textChars(), StockApplicationValues.MAX_TEXT));
        if (nbt.storageUnits() > Math.min(limits.retainedUnits(), bounds.maxValueUnits())) throw new IllegalArgumentException("capacity");
        return nbt;
    }
    static void validate(DataValue.NbtValue value, int maxEntries) {
        var root = compound(value);
        if (!root.fields().keySet().equals(Set.of("ordered_stacks", "ordered_crafts"))) throw invalid();
        var items = entries(root.field("ordered_stacks"), maxEntries, false);
        if (items.isEmpty()) throw invalid();
        var crafts = list(root.field("ordered_crafts"), maxEntries);
        long count = items.size();
        for (var v : crafts) {
            var craft = compound(v);
            if (!craft.fields().keySet().equals(Set.of("pattern", "count"))) throw invalid();
            integer(craft.field("count"), 1);
            var pattern = entries(craft.field("pattern"), maxEntries, true);
            if (pattern.isEmpty()) throw invalid();
            count += pattern.size();
            if (count > maxEntries) throw new IllegalArgumentException("capacity");
        }
    }
    private static List<DataValue> entries(DataValue value, int max, boolean pattern) {
        var order = compound(value);
        if (!order.fields().keySet().equals(Set.of("entries"))) throw invalid();
        var entries = list(order.field("entries"), max);
        for (var v : entries) {
            var item = compound(v);
            if (!item.fields().keySet().equals(Set.of("item_stack", "count"))) throw invalid();
            var stack = compound(item.field("item_stack"));
            if (!pattern && stack.fields().isEmpty()) throw invalid();
            integer(item.field("count"), pattern ? 0 : 1);
        }
        return entries;
    }
    private static DataValue.RecordValue compound(DataValue v) {
        if (!(v instanceof DataValue.NbtValue n) || n.tagId() != 10 || !(n.value() instanceof DataValue.RecordValue r)) throw invalid();
        return r;
    }
    private static List<DataValue> list(DataValue v, int max) {
        if (!(v instanceof DataValue.NbtValue n) || n.tagId() != 9 || !(n.value() instanceof DataValue.ListValue l)) throw invalid();
        if (l.values().size() > max) throw new IllegalArgumentException("capacity");
        return l.values();
    }
    private static void integer(DataValue v, int minimum) {
        if (!(v instanceof DataValue.NbtValue n) || n.tagId() != 3 || !(n.value() instanceof DataValue.IntegerValue i) || i.value() < minimum) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_native_order"); }
}
