package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Historical production structure evidence, never a live world-access grant or generic Cluster state. */
public record ProductionStructure(ProductionLayout layout, Set<BlockPos> casingPositions,
    Set<ChunkPos> containingChunks) {
    public ProductionStructure {
        Objects.requireNonNull(layout);
        var casing = new LinkedHashSet<BlockPos>();
        for (var pos : casingPositions) casing.add(Objects.requireNonNull(pos).immutable());
        casingPositions = Collections.unmodifiableSet(casing);
        containingChunks = Collections.unmodifiableSet(new LinkedHashSet<>(containingChunks));
        var b = layout.bounds();
        for (var pos : casingPositions)
            if (!b.contains(pos)) throw new IllegalArgumentException("casing outside production bounds");
        UUID previous = null;
        for (var panel : layout.panels()) {
            if (previous != null && previous.compareTo(panel.panelId()) >= 0)
                throw new IllegalArgumentException("structure panels must be uniquely UUID-sorted");
            previous = panel.panelId();
            if (!shell(b,panel.address().localPos())) throw new IllegalArgumentException("structure panel is not on shell");
        }
        // Bounded offsets avoid wrapping an absolute coordinate at Integer.MAX_VALUE.
        int width = b.maxX()-b.minX()+1, height = b.maxY()-b.minY()+1, depth = b.maxZ()-b.minZ()+1;
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < depth; z++) {
            if (x != 0 && x != width-1 && y != 0 && y != height-1 && z != 0 && z != depth-1) continue;
            if (!casing.contains(new BlockPos(b.minX()+x,b.minY()+y,b.minZ()+z)))
                throw new IllegalArgumentException("incomplete production shell");
        }
        if (!chunksFor(b).equals(containingChunks)) throw new IllegalArgumentException("production chunk evidence does not match bounds");
    }

    public static Set<ChunkPos> chunksFor(ProductionLayout.Bounds bounds) {
        var result = new LinkedHashSet<ChunkPos>();
        for (int x = Math.floorDiv(bounds.minX(),16); x <= Math.floorDiv(bounds.maxX(),16); x++)
            for (int z = Math.floorDiv(bounds.minZ(),16); z <= Math.floorDiv(bounds.maxZ(),16); z++)
                result.add(new ChunkPos(x,z));
        return Collections.unmodifiableSet(result);
    }
    private static boolean shell(ProductionLayout.Bounds b, BlockPos pos) {
        return pos.getX()==b.minX() || pos.getX()==b.maxX() || pos.getY()==b.minY() || pos.getY()==b.maxY()
            || pos.getZ()==b.minZ() || pos.getZ()==b.maxZ();
    }

    /** Coordinator may be absent from current geometry while a separate frozen epoch retains it. */
    public record Revision(UUID structureId, long revision, UUID coordinatorId, ProductionStructure structure) {
        public Revision {
            Objects.requireNonNull(structureId); Objects.requireNonNull(coordinatorId); Objects.requireNonNull(structure);
            if (revision < 0) throw new IllegalArgumentException("negative production structure revision");
        }
        public Optional<Revision> revise(UUID coordinator, ProductionStructure next) {
            Objects.requireNonNull(coordinator); Objects.requireNonNull(next);
            return revision == Long.MAX_VALUE ? Optional.empty()
                : Optional.of(new Revision(structureId,revision+1,coordinator,next));
        }
    }
}
