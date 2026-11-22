package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryShelfReader;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryShelfUpdates;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureWorldView;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;

/** Event-driven physical recipe-source inspection; owns neither an index nor generic book entries. */
public final class RecipeShelfSource implements AutoCloseable {
    private final Level level;
    private final BlockPos core;
    private final LibraryShelfUpdates.Watch watch;
    private boolean dirty = true, closed;
    private int memberLimit = -1, spanLimit = -1;

    public RecipeShelfSource(Level level, BlockPos core) {
        this.level = Objects.requireNonNull(level); this.core = Objects.requireNonNull(core).immutable();
        watch = LibraryShelfUpdates.watch(level); observe(List.of(core));
    }

    /** Consumes notifications, not world blocks or books. Configuration changes also invalidate the view. */
    public boolean needsRefresh(int maxMembers, int maxSpan) {
        requireOpen();
        dirty |= watch.consume() || memberLimit != maxMembers || spanLimit != maxSpan;
        return dirty;
    }

    public Observation scan(int maxMembers, int maxSpan) {
        requireOpen();
        watch.consume(); // This scan covers preceding events; notifications during the read remain pending.
        dirty = true; // An interrupted/failed scan must be retried, not treated as a clean observation.
        var structure = LibraryStructureScanner.scan(new LibraryStructureWorldView(level), core, maxMembers, maxSpan);
        observe(structure.snapshot() == null ? List.of(core) : structure.snapshot().members());
        var books = Inspection.INCOMPLETE;
        if (structure.state() == LibraryStructureScanner.State.VALID && structure.snapshot() != null) {
            books = inspect(structure.snapshot().mediaShelves(), pos -> {
                var result = LibraryShelfReader.read(level, core, pos);
                return result.snapshot().map(LibraryShelfReader.Snapshot::items).orElse(null);
            }, level.registryAccess().holderOrThrow(Enchantments.EFFICIENCY));
            // A block entity can appear after its block/chunk notification; don't require another player action.
        }
        dirty = structure.state() == LibraryStructureScanner.State.VALID && !books.complete();
        memberLimit = maxMembers; spanLimit = maxSpan;
        return new Observation(structure, books);
    }

    public void invalidate() { requireOpen(); dirty = true; }

    private void observe(Collection<BlockPos> members) {
        var positions = new HashSet<BlockPos>(); positions.add(core);
        for (Direction face : Direction.values()) positions.add(core.relative(face).immutable());
        for (var pos : members) {
            positions.add(pos.immutable());
            for (Direction face : Direction.values()) positions.add(pos.relative(face).immutable());
        }
        watch.observe(positions);
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Recipe shelf source is closed"); }
    @Override public void close() {
        if (closed) return;
        closed = true; LibraryShelfUpdates.forget(level, watch);
    }

    public record Observation(LibraryStructureScanner.ScanResult structure, Inspection books) {}
    public record Inspection(boolean complete, Map<BlockPos, Integer> topologies, int efficiencyBonus) {
        private static final Inspection INCOMPLETE = new Inspection(false, Map.of(), 0);
        public Inspection { topologies = Map.copyOf(topologies); }
    }

    /** Recipe-only metadata. No generic Library components are decoded or retained. */
    public static Inspection inspect(Collection<BlockPos> shelves, Function<BlockPos, List<ItemStack>> contents,
        Holder<Enchantment> efficiency) {
        Objects.requireNonNull(shelves); Objects.requireNonNull(contents); Objects.requireNonNull(efficiency);
        var topologies = new LinkedHashMap<BlockPos, Integer>(); int bonus = 0;
        for (var pos : shelves) {
            var slots = contents.apply(pos);
            if (slots == null) return Inspection.INCOMPLETE;
            topologies.put(pos.immutable(), pageTopology(slots)); bonus += efficiencyBonus(slots, efficiency);
        }
        return new Inspection(true, topologies, bonus);
    }

    public static int pageTopology(List<ItemStack> slots) {
        Objects.requireNonNull(slots); int topology = 0;
        for (int slot = 0; slot < Math.min(6, slots.size()); slot++)
            if (Objects.requireNonNull(slots.get(slot)).is(Items.WRITABLE_BOOK)) topology |= 1 << slot;
        return topology;
    }

    /** Preserves the existing recipe-maintenance bonus; never a Computer execution resource. */
    public static int efficiencyBonus(List<ItemStack> slots, Holder<Enchantment> efficiency) {
        Objects.requireNonNull(slots); Objects.requireNonNull(efficiency); int result = 0;
        for (int slot = 0; slot < Math.min(6, slots.size()); slot++) {
            var stack = Objects.requireNonNull(slots.get(slot));
            if (!stack.is(Items.ENCHANTED_BOOK)) continue;
            int enchantment = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).getLevel(efficiency);
            if (enchantment > 0) result += enchantment * enchantment + 1;
        }
        return result;
    }
}
