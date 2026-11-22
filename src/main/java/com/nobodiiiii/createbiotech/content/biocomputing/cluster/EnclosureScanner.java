package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterEnclosure.Bounds;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterEnclosure.Contact;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner.Kind.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner.Status.*;

/** Loaded-only enclosure geometry; never reads Computer programs, identities or RAM. */
public final class EnclosureScanner {
    public enum Kind { AIR, CASING, CONTROLLER_BLOCK, COMPUTER_LOWER, COMPUTER_UPPER, INTERNAL, ILLEGAL, UNLOADED, FOREIGN }
    public record Cell(Kind kind, Direction facing) {
        public Cell {
            Objects.requireNonNull(kind);
            if ((kind == CONTROLLER_BLOCK || kind == COMPUTER_LOWER || kind == COMPUTER_UPPER) && facing == null)
                throw new IllegalArgumentException("missing_facing");
        }
    }
    public interface View {
        boolean loaded(BlockPos position);
        boolean sameSpace(BlockPos origin, BlockPos position);
        Cell cell(BlockPos position);
        /** One complete observation. Overrides must retain loaded/space checks before reading
         * state; this is not permission to reuse an earlier observation at a later barrier. */
        default Cell observe(BlockPos origin, BlockPos position) {
            if (!loaded(position)) return new Cell(UNLOADED, null);
            if (!sameSpace(origin, position)) return new Cell(FOREIGN, null);
            return cell(position);
        }
    }
    public enum Status { READY, OPEN_SHELL, NOT_CONTROLLER, INVALID_CONTROLLER, CONTROLLER_CONFLICT,
        PARTIAL_UNLOADED, WRONG_SPACE, SIZE, COMPUTER_LIMIT, INVALID_COMPUTER, ILLEGAL_INTERNAL, AMBIGUOUS, COORDINATE_RANGE }
    public record Result(Status status, Optional<ClusterEnclosure> enclosure) {}
    public static Result scan(View view, BlockPos controller, EnclosureLimits limits) {
        controller = controller.immutable();
        int radius = limits.maxSize() - 1;
        for (var axis : Direction.Axis.values())
            if ((long)controller.get(axis) - radius < Integer.MIN_VALUE || (long)controller.get(axis) + radius > Integer.MAX_VALUE)
                return failure(COORDINATE_RANGE);
        var origin = EnclosureVolume.read(view, controller, controller);
        var originStatus = controllerStatus(origin);
        if (originStatus != READY) return failure(originStatus);
        var volume = new EnclosureVolume(view, new Bounds(controller.offset(-radius, -radius, -radius),
            controller.offset(radius, radius, radius)), controller, origin);
        var xs = intervals(controller.getX(), Direction.Axis.X, origin.facing(), limits);
        var ys = intervals(controller.getY(), Direction.Axis.Y, origin.facing(), limits);
        var zs = intervals(controller.getZ(), Direction.Axis.Z, origin.facing(), limits);
        ClusterEnclosure found = null;
        boolean unresolvedAlternative = false;
        Status diagnostic = OPEN_SHELL;
        for (var x : xs) for (var y : ys) for (var z : zs) {
            var bounds = new Bounds(new BlockPos(x.min(), y.min(), z.min()), new BlockPos(x.max(), y.max(), z.max()));
            var result = candidate(volume, bounds, controller, origin.facing(), limits);
            if (result.status() == PARTIAL_UNLOADED) unresolvedAlternative = true;
            if (result.status() == READY) {
                if (found != null) return failure(AMBIGUOUS);
                found = result.enclosure().orElseThrow();
            } else if (priority(result.status()) > priority(diagnostic)) diagnostic = result.status();
        }
        if (found == null) return failure(diagnostic);
        // A second plausible shell must be excluded, not assumed absent until its chunk loads.
        return unresolvedAlternative ? failure(PARTIAL_UNLOADED) : new Result(READY, Optional.of(found));
    }
    public static Result inspect(View view, ClusterEnclosure.Bounds bounds, BlockPos controller, Direction outward, EnclosureLimits limits) {
        controller = controller.immutable();
        for (var axis : Direction.Axis.values())
            if (bounds.size(axis) < limits.minSize() || bounds.size(axis) > limits.maxSize()) return failure(SIZE);
        if (!bounds.contains(controller)) return failure(INVALID_CONTROLLER);
        var origin = EnclosureVolume.read(view, controller, controller);
        var originStatus = controllerStatus(origin);
        if (originStatus != READY) return failure(originStatus);
        if (origin.facing() != outward || !onFace(bounds, controller, outward)) return failure(INVALID_CONTROLLER);
        return candidate(new EnclosureVolume(view, bounds, controller, origin), bounds, controller, outward, limits);
    }

    private static Result candidate(EnclosureVolume volume, Bounds bounds, BlockPos controller, Direction outward, EnclosureLimits limits) {
        var inner = new Bounds(bounds.min().offset(1, 1, 1), bounds.max().offset(-1, -1, -1));
        int shellSize = size(bounds) - size(inner);
        int shell = boundaryCount(volume, bounds, inner, CASING) + boundaryCount(volume, bounds, inner, CONTROLLER_BLOCK);
        int unloaded = boundaryCount(volume, bounds, inner, UNLOADED), foreign = boundaryCount(volume, bounds, inner, FOREIGN);
        // A known hole rejects this candidate; unrelated unknown cells do not hide that fact.
        if (shell + unloaded + foreign != shellSize) return failure(OPEN_SHELL);
        if (volume.count(bounds, FOREIGN) > 0) return failure(WRONG_SPACE);
        if (volume.count(bounds, UNLOADED) > 0) return failure(PARTIAL_UNLOADED);
        if (boundaryCount(volume, bounds, inner, CONTROLLER_BLOCK) != 1) return failure(CONTROLLER_CONFLICT);
        if (volume.count(inner, ILLEGAL) > 0) return failure(ILLEGAL_INTERNAL);

        Set<BlockPos> boundary = new HashSet<>();
        Map<BlockPos, Set<Contact>> computers = new HashMap<>();
        for (var position : BlockPos.betweenClosed(bounds.min(), bounds.max())) {
            if (bounds.boundary(position)) { boundary.add(position.immutable()); continue; }
            var cell = volume.cell(position);
            if (cell.kind() != COMPUTER_LOWER) continue;
            var other = position.above();
            // An incomplete device is not a member, but is not a hole in the outer shell either.
            if (!inner.contains(other) || volume.cell(other).kind() != COMPUTER_UPPER || volume.cell(other).facing() != cell.facing()
                    || cell.facing().getAxis() == Direction.Axis.Y) continue;
            computers.put(position.immutable(), new HashSet<>());
        }

        // Only casing joined to the external shell can carry a contact. A floating cube is not a bus.
        Set<BlockPos> connected = new HashSet<>(boundary);
        var queue = new ArrayDeque<>(boundary);
        while (!queue.isEmpty()) {
            var position = queue.removeFirst();
            for (var direction : Direction.values()) {
                if (!bounds.neighborInside(position, direction)) continue;
                var next = position.relative(direction);
                if (volume.cell(next).kind() == CASING && connected.add(next)) queue.addLast(next);
            }
        }
        computers.forEach((base, contacts) -> {
            for (var part : List.of(base, base.above())) for (var direction : Direction.values())
                if (connected.contains(part.relative(direction))) contacts.add(new Contact(part, direction));
        });
        return new Result(READY, Optional.of(new ClusterEnclosure(bounds, controller, outward, boundary, computers)));
    }

    private static Status controllerStatus(Cell cell) {
        return switch (cell.kind()) { case UNLOADED -> PARTIAL_UNLOADED; case FOREIGN -> WRONG_SPACE;
            case CONTROLLER_BLOCK -> READY; default -> NOT_CONTROLLER; };
    }
    private static boolean onFace(Bounds bounds, BlockPos position, Direction outward) {
        for (var axis : Direction.Axis.values()) {
            int value = position.get(axis);
            if (axis == outward.getAxis()) {
                int plane = outward.getAxisDirection() == Direction.AxisDirection.POSITIVE ? bounds.max().get(axis) : bounds.min().get(axis);
                if (value != plane) return false;
            } else if (value <= bounds.min().get(axis) || value >= bounds.max().get(axis)) return false;
        }
        return true;
    }
    private record Interval(int min, int max) {}
    private static List<Interval> intervals(int origin, Direction.Axis axis, Direction outward, EnclosureLimits limits) {
        var result = new ArrayList<Interval>();
        for (int length = limits.minSize(); length <= limits.maxSize(); length++) {
            if (axis == outward.getAxis()) {
                result.add(outward.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? new Interval(origin - length + 1, origin) : new Interval(origin, origin + length - 1));
            } else for (int before = 1; before < length - 1; before++) result.add(new Interval(origin - before, origin - before + length - 1));
        }
        return result;
    }
    private static int boundaryCount(EnclosureVolume volume, Bounds outer, Bounds inner, Kind kind) {
        return volume.count(outer, kind) - volume.count(inner, kind);
    }
    private static int size(Bounds bounds) {
        return Math.toIntExact(bounds.size(Direction.Axis.X) * bounds.size(Direction.Axis.Y) * bounds.size(Direction.Axis.Z));
    }
    private static int priority(Status status) {
        return switch (status) {
            case WRONG_SPACE -> 7; case PARTIAL_UNLOADED -> 6; case CONTROLLER_CONFLICT -> 5;
            case INVALID_COMPUTER -> 4; case COMPUTER_LIMIT -> 3; case ILLEGAL_INTERNAL -> 2; default -> 0;
        };
    }
    private static Result failure(Status status) { return new Result(status, Optional.empty()); }
    private EnclosureScanner() {}
}
