package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import static com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeStructureArchive.hasType;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;
import java.util.*;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;

/** Persistence boundary for the recipe application's independent maintenance owner. */
public final class RecipeMaintenanceArchive {
    public static final String STATE = "StructureState", LAYOUT = "StructureSnapshot", TOPOLOGIES = "PageTopologies", INDEX = "PatternIndex";
    // Historical maximum, capped further by the host's existing configured scan limit.
    public static final int MAX_MEMBERS = 1024;
    private RecipeMaintenanceArchive() {}

    public static CompoundTag save(RecipeLibraryMaintenance owner, HolderLookup.Provider registries) {
        var tag = new CompoundTag(); writeMetadata(owner, tag); tag.put(INDEX, owner.index().save(registries)); return tag;
    }

    /** Writes only recipe-owned fields; the historical host may append its queue archive to the same index. */
    public static void writeMetadata(RecipeLibraryMaintenance owner, CompoundTag tag) {
        var prepared = new CompoundTag(); prepared.putString(STATE, owner.state().name());
        if (owner.layout() != null) prepared.put(LAYOUT, RecipeStructureArchive.save(owner.layout()));
        var saved = new ListTag();
        owner.topologies().entrySet().stream().sorted(Comparator.comparingLong(entry -> entry.getKey().asLong())).forEach(entry -> {
            var child = new CompoundTag(); child.putLong("Pos", entry.getKey().asLong());
            child.putInt("WritableSlots", entry.getValue()); saved.add(child);
        });
        prepared.put(TOPOLOGIES, saved);
        tag.remove(LAYOUT); tag.merge(prepared);
    }

    /** Standalone current archive; historical production envelopes are validated by their own adapter. */
    public static Optional<Prepared> load(CompoundTag tag, HolderLookup.Provider registries, int memberLimit, @Nullable RecipeSourceLocation space) {
        var keys = tag.contains(LAYOUT) ? Set.of(STATE, LAYOUT, TOPOLOGIES, INDEX) : Set.of(STATE, TOPOLOGIES, INDEX);
        if (!tag.getAllKeys().equals(keys) || !hasType(tag, INDEX, Tag.TAG_COMPOUND) || !validMetadata(tag, memberLimit, false))
            return Optional.empty();
        var index = RecipePageIndex.load(tag.getCompound(INDEX), registries);
        return prepare(tag, index.index(), index.hadCorruption(), false, memberLimit, space);
    }

    /** Prepares without mutating an owner or re-decoding the historical adapter's already-owned index. */
    public static Optional<Prepared> prepare(CompoundTag tag, RecipePageIndex index, boolean repaired,
        boolean priorNested, int memberLimit, @Nullable RecipeSourceLocation space) {
        Objects.requireNonNull(index);
        if (!validMetadata(tag, memberLimit, priorNested)) return Optional.empty();
        var layout = tag.contains(LAYOUT) ? RecipeStructureArchive.load(tag.getCompound(LAYOUT)) : Optional.<LibraryStructureScanner.ScanResult>empty();
        boolean replaceLayout = !tag.contains(LAYOUT) || layout.isPresent();
        var state = LibraryStructureScanner.State.valueOf(tag.getString(STATE));
        var topologies = priorNested ? Optional.of(Map.<BlockPos, Integer>of()) : loadTopologies((ListTag)tag.get(TOPOLOGIES), memberLimit);
        boolean corrupt = repaired || !replaceLayout || topologies.isEmpty();
        var slots = topologies.orElse(Map.of());
        boolean pending = priorNested || topologies.isEmpty() || repaired || layout.isEmpty()
            || state != LibraryStructureScanner.State.VALID
            || !matchesSpace(layout.orElseThrow(), slots, index.pageOrder(), memberLimit, space);
        return Optional.of(new Prepared(index, state, layout.orElse(null), replaceLayout, slots, pending, corrupt));
    }

    public static boolean validMetadata(CompoundTag tag, int memberLimit, boolean priorNested) {
        if (!hasType(tag, STATE, Tag.TAG_STRING) || tag.contains(LAYOUT) && !hasType(tag, LAYOUT, Tag.TAG_COMPOUND)) return false;
        if (priorNested) {
            if (tag.contains(TOPOLOGIES)) return false;
        } else {
            if (!hasType(tag, TOPOLOGIES, Tag.TAG_LIST)) return false;
            var list = (ListTag)tag.get(TOPOLOGIES);
            if (list.size() > effectiveMemberLimit(memberLimit) || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) return false;
        }
        try { LibraryStructureScanner.State.valueOf(tag.getString(STATE)); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public record Prepared(RecipePageIndex index, LibraryStructureScanner.State state,
        @Nullable LibraryStructureScanner.ScanResult layout, boolean replaceLayout, Map<BlockPos, Integer> topologies,
        boolean refreshPending, boolean hadCorruption) {
        public Prepared {
            Objects.requireNonNull(index); Objects.requireNonNull(state);
            var owned = new HashMap<BlockPos, Integer>(); topologies.forEach((pos, mask) -> owned.put(pos.immutable(), mask));
            topologies = Map.copyOf(owned);
        }
        public void commitTo(RecipeLibraryMaintenance owner) { owner.restore(index, state, layout, replaceLayout, topologies, refreshPending); }
    }

    public static Optional<Map<BlockPos, Integer>> loadTopologies(ListTag saved, int memberLimit) {
        if (saved.size() > effectiveMemberLimit(memberLimit)) return Optional.empty();
        var result = new HashMap<BlockPos, Integer>();
        for (var value : saved) {
            if (!(value instanceof CompoundTag child) || !child.getAllKeys().equals(Set.of("Pos", "WritableSlots"))
                || !hasType(child, "Pos", Tag.TAG_LONG) || !hasType(child, "WritableSlots", Tag.TAG_INT)) return Optional.empty();
            int mask = child.getInt("WritableSlots");
            if (mask < 0 || mask > 0x3f || result.put(BlockPos.of(child.getLong("Pos")), mask) != null) return Optional.empty();
        }
        return Optional.of(Map.copyOf(result));
    }

    public static boolean matchesTopology(LibraryStructureScanner.ScanResult layout, Map<BlockPos, Integer> topologies,
        List<PatternPageKey> order, int memberLimit) {
        var s = layout.snapshot(); int limit = effectiveMemberLimit(memberLimit);
        if (layout.state() != LibraryStructureScanner.State.VALID || s == null || s.members().isEmpty()
            || s.members().size() > limit || topologies.size() > limit || !new HashSet<>(s.mediaShelves()).equals(topologies.keySet())
            || !RecipePageIndex.isCanonicalPageOrder(order)) return false;
        long expectedPages = 0;
        for (int mask : topologies.values()) {
            if (mask < 0 || mask > 0x3f) return false;
            expectedPages += (long)Integer.bitCount(mask) * 100L;
        }
        if (expectedPages != order.size()) return false;
        var addresses = new HashMap<BlockPos, RecipeSourceLocation>(); RecipeSourceLocation common = null;
        for (var key : order) {
            var pos = key.shelf().localPos(); var mask = topologies.get(pos);
            if (mask == null || (mask & (1 << key.slot())) == 0) return false;
            var previous = addresses.putIfAbsent(pos, key.shelf());
            if (previous != null && !previous.equals(key.shelf())) return false;
            if (common == null) common = key.shelf();
            else if (!common.dimension().equals(key.shelf().dimension()) || !Objects.equals(common.subLevelId(), key.shelf().subLevelId())) return false;
        }
        return true;
    }

    public static boolean matchesSpace(LibraryStructureScanner.ScanResult layout, Map<BlockPos, Integer> topologies,
        List<PatternPageKey> order, int memberLimit, @Nullable RecipeSourceLocation space) {
        if (space == null || order.isEmpty() || layout.snapshot() == null || layout.snapshot().members().isEmpty()
            || !layout.snapshot().members().getFirst().equals(space.localPos()) || !matchesTopology(layout, topologies, order, memberLimit)) return false;
        return order.stream().allMatch(key -> space.dimension().equals(key.shelf().dimension())
            && Objects.equals(space.subLevelId(), key.shelf().subLevelId()));
    }

    public static int effectiveMemberLimit(int configured) { return Math.clamp(configured, 1, MAX_MEMBERS); }
}
