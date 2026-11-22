package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionTopologyTags.*;

/** Sole historical snapshot v2 / structure record v1 representation. */
public final class ProductionStructureCodec {
    private static final Set<String> SNAPSHOT_KEYS = Set.of("Version","Bounds","Nodes","Panels","CasingPositions","ContainingChunks");
    private static final Set<String> REVISION_KEYS = Set.of("Version","StructureMemberId","Revision","CoordinatorId","Snapshot");
    private ProductionStructureCodec() {}

    public static CompoundTag saveSnapshot(ProductionStructure structure) {
        var tag = new CompoundTag(); tag.putInt("Version",2); writeLayout(tag,structure.layout());
        tag.putLongArray("CasingPositions",structure.casingPositions().stream().mapToLong(BlockPos::asLong).sorted().toArray());
        tag.putLongArray("ContainingChunks",structure.containingChunks().stream().mapToLong(ChunkPos::toLong).sorted().toArray());
        return tag;
    }
    public static Optional<ProductionStructure> loadSnapshot(CompoundTag tag) {
        if (tag==null || !tag.getAllKeys().equals(SNAPSHOT_KEYS) || !has(tag,"Version",Tag.TAG_INT) || tag.getInt("Version")!=2
            || !has(tag,"CasingPositions",Tag.TAG_LONG_ARRAY) || !has(tag,"ContainingChunks",Tag.TAG_LONG_ARRAY)) return Optional.empty();
        try {
            var layout = readLayout(tag); var chunks = ProductionStructure.chunksFor(layout.bounds());
            long[] expectedChunks = chunks.stream().mapToLong(ChunkPos::toLong).sorted().toArray();
            if (!Arrays.equals(expectedChunks,tag.getLongArray("ContainingChunks"))) return Optional.empty();
            long[] rawCasing = tag.getLongArray("CasingPositions");
            if (rawCasing.length>ProductionLayout.MAX_VOLUME) return Optional.empty();
            var casing = new LinkedHashSet<BlockPos>();
            for (int i=0; i<rawCasing.length; i++) {
                if (i>0 && rawCasing[i-1]>=rawCasing[i]) return Optional.empty();
                casing.add(BlockPos.of(rawCasing[i]));
            }
            return Optional.of(new ProductionStructure(layout,casing,chunks));
        } catch (IllegalArgumentException | NoSuchElementException invalid) { return Optional.empty(); }
    }

    public static CompoundTag saveRevision(ProductionStructure.Revision value) {
        var tag = new CompoundTag(); tag.putInt("Version",1); tag.putUUID("StructureMemberId",value.structureId());
        tag.putLong("Revision",value.revision()); tag.putUUID("CoordinatorId",value.coordinatorId());
        tag.put("Snapshot",saveSnapshot(value.structure())); return tag;
    }
    public static Optional<ProductionStructure.Revision> loadRevision(CompoundTag tag) {
        if (tag==null || !tag.getAllKeys().equals(REVISION_KEYS) || !has(tag,"Version",Tag.TAG_INT) || tag.getInt("Version")!=1
            || !tag.hasUUID("StructureMemberId") || !has(tag,"Revision",Tag.TAG_LONG) || tag.getLong("Revision")<0
            || !tag.hasUUID("CoordinatorId") || !has(tag,"Snapshot",Tag.TAG_COMPOUND)) return Optional.empty();
        return loadSnapshot(tag.getCompound("Snapshot")).map(structure -> new ProductionStructure.Revision(
            tag.getUUID("StructureMemberId"),tag.getLong("Revision"),tag.getUUID("CoordinatorId"),structure));
    }
}
