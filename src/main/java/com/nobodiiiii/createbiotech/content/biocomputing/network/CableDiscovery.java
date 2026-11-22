package com.nobodiiiii.createbiotech.content.biocomputing.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Loaded-only topology walk. Readers and other Computers are endpoints, not bridges. */
public final class CableDiscovery {
    private CableDiscovery() {}
    public enum Kind { EMPTY, CABLE, READER, COMPUTER, ENDPOINT }
    public record Cell(Kind kind, Direction facing, int cableFaces) {
        public Cell(Kind kind, Direction facing) { this(kind, facing, 63); }
        boolean open(Direction face) { return (cableFaces & (1 << face.ordinal())) != 0; }
        public Cell { Objects.requireNonNull(kind); if (kind == Kind.READER) Objects.requireNonNull(facing); }
    }
    public interface World {
        boolean loaded(BlockPos pos);
        Cell cell(BlockPos pos);
        default boolean acceptsCable(BlockPos pos, Direction side) { return true; }
        /** Limit a walk to one physical space before asking whether a neighboring chunk can be read. */
        default Predicate<BlockPos> traversalScope(BlockPos origin) { return ignored -> true; }
    }
    private record Edge(BlockPos pos, Direction travel) {}
    public record Connection(BlockPos position, Direction side) {
        public Connection { position = Objects.requireNonNull(position).immutable(); Objects.requireNonNull(side); }
    }

    /** A physical cable component. Endpoint faces are terminals, never edges through a device. */
    public record Segment(List<BlockPos> cables, List<Connection> endpoints) {
        public Segment {
            cables = cables.stream().map(BlockPos::immutable).toList();
            endpoints = List.copyOf(endpoints);
        }
    }
    private static final Comparator<BlockPos> POSITION_ORDER = Comparator.comparingInt((BlockPos pos) -> pos.getY())
        .thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX);
    private static final Comparator<Connection> CONNECTION_ORDER = Comparator.comparing(Connection::position, POSITION_ORDER)
        .thenComparingInt(connection -> connection.side().ordinal());

    /**
     * Complete physical components touching the owner's accepted faces, unlike the merged logical peer list.
     * A single cache/work budget covers all source faces; closed advertisements do not remove physical terminals.
     * This is an observation, not a grant to deliver data or an authority to keep a stale topology alive.
     */
    public static List<Segment> segments(World world, BlockPos origin, int maxVisited, int maxEndpoints) {
        Objects.requireNonNull(world);
        origin = Objects.requireNonNull(origin).immutable();
        if (maxVisited < 1 || maxEndpoints < 1) throw new IllegalArgumentException("Positive topology limits required");
        if (!world.loaded(origin)) throw new DiscoveryException("topology_unloaded");
        var scope = world.traversalScope(origin);
        if (!scope.test(origin)) throw new DiscoveryException("access_denied");
        var cells = new HashMap<BlockPos, Cell>();
        var expanded = new HashSet<BlockPos>();
        var peers = new HashSet<BlockPos>();
        var result = new ArrayList<Segment>();
        for (Direction direction : Direction.values()) {
            if (!world.acceptsCable(origin, direction)) continue;
            var start = origin.relative(direction);
            if (!scope.test(start) || expanded.contains(start)) continue;
            var queue = new ArrayDeque<Edge>();
            queue.addLast(new Edge(start, direction));
            var cables = new HashSet<BlockPos>();
            var terminals = new HashSet<Connection>();
            terminals.add(new Connection(origin, direction));
            while (!queue.isEmpty()) {
                var edge = queue.removeFirst();
                if (!scope.test(edge.pos())) continue;
                Direction side = edge.travel().getOpposite();
                if (edge.pos().equals(origin)) {
                    if (world.acceptsCable(origin, side)) terminals.add(new Connection(origin, side));
                    continue;
                }
                var cell = segmentCell(world, edge.pos(), cells, maxVisited);
                if (cell.kind() == Kind.CABLE && cell.open(edge.travel().getOpposite()) && expanded.add(edge.pos())) {
                    cables.add(edge.pos());
                    for (var face : Direction.values()) if (cell.open(face)) queue.addLast(new Edge(edge.pos().relative(face), face));
                }
                if (cell.kind() == Kind.ENDPOINT && world.acceptsCable(edge.pos(), side)) {
                    peers.add(edge.pos());
                    if (peers.size() > maxEndpoints) throw new DiscoveryException("endpoint_limit");
                    terminals.add(new Connection(edge.pos(), side));
                }
            }
            // An accepted face looking at air is not a private, free-standing communication channel.
            if (!cables.isEmpty() || terminals.size() > 1)
                result.add(new Segment(cables.stream().sorted(POSITION_ORDER).toList(),
                    terminals.stream().sorted(CONNECTION_ORDER).toList()));
        }
        return List.copyOf(result);
    }

    private static Cell segmentCell(World world, BlockPos pos, HashMap<BlockPos, Cell> cells, int maxVisited) {
        var cached = cells.get(pos);
        if (cached != null) return cached;
        if (cells.size() >= maxVisited) throw new DiscoveryException("topology_limit");
        if (!world.loaded(pos)) throw new DiscoveryException("topology_unloaded");
        var cell = Objects.requireNonNull(world.cell(pos));
        cells.put(pos, cell);
        return cell;
    }

    public static List<BlockPos> scan(World world, BlockPos origin, int maxVisited, int maxReaders) {
		return walk(world, origin, maxVisited, maxReaders, false).stream().map(Connection::position).distinct().toList();
	}
	public static List<BlockPos> scanEndpoints(World world, BlockPos origin, int maxVisited, int maxEndpoints) {
		return scanEndpointConnections(world, origin, maxVisited, maxEndpoints).stream().map(Connection::position).distinct().toList();
	}
    public static List<Connection> scanEndpointConnections(World world, BlockPos origin, int maxVisited, int maxEndpoints) {
        return walk(world, origin, maxVisited, maxEndpoints, true);
    }

    /** Cosmetic shortest cable path, including both devices. The whole scan must still pass its limits. */
    public static List<BlockPos> findEndpointPath(World world, BlockPos origin, BlockPos target, int maxVisited, int maxEndpoints) {
        var path = new ArrayList<BlockPos>();
        walk(world, origin, maxVisited, maxEndpoints, true, Objects.requireNonNull(target), path);
        return List.copyOf(path);
    }
	private static List<Connection> walk(World world, BlockPos origin, int maxVisited, int maxResults, boolean endpoints) {
        return walk(world, origin, maxVisited, maxResults, endpoints, null, null);
    }
    private static List<Connection> walk(World world, BlockPos origin, int maxVisited, int maxResults, boolean endpoints,
            BlockPos target, List<BlockPos> path) {
        Objects.requireNonNull(world); Objects.requireNonNull(origin);
		if (maxVisited < 1 || maxResults < 1) throw new IllegalArgumentException("Positive topology limits required");
        var scope = world.traversalScope(origin);
        if (!scope.test(origin)) throw new DiscoveryException("access_denied");
        var queue = new ArrayDeque<Edge>();
        var cells = new HashMap<BlockPos, Cell>();
        var expanded = new HashSet<BlockPos>();
        var readers = new HashSet<BlockPos>();
        var connections = new HashSet<Connection>();
        var parents = target == null ? null : new HashMap<BlockPos, BlockPos>();
		enqueueSource(world, queue, origin);
        while (!queue.isEmpty()) {
            Edge edge = queue.removeFirst();
            if (edge.pos().equals(origin) || !scope.test(edge.pos())) continue;
            Cell cell = cells.get(edge.pos());
            if (cell == null) {
                if (cells.size() >= maxVisited) throw new DiscoveryException("topology_limit");
                if (!world.loaded(edge.pos())) throw new DiscoveryException("topology_unloaded");
                cell = Objects.requireNonNull(world.cell(edge.pos()));
                cells.put(edge.pos(), cell);
            }
			if (cell.kind() == Kind.CABLE && cell.open(edge.travel().getOpposite()) && expanded.add(edge.pos())) {
                if (parents != null) parents.put(edge.pos(), edge.pos().relative(edge.travel().getOpposite()));
                for (var face : Direction.values()) if (cell.open(face)) queue.addLast(new Edge(edge.pos().relative(face), face));
            }
            // The same reader can first be encountered on a non-connector side.
			boolean match = endpoints ? cell.kind() == Kind.ENDPOINT && world.acceptsCable(edge.pos(), edge.travel().getOpposite())
				: cell.kind() == Kind.READER && cell.facing() == edge.travel();
			if (match) {
                readers.add(edge.pos());
                connections.add(new Connection(edge.pos(), edge.travel().getOpposite()));
				if (readers.size() > maxResults) throw new DiscoveryException(endpoints ? "endpoint_limit" : "reader_limit");
                if (edge.pos().equals(target) && path.isEmpty()) {
                    path.add(edge.pos().immutable());
                    var previous = edge.pos().relative(edge.travel().getOpposite());
                    while (!previous.equals(origin)) {
                        path.add(previous.immutable()); previous = parents.get(previous);
                    }
                    path.add(origin.immutable()); Collections.reverse(path);
                }
            }
        }
		if (!endpoints && readers.isEmpty()) throw new DiscoveryException("no_readers");
        return connections.stream().sorted(Comparator.comparingInt((Connection connection) -> connection.position().getX())
            .thenComparingInt(connection -> connection.position().getY()).thenComparingInt(connection -> connection.position().getZ())
            .thenComparingInt(connection -> connection.side().ordinal())).toList();
    }
	private static void enqueueSource(World world, ArrayDeque<Edge> queue, BlockPos pos) {
		for (Direction direction : Direction.values())
			if (world.acceptsCable(pos, direction)) queue.addLast(new Edge(pos.relative(direction), direction));
    }
    public static final class DiscoveryException extends IllegalArgumentException {
        private final String code;
        public DiscoveryException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
