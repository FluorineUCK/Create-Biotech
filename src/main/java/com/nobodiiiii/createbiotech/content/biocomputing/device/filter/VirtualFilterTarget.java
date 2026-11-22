package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.DeployerControl;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.VersionedInventoryTrackerBehaviour;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterRule.Domain;

/** Adapter for real Create slots. It never calls setFilter or owns an ItemStack. */
public final class VirtualFilterTarget {
    public static final int MAX_MATCHERS = VirtualFilterRule.MAX_MATCHERS;
    public static final String ARCHIVE_KEY = "BiocomputingVirtualFilter";
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(512, 32_768, 32_768);
    static final VirtualFilterRule.Registry REGISTRY = (domain, kind, name) -> switch (domain) {
        case ITEM -> kind == VirtualFilterRule.Kind.ID ? BuiltInRegistries.ITEM.containsKey(name)
            : BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, name)).isPresent();
        case FLUID -> kind == VirtualFilterRule.Kind.ID ? BuiltInRegistries.FLUID.containsKey(name)
            : BuiltInRegistries.FLUID.getTag(TagKey.create(Registries.FLUID, name)).isPresent();
    };

    public interface Host {
        VirtualFilterTarget biocomputingVirtualFilter();
    }

    private final FilteringBehaviour filter;
    private final Set<Domain> domains;
    private UUID identity = UUID.randomUUID();
    private UUID menuIdentity = UUID.randomUUID();
    private UUID bindingId;
    private UUID listMount;
    private boolean listUpdate;
    private VirtualFilterSlot slot;
    private Tag invalid;
    private long configurationRevision;

    private VirtualFilterTarget(FilteringBehaviour filter, Domain domain) {
        this(filter, Set.of(domain));
    }

    private VirtualFilterTarget(FilteringBehaviour filter, Set<Domain> domains) {
        this.filter = filter;
        this.domains = domains;
        slot = VirtualFilterSlot.empty(domains, occupied());
    }

    public static VirtualFilterTarget create(FilteringBehaviour filter) {
        var block = filter.blockEntity.getBlockState();
        if (filter.blockEntity instanceof SmartFluidPipeBlockEntity && AllBlocks.SMART_FLUID_PIPE.has(block))
            return new VirtualFilterTarget(filter, Domain.FLUID);
        if (filter.blockEntity instanceof FunnelBlockEntity && (AllBlocks.BRASS_FUNNEL.has(block) || AllBlocks.BRASS_BELT_FUNNEL.has(block)))
            return new VirtualFilterTarget(filter, Domain.ITEM);
        if (filter.blockEntity instanceof DeployerBlockEntity && filter.blockEntity instanceof DeployerControl.Access
            && AllBlocks.DEPLOYER.has(block))
            return new VirtualFilterTarget(filter, Domain.ITEM);
        if (filter.blockEntity instanceof ThresholdSwitchBlockEntity && AllBlocks.THRESHOLD_SWITCH.has(block))
            // The same native slot filters either storage kind. A rule still matches exactly one domain.
            return new VirtualFilterTarget(filter, Set.of(Domain.ITEM, Domain.FLUID));
        return null;
    }

    static Optional<VirtualFilterTarget> of(FilteringBehaviour filter) {
        return filter instanceof Host host ? Optional.ofNullable(host.biocomputingVirtualFilter()) : Optional.empty();
    }

    /** Placement-local witness for devices that reserve work against a particular filter policy. */
    public static long configurationRevision(FilteringBehaviour filter) {
        var target = filter instanceof Host host ? host.biocomputingVirtualFilter() : null;
        return target == null ? 0L : target.configurationRevision;
    }

    long version() { return slot.version(); }
    boolean occupied() { return !filter.getFilter().isEmpty(); }
    Optional<DataValue.RecordValue> snapshot() { return slot.data(); }
    ItemStack nativeStack() { return filter.getFilter(); }
    boolean supportsList() { return VirtualListFilter.supports(nativeStack()); }
    boolean acceptsListRule(DataValue.RecordValue data, int limit) {
        return domains.contains(VirtualFilterRule.compile(data, limit, REGISTRY).domain());
    }
    UUID identity() { return identity; }
    UUID menuIdentity() { return menuIdentity; }
    UUID bindingId() { return bindingId; }
    UUID retainBindingId() {
        if (bindingId == null) {
            bindingId = UUID.randomUUID();
            filter.blockEntity.setChanged();
        }
        return bindingId;
    }
    VirtualFilterSlot state() { return slot; }
    String status() {
        if (!live() || !filter.isActive()) return "target_unavailable";
        return invalid != null ? "invalid_filter_archive" : "ready";
    }

    String writeStatus() {
        return filter.blockEntity instanceof DeployerControl.Access deployer && deployer.biocomputingConfigurationBusy()
            ? "busy" : "ready";
    }

    boolean publish(VirtualFilterSlot before, VirtualFilterSlot after, SerialDevice.Prepared exchange) {
        if (!status().equals("ready") || !writeStatus().equals("ready") || slot != before || occupied() != before.nativeOccupied() || !exchange.publish())
            return false;
        if (before != after) apply(new VirtualFilterSlot.Change(after, "applied"));
        return true;
    }

    boolean publishList(ItemStack expected, ItemStack after, SerialDevice.Prepared exchange) {
        if (!status().equals("ready") || !writeStatus().equals("ready") || nativeStack() != expected || !exchange.publish()) return false;
        // The same native list item remains installed. The setter refreshes Create's wrapper,
        // inventory tracker or pipe propagation; no refund/insertion entry point is used here.
        return replaceList(after);
    }

    UUID listBindingId() { return supportsList() ? listMount : null; }
    UUID retainListBindingId() {
        var after = nativeStack().copy();
        VirtualListFilter.ensureIdentity(after);
        if (!replaceList(after)) throw new IllegalArgumentException("invalid_filter_archive");
        if (listMount == null) listMount = UUID.randomUUID();
        filter.blockEntity.setChanged();
        return listMount;
    }
    private boolean replaceList(ItemStack after) {
        listUpdate = true;
        try { return filter.setFilter(after); }
        finally { listUpdate = false; }
    }

    String set(long observed, DataValue.RecordValue value, int limit) {
        if (occupied()) return "filter_slot_occupied";
        if (!live() || !filter.isActive()) return "target_unavailable";
        if (invalid != null) return "invalid_filter_archive";
        if (!writeStatus().equals("ready")) return writeStatus();
        return apply(slot.set(observed, value, limit, REGISTRY));
    }

    String clear(long observed) {
        if (occupied()) return "filter_slot_occupied";
        if (!live() || !filter.isActive()) return "target_unavailable";
        if (invalid != null) return "invalid_filter_archive";
        if (!writeStatus().equals("ready")) return writeStatus();
        return apply(slot.clear(observed));
    }

    public void nativeChanged() {
        if (!(filter.getWorld() instanceof ServerLevel)) return;
        configurationRevision++;
        if (!listUpdate && listMount != null) {
            listMount = null;
            filter.blockEntity.setChanged();
        }
        if (!listUpdate) menuIdentity = UUID.randomUUID();
        var next = slot.nativeChanged(occupied());
        if (next != slot) {
            slot = next;
            // The native setter already called its change callback and marked the block entity dirty.
        }
    }

    /** Empty means let Create decide; false on unreadable rule or missing registry references. */
    public Optional<Boolean> test(ItemStack candidate) {
        if (!(filter.getWorld() instanceof ServerLevel) || occupied() || !filter.isActive()) return Optional.empty();
        if (invalid != null) return Optional.of(false);
        return slot.rule().map(rule -> !candidate.isEmpty() && rule.referencesAvailable(REGISTRY)
            && rule.matches(Domain.ITEM, BuiltInRegistries.ITEM.getKey(candidate.getItem()),
                tag -> candidate.is(TagKey.create(Registries.ITEM, tag))));
    }

    public Optional<Boolean> test(FluidStack candidate) {
        if (!(filter.getWorld() instanceof ServerLevel) || occupied() || !filter.isActive()) return Optional.empty();
        if (invalid != null) return Optional.of(false);
        return slot.rule().map(rule -> !candidate.isEmpty() && rule.referencesAvailable(REGISTRY)
            && rule.matches(Domain.FLUID, BuiltInRegistries.FLUID.getKey(candidate.getFluid()),
                tag -> candidate.getFluid().is(TagKey.create(Registries.FLUID, tag))));
    }

    public void read(CompoundTag tag) {
        configurationRevision++;
        identity = UUID.randomUUID();
        menuIdentity = UUID.randomUUID();
        bindingId = null;
        listMount = null;
        invalid = tag.contains(ARCHIVE_KEY) ? tag.get(ARCHIVE_KEY).copy() : null;
        slot = VirtualFilterSlot.empty(domains, occupied());
        if (invalid == null) return;
        try {
            if (!(invalid instanceof CompoundTag root) || !root.contains("Format", Tag.TAG_INT) || root.getInt("Format") != 1
                || !root.contains("Revision", Tag.TAG_LONG)
                || root.contains("Binding") && !root.hasUUID("Binding")
                || root.contains("ListMount") && !root.hasUUID("ListMount"))
                return;
            var keys = new java.util.HashSet<>(Set.of("Format", "Revision"));
            if (root.hasUUID("Binding")) keys.add("Binding");
            if (root.hasUUID("ListMount")) keys.add("ListMount");
            if (root.contains("Rule")) keys.add("Rule");
            if (!keys.equals(root.getAllKeys())) return;
            Optional<DataValue.RecordValue> data = Optional.empty();
            if (root.contains("Rule")) {
                if (!(root.get("Rule") instanceof CompoundTag encoded) || !(ValueNbtCodec.decode(encoded, ARCHIVE) instanceof DataValue.RecordValue rule))
                    return;
                data = Optional.of(rule);
            }
            slot = VirtualFilterSlot.restore(domains, root.getLong("Revision"), data).nativeChanged(occupied());
            bindingId = root.hasUUID("Binding") ? root.getUUID("Binding") : null;
            listMount = root.hasUUID("ListMount") && supportsList() ? root.getUUID("ListMount") : null;
            invalid = null;
        } catch (IllegalArgumentException rejected) { /* Retain unreadable data without activating or erasing it. */ }
    }

    public void write(CompoundTag tag) {
        tag.remove(ARCHIVE_KEY);
        if (invalid != null) { tag.put(ARCHIVE_KEY, invalid.copy()); return; }
        if (slot.version() == 0 && bindingId == null && listMount == null) return;
        var root = new CompoundTag();
        root.putInt("Format", 1);
        root.putLong("Revision", slot.version());
        if (bindingId != null) root.putUUID("Binding", bindingId);
        if (listMount != null) root.putUUID("ListMount", listMount);
        slot.data().ifPresent(rule -> root.put("Rule", ValueNbtCodec.encode(rule, ARCHIVE)));
        tag.put(ARCHIVE_KEY, root);
    }

    private boolean live() {
        return filter.getWorld() instanceof ServerLevel level && !filter.blockEntity.isRemoved()
            && level.getWorldBorder().isWithinBounds(filter.getPos()) && level.shouldTickBlocksAt(filter.getPos())
            && level.getChunkSource().getChunkNow(filter.getPos().getX() >> 4, filter.getPos().getZ() >> 4) != null
            && level.getBlockEntity(filter.getPos()) == filter.blockEntity;
    }

    private String apply(VirtualFilterSlot.Change change) {
        if (change.slot() == slot) return change.code();
        slot = change.slot();
        configurationRevision++;
        filter.blockEntity.setChanged();
        if (filter.blockEntity instanceof SmartFluidPipeBlockEntity)
            FluidPropagator.propagateChangedPipe(filter.getWorld(), filter.getPos(), filter.blockEntity.getBlockState());
        if (filter.blockEntity instanceof ThresholdSwitchBlockEntity threshold) {
            // The measure's inventory version has not changed. Invalidate only its observation cache;
            // let the next native sample and scheduled edge update redstone.
            var inventory = BlockEntityBehaviour.get(threshold, VersionedInventoryTrackerBehaviour.TYPE);
            if (inventory != null) inventory.reset();
        }
        return change.code();
    }
}
