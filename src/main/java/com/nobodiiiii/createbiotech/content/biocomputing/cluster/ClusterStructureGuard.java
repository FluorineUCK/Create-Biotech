package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Caches geometry, never a permission to skip re-observing topology changes. */
public final class ClusterStructureGuard {
    private Window observed;
    private EnclosureScanner.Result result;
    private BlockPos origin;
    private EnclosureLimits limits;
    private Proof proof;
    public EnclosureScanner.Result current(EnclosureScanner.View view, BlockPos controller, EnclosureLimits limits) {
        if (result != null && controller.equals(origin) && limits.equals(this.limits)) {
            if (observed.matches(view, origin)) return result;
        }
        // World views are synchronous, read-only observations on the server thread. A changed
        // snapshot is freshly scanned; no topology permission survives this call or callbacks.
        var current = new Observation(view, controller.immutable());
        var next = EnclosureScanner.scan(current, controller, limits);
        observed = new Window(current.cells, view instanceof WorldEnclosureView world && world.canReadSection(controller)
            ? world.representatives() : null);
        origin = controller.immutable(); this.limits = limits; result = next;
        proof = next.status() == EnclosureScanner.Status.READY ? new Proof(observed, origin, limits) : null;
        return next;
    }
    public void clear() { observed = null; origin = null; limits = null; result = null; proof = null; }

    /** Captured evidence, not cached permission: every use re-reads the complete discovery window.
     * Later scans of this guard cannot silently substitute a different geometry into an old route. */
    public Optional<Proof> proof() {
        // Stable evidence identity lets one callback-free barrier deduplicate identical proofs.
        // No check result is cached: Proof.unchanged still observes the world on every invocation.
        return Optional.ofNullable(proof);
    }
    public static final class Proof {
        private final Window observed;
        private final BlockPos origin;
        private final EnclosureLimits limits;
        private Proof(Window observed, BlockPos origin, EnclosureLimits limits) {
            this.observed = observed; this.origin = origin; this.limits = limits;
        }
        public boolean unchanged(EnclosureScanner.View view, BlockPos controller, EnclosureLimits limits) {
            if (!controller.equals(origin) || !limits.equals(this.limits)) return false;
            return observed.matches(view, origin);
        }
    }

    /** Read-only terminal proof against the last bounded observation; never rescan or update the cache. */
    public boolean unchanged(EnclosureScanner.View view, BlockPos controller, EnclosureLimits limits) {
        if (result == null || result.status() != EnclosureScanner.Status.READY || !controller.equals(origin) || !limits.equals(this.limits)) return false;
        return observed.matches(view, origin);
    }

    /** Immutable evidence grouped once at capture. Chunk handles and match results are never retained. */
    private static final class Window {
        private record Section(BlockPos[] positions, EnclosureScanner.Cell[] expected,
                               BlockState[] representatives) {}
        private final Section[] sections;
        Window(Map<BlockPos, EnclosureScanner.Cell> cells,
               Map<EnclosureScanner.Cell, BlockState> representatives) {
            var groups = new LinkedHashMap<Long, List<BlockPos>>();
            cells.keySet().forEach(pos -> groups.computeIfAbsent(net.minecraft.core.SectionPos.asLong(pos),
                ignored -> new ArrayList<>()).add(pos));
            sections = groups.values().stream().map(group -> {
                // Vanilla stores each section in Y/Z/X order, with X contiguous. Arrange the
                // immutable evidence once; every barrier still reads every live cell on a match.
                if (representatives != null) group.sort(Comparator.comparingInt(p ->
                    ((p.getY() & 15) << 8) | ((p.getZ() & 15) << 4) | (p.getX() & 15)));
                var positions = group.toArray(BlockPos[]::new);
                var expected = group.stream().map(cells::get).toArray(EnclosureScanner.Cell[]::new);
                var hints = representatives == null ? null : Arrays.stream(expected).map(representatives::get)
                    .toArray(BlockState[]::new);
                return new Section(positions, expected, hints);
            }).toArray(Section[]::new);
        }
        boolean matches(EnclosureScanner.View view, BlockPos origin) {
            if (view instanceof WorldEnclosureView world && world.canReadSection(origin)) {
                for (var section : sections)
                    if (!world.matchesSection(section.positions(), section.expected(), section.representatives())) return false;
            } else {
                for (var section : sections)
                    for (int i = 0; i < section.positions().length; i++)
                        if (!section.expected()[i].equals(EnclosureVolume.read(view, origin, section.positions()[i]))) return false;
            }
            return true;
        }
    }

    private static final class Observation implements EnclosureScanner.View {
        private final EnclosureScanner.View source;
        private final BlockPos origin;
        private final Map<BlockPos, EnclosureScanner.Cell> cells = new HashMap<>();
        private Observation(EnclosureScanner.View source, BlockPos origin) { this.source = source; this.origin = origin; }
        private EnclosureScanner.Cell observe(BlockPos position) {
            return cells.computeIfAbsent(position.immutable(), key -> EnclosureVolume.read(source, origin, key));
        }
        // This private scan snapshot is tied to its constructor origin and expires after
        // the scan. Read its complete cell once, rather than three identical map lookups.
        @Override public EnclosureScanner.Cell observe(BlockPos origin, BlockPos position) { return observe(position); }
        public boolean loaded(BlockPos position) { return observe(position).kind() != EnclosureScanner.Kind.UNLOADED; }
        public boolean sameSpace(BlockPos origin, BlockPos position) { return observe(position).kind() != EnclosureScanner.Kind.FOREIGN; }
        public EnclosureScanner.Cell cell(BlockPos position) { return observe(position); }
    }
}
