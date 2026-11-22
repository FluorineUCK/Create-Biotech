package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Local-space cosmetics only; this never changes a pin or a communication endpoint. */
public final class ClusterIndicatorGeometry {
    private ClusterIndicatorGeometry() {}

    public static Vec3 waitingPoint(BlockPos source, BlockPos peer) {
        var horizontal = new Vec3((double) peer.getX() - source.getX(), 0, (double) peer.getZ() - source.getZ());
        double distance = horizontal.length();
        // Move clear of the resident model toward the peer, without overshooting adjacent members.
        // A vertical pair uses an inset corner of its own block, never an arbitrary adjacent shell block.
        var offset = distance == 0 ? new Vec3(.4, 0, .4) : horizontal.scale(Math.min(.6, distance / 2) / distance);
        return Vec3.atCenterOf(source).add(offset).add(0, .6, 0);
    }
}
