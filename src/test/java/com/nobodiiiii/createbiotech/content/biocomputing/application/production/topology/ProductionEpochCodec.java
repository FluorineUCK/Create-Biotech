package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import java.util.*;
import net.minecraft.nbt.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionTopologyTags.*;

/** Sole codec for historical production epoch v2. No general Cluster or Computer RAM format. */
public final class ProductionEpochCodec {
    private static final Set<String> KEYS = Set.of("Version", "EpochId", "ClusterId", "StructureMemberId",
        "CoordinatorId", "CoordinatorAddress", "Bounds", "Nodes", "Panels");
    private ProductionEpochCodec() {}

    public static CompoundTag save(ProductionEpoch epoch) {
        var tag = new CompoundTag(); tag.putInt("Version",2);
        tag.putUUID("EpochId",epoch.epochId()); tag.putUUID("ClusterId",epoch.groupId());
        tag.putUUID("StructureMemberId",epoch.structureId()); tag.putUUID("CoordinatorId",epoch.coordinatorId());
        tag.put("CoordinatorAddress",address(epoch.coordinatorAddress())); writeLayout(tag,epoch.layout()); return tag;
    }
    public static Optional<ProductionEpoch> load(CompoundTag tag) {
        if (tag==null || !tag.getAllKeys().equals(KEYS) || !has(tag,"Version",Tag.TAG_INT) || tag.getInt("Version")!=2
            || !tag.hasUUID("EpochId") || !tag.hasUUID("ClusterId") || !tag.hasUUID("StructureMemberId") || !tag.hasUUID("CoordinatorId")
            || !has(tag,"CoordinatorAddress",Tag.TAG_COMPOUND)) return Optional.empty();
        try {
            var epoch = new ProductionEpoch(tag.getUUID("EpochId"),tag.getUUID("ClusterId"),tag.getUUID("StructureMemberId"),
                tag.getUUID("CoordinatorId"),readLayout(tag));
            return epoch.coordinatorAddress().equals(readAddress(tag.getCompound("CoordinatorAddress"))) ? Optional.of(epoch) : Optional.empty();
        } catch (IllegalArgumentException | NoSuchElementException invalid) { return Optional.empty(); }
    }
}
