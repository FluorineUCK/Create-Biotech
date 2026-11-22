package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

/** Recipe-only maintenance, independently usable without production queues or a legacy block entity. */
public final class RecipeLibraryMaintenance {
    private RecipePageIndex index = new RecipePageIndex();
    private LibraryStructureScanner.State state = LibraryStructureScanner.State.UNFORMED;
    @Nullable private LibraryStructureScanner.ScanResult layout;
    private Map<BlockPos, Integer> topologies = Map.of();
    private boolean pending;
    private int bonus, budget;

    public RecipePageIndex index() { return index; }
    public LibraryStructureScanner.State state() { return state; }
    @Nullable public LibraryStructureScanner.ScanResult layout() { return layout; }
    public Map<BlockPos, Integer> topologies() { return topologies; }
    public boolean topologyRefreshPending() { return pending; }
    public int efficiencyBonus() { return bonus; }
    public int searchBudget() { return budget; }
    public int queueCount() { return layout == null ? 1 : 1 + layout.snapshot().ordinaryShelves().size(); }
    public boolean ready() { return state == LibraryStructureScanner.State.VALID && !pending; }

    /** A PARTIAL observation retains diagnostic history, never permission to index that history. */
    public boolean apply(LibraryStructureScanner.ScanResult scan, @Nullable Map<BlockPos, Integer> observed,
        int efficiencyBonus, boolean admitted, Function<BlockPos, RecipeSourceLocation> addresses) {
        Objects.requireNonNull(scan); Objects.requireNonNull(addresses);
        var previousState = state; var previousLayout = layout; var previousTopologies = topologies;
        boolean previousPending = pending; int previousBonus = bonus; long generation = index.generation();
        Map<BlockPos, Integer> prepared = observed == null ? null : immutableTopologies(observed);
        boolean reconcile = scan.state() == LibraryStructureScanner.State.VALID && prepared != null
            && (!topologies.equals(prepared) || prepared.isEmpty() && !index.pageOrder().isEmpty() || pending);
        // Spatial witnesses are prepared before any admission state is published.
        var keys = reconcile ? canonicalPages(scan, prepared, addresses) : List.<PatternPageKey>of();
        state = scan.state();
        if (state != LibraryStructureScanner.State.PARTIAL) {
            var next = scan.snapshot() == null ? null : scan;
            if (!Objects.equals(layout, next)) layout = next;
            if (state == LibraryStructureScanner.State.VALID && observed != null) {
                if (reconcile) {
                    boolean mismatch = !index.pageOrder().equals(keys);
                    if (mismatch && !admitted) pending = true;
                    else {
                        if (mismatch) index.rebuildPageOrder(keys);
                        topologies = prepared; pending = false;
                    }
                } else pending = false;
            }
            bonus = efficiencyBonus;
        }
        return state != previousState || !Objects.equals(layout, previousLayout)
            || !topologies.equals(previousTopologies) || pending != previousPending
            || bonus != previousBonus || generation != index.generation();
    }

    private static List<PatternPageKey> canonicalPages(LibraryStructureScanner.ScanResult scan,
        Map<BlockPos, Integer> observed, Function<BlockPos, RecipeSourceLocation> addresses) {
        var keys = new ArrayList<PatternPageKey>();
        if (scan.snapshot() != null) for (var shelf : scan.snapshot().mediaShelves()) {
            int mask = observed.getOrDefault(shelf, 0);
            if (mask == 0) continue;
            var address = addresses.apply(shelf);
            for (int slot = 0; slot < 6; slot++) if ((mask & (1 << slot)) != 0)
                for (int page = 0; page < 100; page++) keys.add(new PatternPageKey(address, slot, page));
        }
        return RecipePageIndex.canonicalPageOrder(keys);
    }

    public boolean ensureLoaded(Predicate<BlockPos> loaded) {
        Objects.requireNonNull(loaded);
        if (state == LibraryStructureScanner.State.PARTIAL) return false;
        if (layout != null && !layout.snapshot().members().stream().allMatch(loaded)) {
            state = LibraryStructureScanner.State.PARTIAL; return false;
        }
        return true;
    }

    public void markIncomplete() { pending = true; state = LibraryStructureScanner.State.PARTIAL; }

    /** Never advances production queries; its sole work budget belongs to page maintenance. */
    public boolean advance(boolean admitted, Predicate<BlockPos> loaded, Predicate<BlockPos> readable,
        RecipePageIndex.PageReader pages, PatternJsonParser parser, int budget) {
        Objects.requireNonNull(loaded); Objects.requireNonNull(readable);
        if (!admitted || !ready() || !ensureLoaded(loaded)) return false;
        if (layout != null && !layout.snapshot().mediaShelves().stream().allMatch(readable)) {
            markIncomplete(); return false;
        }
        index.tick(pages, parser, budget); return true;
    }

    public void updateBudget(int residentLevel, int maximum) {
        budget = state == LibraryStructureScanner.State.VALID ? Math.min(maximum, baseBudget(residentLevel) + bonus) : 0;
    }
    /** Existing recipe maintenance rule, not Computer execution capacity. */
    public static int baseBudget(int level) {
        return switch (Math.clamp(level, 1, 5)) { case 1 -> 8; case 2 -> 16; case 3 -> 32; case 4 -> 64; default -> 128; };
    }

    /** Restores the one decoded index after the host has validated its complete historical envelope. */
    public void restore(RecipePageIndex restored, LibraryStructureScanner.State state,
        @Nullable LibraryStructureScanner.ScanResult layout, boolean replaceLayout,
        Map<BlockPos, Integer> topologies, boolean refreshPending) {
        var ownedTopologies = immutableTopologies(topologies);
        Objects.requireNonNull(restored); Objects.requireNonNull(state);
        this.index = restored; this.state = state;
        if (replaceLayout) this.layout = layout;
        this.topologies = ownedTopologies; pending = refreshPending;
    }

    private static Map<BlockPos, Integer> immutableTopologies(Map<BlockPos, Integer> source) {
        var copy = new HashMap<BlockPos, Integer>();
        source.forEach((pos, mask) -> copy.put(pos.immutable(), mask));
        return Map.copyOf(copy);
    }
}
