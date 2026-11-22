package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import net.minecraft.core.BlockPos;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterEnclosure.Bounds;

/** One immutable bounded observation, with prefix counts for constant-time candidate rejection. */
final class EnclosureVolume {
    private final Bounds bounds;
    private final int sx, sy, sz;
    private final Cell[] cells;
    private final int[][] counts;

    EnclosureVolume(View view, Bounds bounds, BlockPos origin, Cell originCell) {
        this.bounds = bounds;
        sx = Math.toIntExact(bounds.size(net.minecraft.core.Direction.Axis.X)) + 1;
        sy = Math.toIntExact(bounds.size(net.minecraft.core.Direction.Axis.Y)) + 1;
        sz = Math.toIntExact(bounds.size(net.minecraft.core.Direction.Axis.Z)) + 1;
        int volume = Math.multiplyExact(Math.multiplyExact(sx, sy), sz);
        cells = new Cell[volume]; counts = new int[Kind.values().length][volume];
        for (int x = 1; x < sx; x++) for (int y = 1; y < sy; y++) for (int z = 1; z < sz; z++) {
            var position = bounds.min().offset(x - 1, y - 1, z - 1);
            Cell cell = position.equals(origin) ? originCell : read(view, origin, position);
            int index = index(x, y, z); cells[index] = cell;
            for (var kind : Kind.values()) {
                var prefix = counts[kind.ordinal()];
                prefix[index] = (cell.kind() == kind ? 1 : 0)
                    + prefix[index(x - 1, y, z)] + prefix[index(x, y - 1, z)] + prefix[index(x, y, z - 1)]
                    - prefix[index(x - 1, y - 1, z)] - prefix[index(x - 1, y, z - 1)] - prefix[index(x, y - 1, z - 1)]
                    + prefix[index(x - 1, y - 1, z - 1)];
            }
        }
    }

    static Cell read(View view, BlockPos origin, BlockPos position) {
        return view.observe(origin, position);
    }

    Cell cell(BlockPos position) {
        return cells[index(position.getX() - bounds.min().getX() + 1,
            position.getY() - bounds.min().getY() + 1, position.getZ() - bounds.min().getZ() + 1)];
    }

    int count(Bounds area, Kind kind) {
        int x0 = area.min().getX() - bounds.min().getX(), y0 = area.min().getY() - bounds.min().getY(), z0 = area.min().getZ() - bounds.min().getZ();
        int x1 = area.max().getX() - bounds.min().getX() + 1, y1 = area.max().getY() - bounds.min().getY() + 1, z1 = area.max().getZ() - bounds.min().getZ() + 1;
        var prefix = counts[kind.ordinal()];
        return prefix[index(x1, y1, z1)] - prefix[index(x0, y1, z1)] - prefix[index(x1, y0, z1)] - prefix[index(x1, y1, z0)]
            + prefix[index(x0, y0, z1)] + prefix[index(x0, y1, z0)] + prefix[index(x1, y0, z0)] - prefix[index(x0, y0, z0)];
    }

    private int index(int x, int y, int z) { return (x * sy + y) * sz + z; }
}
