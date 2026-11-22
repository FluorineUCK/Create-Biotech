package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import com.nobodiiiii.createbiotech.registry.CBDataComponents;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.content.logistics.filter.ListFilterItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterRule.Domain;

/** The physical outer Filter owns rule data only. No virtual entry is inserted into Create's sample handler. */
public final class VirtualListFilter {
    private static final ArchiveLimits RULE_FORMAT = new ArchiveLimits(512, 32_768, 32_768);
    private static final ArchiveLimits LIST_FORMAT = new ArchiveLimits(9216, 64 * 1024, 64 * 1024);
    private static final int MAX_SLOTS = 18; // Native ListFilterItem#getFilterItemHandler format.
    // Guard the added virtual work across a native list tree, without changing native-only filtering.
    private static final int MAX_VIRTUAL_DEPTH = 8;
    private static final int MAX_VIRTUAL_ENTRIES = MAX_SLOTS * 2;
    private VirtualListFilter() {}

    private record Entry(int index, DataValue.RecordValue data, VirtualFilterRule compiled) {}
    private record State(UUID identity, long revision, List<Entry> entries) {
        State {
            entries = entries.stream().sorted(Comparator.comparingInt(Entry::index)).toList();
        }
    }

    static long revision(ItemStack stack) { return read(stack).revision(); }
    static UUID identity(ItemStack stack) {
        try { return read(stack).identity(); }
        catch (IllegalArgumentException invalid) { return null; }
    }
    static UUID ensureIdentity(ItemStack stack) {
        var before = read(stack);
        if (before.identity() != null) return before.identity();
        var id = UUID.randomUUID();
        write(stack, new State(id, before.revision(), before.entries()));
        return id;
    }
    static boolean supports(ItemStack stack) { return AllItems.FILTER.isIn(stack); }
    static boolean emptyAt(ItemStack stack, int index) {
        var slots = slots(stack);
        return index >= 0 && index < slots.getSlots() && slots.getStackInSlot(index).isEmpty();
    }

    static String set(ItemStack stack, int index, long expected, DataValue.RecordValue rule, int limit) {
        return change(stack, index, expected, Optional.of(rule), limit);
    }

    static String clear(ItemStack stack, int index, long expected) {
        return change(stack, index, expected, Optional.empty(), 1);
    }

    private static String change(ItemStack stack, int index, long expected, Optional<DataValue.RecordValue> rule, int limit) {
        if (!AllItems.FILTER.isIn(stack)) return "unsupported_filter_target";
        var slots = slots(stack);
        if (index < 0 || index >= slots.getSlots() || index >= MAX_SLOTS) return "invalid_filter_slot";
        if (!slots.getStackInSlot(index).isEmpty()) return "filter_slot_occupied";
        try {
            var before = read(stack);
            if (expected != before.revision()) return "version_conflict";
            if (before.revision() == Long.MAX_VALUE) return "version_exhausted";
            var after = new TreeMap<Integer, Entry>();
            before.entries().forEach(entry -> after.put(entry.index(), entry));
            if (rule.isEmpty() && after.remove(index) == null) return "empty";
            if (rule.isPresent()) {
                var value = rule.orElseThrow();
                after.put(index, new Entry(index, value, VirtualFilterRule.compile(value, limit, VirtualFilterTarget.REGISTRY)));
            }
            write(stack, new State(before.identity() == null ? UUID.randomUUID() : before.identity(), before.revision() + 1, List.copyOf(after.values())));
            return rule.isPresent() ? "applied" : "cleared";
        } catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
    }

    /** An actual native edit takes precedence. Keep a revision even if the final virtual entry is removed. */
    public static void reconcile(ItemStack stack) {
        if (!AllItems.FILTER.isIn(stack) || !stack.has(CBDataComponents.BIOCOMPUTING_FILTER_RULES.get())) return;
        try {
            var before = read(stack);
            var slots = slots(stack);
            var kept = before.entries().stream().filter(e -> e.index() < slots.getSlots() && slots.getStackInSlot(e.index()).isEmpty()).toList();
            // A manual change to the mode or another native entry must fence previous writers too.
            write(stack, new State(before.identity(), before.revision() == Long.MAX_VALUE ? Long.MAX_VALUE : before.revision() + 1, kept));
        } catch (IllegalArgumentException malformed) { /* Retain the invalid data and never turn it into an empty filter. */ }
    }

    public interface Wrapped {
        Compiled biocomputingVirtualEntries();
    }

    public static final class Compiled {
        private final boolean invalid;
        private final boolean blacklist;
        private final List<VirtualFilterRule> entries;
        private final List<VirtualFilterRule> validatedTree;
        private final int depth;
        private Compiled(boolean invalid, boolean blacklist, List<VirtualFilterRule> entries, List<VirtualFilterRule> validatedTree, int depth) {
            this.invalid = invalid; this.blacklist = blacklist; this.entries = List.copyOf(entries);
            this.validatedTree = List.copyOf(validatedTree); this.depth = depth;
        }
        public Optional<Boolean> test(ItemStack stack) {
            return evaluate(Domain.ITEM, stack.isEmpty(), rule -> rule.matches(Domain.ITEM, BuiltInRegistries.ITEM.getKey(stack.getItem()),
                name -> stack.is(TagKey.create(Registries.ITEM, name))));
        }
        public Optional<Boolean> test(FluidStack stack) {
            return evaluate(Domain.FLUID, stack.isEmpty(), rule -> rule.matches(Domain.FLUID, BuiltInRegistries.FLUID.getKey(stack.getFluid()),
                name -> stack.getFluid().is(TagKey.create(Registries.FLUID, name))));
        }
        private Optional<Boolean> evaluate(Domain domain, boolean empty, Predicate<VirtualFilterRule> matches) {
            if (!valid()) return Optional.of(false);
            boolean listed = false;
            for (var rule : entries) {
                if (rule.domain() != domain) continue;
                if (!empty && matches.test(rule)) listed = true;
            }
            return listed ? Optional.of(!blacklist) : Optional.empty(); // Let Create evaluate ordinary samples and the outer mode.
        }
        private boolean valid() {
            return !invalid && validatedTree.stream().allMatch(rule -> rule.referencesAvailable(VirtualFilterTarget.REGISTRY));
        }
    }

    public static Compiled compile(ItemStack stack, List<FilterItemStack> children) {
        try {
            List<VirtualFilterRule> local = List.of();
            if (stack.has(CBDataComponents.BIOCOMPUTING_FILTER_RULES.get())) {
                var slots = slots(stack);
                local = read(stack).entries().stream().filter(e -> e.index() < slots.getSlots() && slots.getStackInSlot(e.index()).isEmpty())
                    .map(Entry::compiled).toList();
            }
            var tree = new ArrayList<>(local);
            int depth = local.isEmpty() ? 0 : 1;
            for (var child : children) {
                if (!(child instanceof Wrapped wrapped) || wrapped.biocomputingVirtualEntries() == null) continue;
                var sub = wrapped.biocomputingVirtualEntries();
                if (sub.invalid || sub.depth >= MAX_VIRTUAL_DEPTH || tree.size() + sub.validatedTree.size() > MAX_VIRTUAL_ENTRIES)
                    return invalidTree();
                depth = Math.max(depth, sub.depth + 1);
                tree.addAll(sub.validatedTree);
            }
            return tree.isEmpty() ? null : new Compiled(false, stack.getOrDefault(AllDataComponents.FILTER_ITEMS_BLACKLIST, false), local, tree, depth);
        } catch (IllegalArgumentException invalid) { return invalidTree(); }
    }

    private static Compiled invalidTree() { return new Compiled(true, false, List.of(), List.of(), 0); }

    private static State read(ItemStack stack) {
        var component = stack.get(CBDataComponents.BIOCOMPUTING_FILTER_RULES.get());
        if (component == null) return new State(null, 0, List.of());
        var root = component.copyTag();
        if (!root.getAllKeys().equals(Set.of("Format", "Identity", "Revision", "Entries")) || !root.contains("Format", Tag.TAG_INT)
            || root.getInt("Format") != 1 || !root.hasUUID("Identity") || !root.contains("Revision", Tag.TAG_LONG) || root.getLong("Revision") < 0
            || !(root.get("Entries") instanceof ListTag rows) || !rows.isEmpty() && rows.getElementType() != Tag.TAG_COMPOUND
            || rows.size() > MAX_SLOTS || root.getLong("Revision") == 0 && !rows.isEmpty()) throw malformed();
        long units = 0;
        var result = new ArrayList<Entry>();
        var indexes = new java.util.HashSet<Integer>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.getCompound(i);
            int index = row.getInt("Slot");
            if (!row.getAllKeys().equals(Set.of("Slot", "Rule")) || !row.contains("Slot", Tag.TAG_INT)
                || index < 0 || index >= MAX_SLOTS || !indexes.add(index) || !row.contains("Rule", Tag.TAG_COMPOUND))
                throw malformed();
            if (!(ValueNbtCodec.decode(row.getCompound("Rule"), RULE_FORMAT) instanceof DataValue.RecordValue value)) throw malformed();
            units += value.storageUnits();
            if (units > LIST_FORMAT.maxValueUnits()) throw malformed();
            var rule = VirtualFilterRule.compile(value, VirtualFilterRule.MAX_MATCHERS, (d, k, id) -> true);
            result.add(new Entry(index, value, rule));
        }
        return new State(root.getUUID("Identity"), root.getLong("Revision"), result);
    }

    private static void write(ItemStack stack, State state) {
        var root = new CompoundTag();
        root.putInt("Format", 1);
        root.putUUID("Identity", state.identity());
        root.putLong("Revision", state.revision());
        var rows = new ListTag();
        long units = 0;
        for (var entry : state.entries()) {
            units += entry.data().storageUnits();
            if (units > LIST_FORMAT.maxValueUnits()) throw new IllegalArgumentException("filter_capacity");
            var row = new CompoundTag();
            row.putInt("Slot", entry.index());
            row.put("Rule", ValueNbtCodec.encode(entry.data(), RULE_FORMAT));
            rows.add(row);
        }
        root.put("Entries", rows);
        stack.set(CBDataComponents.BIOCOMPUTING_FILTER_RULES.get(), CustomData.of(root));
    }

    private static ItemStackHandler slots(ItemStack stack) {
        if (!(stack.getItem() instanceof ListFilterItem list)) throw malformed();
        return list.getFilterItemHandler(stack);
    }

    private static IllegalArgumentException malformed() { return new IllegalArgumentException("invalid_filter_archive"); }
}
