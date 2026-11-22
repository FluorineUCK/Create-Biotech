package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.Objects;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Geometric membership only: naming, communication authorization and RAM are separate. */
public record ClusterEnclosure(Bounds bounds, BlockPos controller, Direction outward,
        Set<BlockPos> shell, Map<BlockPos, Set<Contact>> contacts) {
    public ClusterEnclosure {
        Objects.requireNonNull(bounds); Objects.requireNonNull(outward);
        controller = controller.immutable();
        shell = Set.copyOf(shell.stream().map(BlockPos::immutable).collect(Collectors.toSet()));
        var copy = new HashMap<BlockPos, Set<Contact>>();
        contacts.forEach((position, faces) -> copy.put(position.immutable(), Set.copyOf(faces)));
        contacts = Map.copyOf(copy);
    }
    public boolean containsMember(BlockPos position) {
        var contact = contacts.get(position);
        return contact != null && !contact.isEmpty();
    }
    public Set<BlockPos> members() {
        return contacts.keySet().stream().filter(this::containsMember).collect(Collectors.toUnmodifiableSet());
    }
    public record Contact(BlockPos part, Direction side) {
        public Contact { part = part.immutable(); Objects.requireNonNull(side); }
    }
    public record Bounds(BlockPos min, BlockPos max) {
        public Bounds {
            min = min.immutable(); max = max.immutable();
            if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ())
                throw new IllegalArgumentException("enclosure_bounds");
        }
        public long size(Direction.Axis axis) { return (long)max.get(axis) - min.get(axis) + 1; }
        public boolean contains(BlockPos position) {
            for (var axis : Direction.Axis.values())
                if (position.get(axis) < min.get(axis) || position.get(axis) > max.get(axis)) return false;
            return true;
        }
        public boolean boundary(BlockPos position) {
            if (!contains(position)) return false;
            for (var axis : Direction.Axis.values())
                if (position.get(axis) == min.get(axis) || position.get(axis) == max.get(axis)) return true;
            return false;
        }
        boolean neighborInside(BlockPos position, Direction direction) {
            return direction.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? position.get(direction.getAxis()) < max.get(direction.getAxis())
                : position.get(direction.getAxis()) > min.get(direction.getAxis());
        }
    }
}
