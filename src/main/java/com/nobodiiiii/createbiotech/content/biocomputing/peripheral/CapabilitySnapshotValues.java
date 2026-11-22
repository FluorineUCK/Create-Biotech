package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/** Getter-only projections. Never exposes handlers or retains their mutable stacks. */
public final class CapabilitySnapshotValues {
    private CapabilitySnapshotValues() {}

    public static DataValue itemDescription(ItemStack stack, HolderLookup.Provider registries, ReadLimits limits) {
        return guarded(() -> new DataValue.RecordValue(Map.of(
            "item", new DataValue.TextValue(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()),
            "count", integer(stack.getCount()),
            "components", components(stack.getComponents(), registries, limits))), limits);
    }

    public static DataValue inventory(IItemHandler handler, HolderLookup.Provider registries, ReadLimits limits) {
        if (handler == null) return unsupported();
        return guarded(() -> {
            int slots = handler.getSlots();
            checkCount(slots, limits);
            var values = new ArrayList<DataValue>(slots);
            var budget = new SnapshotLimits.Budget(limits);
            budget.node();
            long units = 1;
            for (int slot = 0; slot < slots; slot++) {
                var stack = handler.getStackInSlot(slot);
                var description = new DataValue.RecordValue(Map.of(
                    "slot", integer(slot),
                    "item", new DataValue.TextValue(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()),
                    "count", integer(stack.getCount()),
                    "components", components(stack.getComponents(), registries, limits)));
                if (description.storageUnits() > limits.maxValueUnits() - units) throw SnapshotLimits.exceeded();
                units += description.storageUnits();
                SnapshotLimits.countValue(description, budget);
                values.add(description);
            }
            return new DataValue.ListValue(values);
        }, limits);
    }

    /** Opt-in item identity/tag metadata. No counts, mutable stack references, or authority. */
    public static DataValue inventoryItemTags(IItemHandler handler, ReadLimits limits) {
        return inventoryItemTags(handler, limits, null);
    }
    public static DataValue inventoryItemTags(IItemHandler handler,ReadLimits limits, ReadRange range) {
        if (range != null && !range.fits(limits)) return error("snapshot_limit");
        if (handler == null) return unsupported();
        return guarded(() -> {
            int slots = handler.getSlots();
            if (slots < 0) throw new IllegalArgumentException("Negative provider entry count");
            if (range == null) checkCount(slots, limits);
            else if (range.offset() > slots) return error("invalid_range");
            int first = range == null ? 0 : range.offset();
            int end = range == null ? slots : Math.min(slots, Math.addExact(first, range.count()));
            var values = new ArrayList<DataValue>();
            var budget = new SnapshotLimits.Budget(limits);
            budget.node();
            long units = 1;
            for (int slot = first; slot < end; slot++) {
                // Admit the descriptor's fixed structure before consulting the provider.
                budget.children(4);
                for (int node = 0; node < 4; node++) budget.node();
                budget.text("slot"); budget.text("item"); budget.text("tags");
                var stack = handler.getStackInSlot(slot);
                var id = BuiltInRegistries.ITEM.getKey(stack.isEmpty()
                    ? net.minecraft.world.item.Items.AIR : stack.getItem());
                long idChars = (long)id.getNamespace().length() + 1 + id.getPath().length();
                budget.textLength(idChars);
                long descriptorUnits = 19 + idChars;
                if (descriptorUnits > limits.maxValueUnits() - units) throw SnapshotLimits.exceeded();
                units += descriptorUnits;
                var tags = new ArrayList<String>();
                if (!stack.isEmpty()) {
                    try (var stream = stack.getTags()) {
                        var iterator = stream.iterator();
                        while (iterator.hasNext()) {
                            // Enumeration is lazy and bounded BEFORE string allocation, collection or sorting.
                            budget.node();
                            var tag = iterator.next().location();
                            long chars = (long)tag.getNamespace().length() + 1 + tag.getPath().length();
                            budget.textLength(chars);
                            if (1 + chars > limits.maxValueUnits() - units) throw SnapshotLimits.exceeded();
                            units += 1 + chars;
                            tags.add(tag.toString());
                        }
                    }
                }
                tags.sort(String::compareTo);
                var copiedTags = new ArrayList<DataValue>(tags.size());
                for (String tag : tags) copiedTags.add(new DataValue.TextValue(tag));
                values.add(new DataValue.RecordValue(Map.of("slot", integer(slot),
                    "item", new DataValue.TextValue(id.toString()), "tags", new DataValue.ListValue(copiedTags))));
            }
            var items = new DataValue.ListValue(values);
            return range == null ? items : range.page(slots, items);
        }, limits);
    }

    public static DataValue fluid(IFluidHandler handler, HolderLookup.Provider registries, ReadLimits limits) {
        if (handler == null) return unsupported();
        return guarded(() -> {
            int tanks = handler.getTanks();
            checkCount(tanks, limits);
            var values = new ArrayList<DataValue>(tanks);
            var budget = new SnapshotLimits.Budget(limits);
            budget.node();
            long units = 1;
            for (int tank = 0; tank < tanks; tank++) {
                var stack = handler.getFluidInTank(tank);
                var description = new DataValue.RecordValue(Map.of(
                    "tank", integer(tank),
                    "fluid", new DataValue.TextValue(BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString()),
                    "amount", integer(stack.getAmount()),
                    "capacity", integer(handler.getTankCapacity(tank)),
                    "components", components(stack.getComponents(), registries, limits)));
                if (description.storageUnits() > limits.maxValueUnits() - units) throw SnapshotLimits.exceeded();
                units += description.storageUnits();
                SnapshotLimits.countValue(description, budget);
                values.add(description);
            }
            return new DataValue.ListValue(values);
        }, limits);
    }

    public static DataValue energy(IEnergyStorage handler, ReadLimits limits) {
        if (handler == null) return unsupported();
        return guarded(() -> new DataValue.RecordValue(Map.of(
            "stored", integer(handler.getEnergyStored()),
            "capacity", integer(handler.getMaxEnergyStored()))), limits);
    }

    private static DataValue components(DataComponentMap components, HolderLookup.Provider registries, ReadLimits limits) {
        checkCount(components.size(), limits);
        var tag = DataComponentMap.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), components)
            .getOrThrow();
        return NbtSnapshotValues.copy(tag, limits);
    }

    private static void checkCount(int count, ReadLimits limits) {
        if (count < 0) throw new IllegalArgumentException("Negative provider entry count");
        if (count > limits.maxEntries()) throw SnapshotLimits.exceeded();
    }

    static DataValue guarded(Supplier<? extends DataValue> action, ReadLimits limits) {
        try {
            DataValue value = action.get();
            SnapshotLimits.validate(value, limits);
            return value;
        } catch (SnapshotLimits.LimitExceeded exceeded) {
            return error("snapshot_limit");
        } catch (RuntimeException | StackOverflowError failure) {
            // Provider/codec diagnostics may contain private data; expose only a stable error code.
            return error("provider_failure");
        }
    }

    static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    static DataValue.ErrorValue unsupported() { return error("unsupported"); }
    static DataValue.ErrorValue error(String code) { return new DataValue.ErrorValue(code, code); }
}
