package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout.*;

/** Exact integer rigid relocation; no reflection, interpolation, world lookup or resource pooling. */
final class ProductionEpochRelocation {
    private static final List<Rotation> ROTATIONS = rotations();
    private ProductionEpochRelocation() {}

    static ProductionLayout relocate(ProductionLayout frozen, ProductionLayout current) {
        var currentMembers = new HashMap<UUID, Member>();
        current.members().forEach(member -> currentMembers.put(member.computerId(), member));
        var matched = new ArrayList<Member>();
        for (var member : frozen.members()) {
            var actual = currentMembers.get(member.computerId());
            if (actual == null || !member.profile().equals(actual.profile()) || !member.address().sameSpace(actual.address()))
                throw new IllegalArgumentException("frozen member missing or its profile/space changed");
            matched.add(actual);
        }
        var currentPanels = new HashMap<UUID, Panel>();
        current.panels().forEach(panel -> currentPanels.put(panel.panelId(), panel));
        if (currentPanels.size() != frozen.panels().size()) throw new IllegalArgumentException("frozen panel set changed");
        var bounds = current.bounds();
        for (var rotation : ROTATIONS) {
            long[] rotatedBounds = transformedBounds(frozen.bounds(), rotation);
            long tx = (long)bounds.minX() - rotatedBounds[0], ty = (long)bounds.minY() - rotatedBounds[1],
                tz = (long)bounds.minZ() - rotatedBounds[2];
            if (rotatedBounds[3] + tx != bounds.maxX() || rotatedBounds[4] + ty != bounds.maxY()
                || rotatedBounds[5] + tz != bounds.maxZ()) continue;
            boolean matches = true;
            for (int i = 0; i < frozen.members().size(); i++)
                if (!rotation.matches(frozen.members().get(i).address().localPos(), matched.get(i).address().localPos(), tx, ty, tz)) {
                    matches = false; break;
                }
            if (!matches) continue;
            var panels = new ArrayList<Panel>();
            for (var panel : frozen.panels()) {
                var actual = currentPanels.get(panel.panelId());
                if (actual == null || !rotation.matches(panel.address().localPos(), actual.address().localPos(), tx, ty, tz)
                    || actual.baseMaterial() != panel.baseMaterial() || actual.facing() != rotation.apply(panel.facing())) {
                    matches = false; break;
                }
                panels.add(actual);
            }
            if (matches) return new ProductionLayout(bounds, matched, panels);
        }
        throw new IllegalArgumentException("current layout is not a rigid proper relocation");
    }

    private static long[] transformedBounds(Bounds bounds, Rotation rotation) {
        long[] result = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        for (int x : new int[] {bounds.minX(), bounds.maxX()})
            for (int y : new int[] {bounds.minY(), bounds.maxY()})
                for (int z : new int[] {bounds.minZ(), bounds.maxZ()}) {
                    long[] point = rotation.apply(x, y, z);
                    for (int axis = 0; axis < 3; axis++) {
                        result[axis] = Math.min(result[axis], point[axis]);
                        result[axis + 3] = Math.max(result[axis + 3], point[axis]);
                    }
                }
        return result;
    }
    private static List<Rotation> rotations() {
        var result = new ArrayList<Rotation>(24);
        int[][] permutations = {{0,1,2}, {0,2,1}, {1,0,2}, {1,2,0}, {2,0,1}, {2,1,0}};
        for (int[] axes : permutations) {
            int inversions = 0;
            for (int i = 0; i < 3; i++) for (int j = i + 1; j < 3; j++) if (axes[i] > axes[j]) inversions++;
            int parity = inversions % 2 == 0 ? 1 : -1;
            for (int sx : new int[] {-1,1}) for (int sy : new int[] {-1,1}) for (int sz : new int[] {-1,1})
                if (parity * sx * sy * sz == 1) result.add(new Rotation(axes[0], axes[1], axes[2], sx, sy, sz));
        }
        return List.copyOf(result);
    }
    private record Rotation(int xAxis, int yAxis, int zAxis, int sx, int sy, int sz) {
        long[] apply(int x, int y, int z) {
            long[] source = {x, y, z}; return new long[] {sx * source[xAxis], sy * source[yAxis], sz * source[zAxis]};
        }
        boolean matches(BlockPos original, BlockPos actual, long tx, long ty, long tz) {
            long[] r = apply(original.getX(), original.getY(), original.getZ());
            return r[0] + tx == actual.getX() && r[1] + ty == actual.getY() && r[2] + tz == actual.getZ();
        }
        Direction apply(Direction direction) {
            long[] v = apply(direction.getStepX(), direction.getStepY(), direction.getStepZ());
            return Direction.getNearest((int)v[0], (int)v[1], (int)v[2]);
        }
    }
}
