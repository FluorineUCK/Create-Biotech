package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.registry.CBConfigs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Pure bounded cuboid scanner. All world access is supplied through {@link View}. */
public final class ComputerStructureScanner {
	private ComputerStructureScanner() {}

	public interface View {
		boolean isLoaded(BlockPos pos);
		boolean sameSpace(BlockPos origin, BlockPos pos);
		CellKind cellAt(BlockPos pos);
		@Nullable ComputerObservation computerAt(BlockPos pos);
	}

	public enum CellKind { AIR, CASING, COMPUTER, ALLOWED_INTERNAL, ILLEGAL }
	public enum ProfileLoadState { EMPTY, VALID, CORRUPT }

	public record ComputerObservation(@Nullable UUID computerId,
		@Nullable UUID computerStructureMemberId, ProfileLoadState profileState,
		@Nullable ComputerProfile profile, @Nullable SpaceAddress address) {}

	public enum State {
		UNFORMED, VALID, VALID_NOT_READY, PARTIAL_UNLOADED, SIZE, VOLUME,
		OPEN_SHELL, ILLEGAL_INTERNAL, DISCONNECTED_BUS, NODE_LIMIT, SPACE,
		IDENTITY_INVALID, STRUCTURE_IDENTITY_CONFLICT, AMBIGUOUS
	}

	public record Limits(int minSize, int maxSize, int maxVolume, int maxNodes) {
		public Limits {
			if (minSize < 3 || minSize > maxSize || maxSize > 16 || maxNodes < 1 || maxNodes > 256
				|| maxVolume != checkedCube(maxSize))
				throw new IllegalArgumentException("invalid computer scanner limits");
		}

		public static Limits fromConfig() {
			return fromValues(CBConfigs.SERVER.factoryCluster.computerMinSize.get(),
				CBConfigs.SERVER.factoryCluster.computerMaxSize.get(),
				CBConfigs.SERVER.factoryCluster.computerMaxNodes.get());
		}

		static Limits fromValues(int configuredMin, int configuredMax, int configuredNodes) {
			int max = Math.clamp(configuredMax, 3, 16);
			int min = Math.clamp(configuredMin, 3, max);
			int nodes = Math.clamp(configuredNodes, 1, 256);
			return new Limits(min, max, checkedCube(max), nodes);
		}

		int observationWindowVolume() {
			return checkedCube(Math.addExact(Math.multiplyExact(2, maxSize - 2), 1));
		}

		private static int checkedCube(int value) {
			return Math.toIntExact(Math.multiplyExact(Math.multiplyExact((long) value, value), value));
		}
	}

	public record ScanResult(State state, @Nullable ComputerStructureSnapshot snapshot,
		@Nullable UUID observedStructureMemberId) {
		public ScanResult {
			Objects.requireNonNull(state, "state");
		}
	}

	public static ScanResult scan(View view, BlockPos seed, Limits limits) {
		Objects.requireNonNull(view, "view");
		Objects.requireNonNull(seed, "seed");
		Objects.requireNonNull(limits, "limits");
		seed = seed.immutable();
		ObservationWindow window = new ObservationWindow(view, seed, limits.maxSize() - 2);
		Observation seedObservation = window.observe(seed);
		if (seedObservation.status == ObservationStatus.UNLOADED)
			return result(State.PARTIAL_UNLOADED, null, null);
		if (seedObservation.status == ObservationStatus.WRONG_SPACE)
			return result(State.SPACE, null, null);
		if (seedObservation.cell != CellKind.COMPUTER)
			return result(State.UNFORMED, null, null);
		if (!validComputerObservation(seedObservation.computer, seed))
			return result(State.IDENTITY_INVALID, null, memberId(seedObservation.computer));

		window.fill();
		PrefixVolumes prefix = new PrefixVolumes(window);
		List<AxisInterval> xs = axisIntervals(window, Axis.X, limits);
		List<AxisInterval> ys = axisIntervals(window, Axis.Y, limits);
		List<AxisInterval> zs = axisIntervals(window, Axis.Z, limits);

		long[] candidates = candidateKeys(xs, ys, zs, limits.maxVolume());
		ComputerStructureSnapshot accepted = null;
		State acceptedState = null;
		UUID acceptedMember = null;
		Set<State> failures = new HashSet<>();
		if (hasOutOfRangeCasingSpan(window, limits)) failures.add(State.SIZE);
		for (long key : candidates) {
			Candidate candidate = decodeCandidate(key, window);
			CandidateResult candidateResult = inspectCandidate(candidate, window, prefix, limits);
			if (candidateResult.snapshot != null) {
				if (accepted != null)
					return result(State.AMBIGUOUS, null, acceptedMember);
				accepted = candidateResult.snapshot;
				acceptedState = candidateResult.state;
				acceptedMember = candidateResult.memberId;
			} else failures.add(candidateResult.state);
		}
		if (accepted != null) return result(acceptedState, accepted, acceptedMember);
		for (State priority : List.of(State.PARTIAL_UNLOADED, State.SPACE, State.IDENTITY_INVALID,
			State.STRUCTURE_IDENTITY_CONFLICT, State.NODE_LIMIT, State.ILLEGAL_INTERNAL,
			State.DISCONNECTED_BUS, State.SIZE, State.OPEN_SHELL, State.VOLUME))
			if (failures.contains(priority)) return result(priority, null, memberId(seedObservation.computer));
		return result(State.SIZE, null, memberId(seedObservation.computer));
	}

	private static CandidateResult inspectCandidate(Candidate candidate, ObservationWindow window,
		PrefixVolumes prefix, Limits limits) {
		int volume = candidate.volume();
		if (volume > limits.maxVolume()) return CandidateResult.failure(State.VOLUME);
		Candidate interior = candidate.interior();
		int interiorVolume = interior.volume();
		int shellVolume = volume - interiorVolume;
		int unloadedTotal = prefix.count(Flag.UNLOADED, candidate);
		int wrongTotal = prefix.count(Flag.WRONG_SPACE, candidate);
		int unloadedShell = unloadedTotal - prefix.count(Flag.UNLOADED, interior);
		int wrongShell = wrongTotal - prefix.count(Flag.WRONG_SPACE, interior);
		int casingShell = prefix.count(Flag.CASING, candidate) - prefix.count(Flag.CASING, interior);
		int knownShell = shellVolume - unloadedShell - wrongShell;
		int illegalInterior = prefix.count(Flag.ILLEGAL, interior);
		int computers = prefix.count(Flag.COMPUTER, interior);
		if (unloadedTotal > 0 && casingShell == knownShell && illegalInterior == 0
			&& computers <= limits.maxNodes()) return CandidateResult.failure(State.PARTIAL_UNLOADED);
		if (wrongTotal > 0 && casingShell == knownShell && illegalInterior == 0
			&& computers <= limits.maxNodes()) return CandidateResult.failure(State.SPACE);
		if (unloadedTotal > 0 || wrongTotal > 0 || casingShell != shellVolume)
			return CandidateResult.failure(State.OPEN_SHELL);
		if (illegalInterior > 0) return CandidateResult.failure(State.ILLEGAL_INTERNAL);
		if (computers > limits.maxNodes()) return CandidateResult.failure(State.NODE_LIMIT);

		List<NodeAt> observedNodes = new ArrayList<>(computers);
		Set<UUID> ids = new HashSet<>();
		Set<UUID> memberIds = new LinkedHashSet<>();
		boolean notReady = false;
		for (int x = interior.minX; x <= interior.maxX; x++)
			for (int y = interior.minY; y <= interior.maxY; y++)
				for (int z = interior.minZ; z <= interior.maxZ; z++) {
					Observation observation = window.at(x, y, z);
					if (observation.cell != CellKind.COMPUTER) continue;
					BlockPos pos = window.worldPos(x, y, z);
					if (!validComputerObservation(observation.computer, pos)
						|| !ids.add(observation.computer.computerId()))
						return CandidateResult.failure(State.IDENTITY_INVALID);
					if (observation.computer.computerStructureMemberId() != null)
						memberIds.add(observation.computer.computerStructureMemberId());
					if (memberIds.size() > 1)
						return CandidateResult.failure(State.STRUCTURE_IDENTITY_CONFLICT);
					notReady |= observation.computer.profileState() == ProfileLoadState.EMPTY;
					observedNodes.add(new NodeAt(pos, new ComputerStructureNode(observation.computer.computerId(),
						observation.computer.address(), observation.computer.profile())));
				}
		if (observedNodes.isEmpty()) return CandidateResult.failure(State.IDENTITY_INVALID);

		Set<BlockPos> reachedCasing = floodCasing(candidate, window);
		for (NodeAt node : observedNodes) {
			boolean connected = false;
			for (Direction direction : Direction.values())
				if (reachedCasing.contains(node.pos.relative(direction))) {
					connected = true;
					break;
				}
			if (!connected) return CandidateResult.failure(State.DISCONNECTED_BUS);
		}
		List<ComputerStructureNode> nodes = observedNodes.stream().map(NodeAt::node)
			.sorted(Comparator.comparing(ComputerStructureNode::computerId)).toList();
		BoundingBox bounds = candidate.bounds(window);
		UUID memberId = memberIds.stream().findFirst().orElse(null);
		try {
			ComputerStructureSnapshot snapshot = new ComputerStructureSnapshot(bounds, nodes, reachedCasing,
				chunksFor(bounds));
			return new CandidateResult(notReady ? State.VALID_NOT_READY : State.VALID, snapshot, memberId);
		} catch (IllegalArgumentException exception) {
			return CandidateResult.failure(State.IDENTITY_INVALID);
		}
	}

	private static Set<BlockPos> floodCasing(Candidate candidate, ObservationWindow window) {
		Set<BlockPos> reached = new LinkedHashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		BlockPos corner = window.worldPos(candidate.minX, candidate.minY, candidate.minZ);
		reached.add(corner);
		queue.add(corner);
		while (!queue.isEmpty()) {
			BlockPos current = queue.removeFirst();
			for (Direction direction : Direction.values()) {
				BlockPos next = current.relative(direction);
				if (!candidate.contains(window.x(next), window.y(next), window.z(next))
					|| reached.contains(next)) continue;
				Observation observation = window.at(window.x(next), window.y(next), window.z(next));
				if (observation.status == ObservationStatus.NORMAL && observation.cell == CellKind.CASING) {
					next = next.immutable();
					reached.add(next);
					queue.addLast(next);
				}
			}
		}
		return reached;
	}

	private static boolean validComputerObservation(@Nullable ComputerObservation observation, BlockPos expectedPos) {
		if (observation == null || observation.computerId() == null || observation.address() == null
			|| observation.profileState() == null || !observation.address().localPos().equals(expectedPos)
			|| observation.profileState() == ProfileLoadState.CORRUPT) return false;
		return observation.profileState() == ProfileLoadState.EMPTY
			? observation.profile() == null : observation.profile() != null;
	}

	private static @Nullable UUID memberId(@Nullable ComputerObservation observation) {
		return observation == null ? null : observation.computerStructureMemberId();
	}

	private static List<AxisInterval> axisIntervals(ObservationWindow window, Axis axis, Limits limits) {
		List<AxisInterval> result = new ArrayList<>();
		int center = window.radius;
		for (int negative = 1; negative <= window.radius; negative++) {
			for (int positive = 1; positive <= window.radius; positive++) {
				int size = negative + positive + 1;
				if (size < limits.minSize() || size > limits.maxSize()) continue;
				result.add(new AxisInterval(center - negative, center + positive));
			}
		}
		return result;
	}

	private static boolean hasOutOfRangeCasingSpan(ObservationWindow window, Limits limits) {
		for (Axis axis : Axis.values())
			for (int negative = 1; negative <= window.radius; negative++) {
				Observation left = window.axis(axis, -negative);
				if (left.status != ObservationStatus.NORMAL || left.cell != CellKind.CASING) continue;
				for (int positive = 1; positive <= window.radius; positive++) {
					Observation right = window.axis(axis, positive);
					if (right.status != ObservationStatus.NORMAL || right.cell != CellKind.CASING) continue;
					int size = negative + positive + 1;
					if (size < limits.minSize() || size > limits.maxSize()) return true;
				}
			}
		return false;
	}

	private static long[] candidateKeys(List<AxisInterval> xs, List<AxisInterval> ys,
		List<AxisInterval> zs, int maxVolume) {
		LongArrayBuilder builder = new LongArrayBuilder(Math.min(1024, xs.size() * ys.size() * zs.size()));
		for (AxisInterval x : xs) for (AxisInterval y : ys) for (AxisInterval z : zs) {
			int volume = x.size() * y.size() * z.size();
			if (volume > maxVolume) continue;
			long key = volume;
			key = key << 5 | x.min;
			key = key << 5 | y.min;
			key = key << 5 | z.min;
			key = key << 5 | x.max;
			key = key << 5 | y.max;
			key = key << 5 | z.max;
			builder.add(key);
		}
		long[] result = builder.toArray();
		Arrays.sort(result);
		return result;
	}

	private static Candidate decodeCandidate(long key, ObservationWindow window) {
		int maxZ = (int) (key & 31); key >>>= 5;
		int maxY = (int) (key & 31); key >>>= 5;
		int maxX = (int) (key & 31); key >>>= 5;
		int minZ = (int) (key & 31); key >>>= 5;
		int minY = (int) (key & 31); key >>>= 5;
		int minX = (int) (key & 31);
		return new Candidate(minX, minY, minZ, maxX, maxY, maxZ);
	}

	private static Set<ChunkPos> chunksFor(BoundingBox bounds) {
		Set<ChunkPos> chunks = new LinkedHashSet<>();
		for (int x = Math.floorDiv(bounds.minX(), 16); x <= Math.floorDiv(bounds.maxX(), 16); x++)
			for (int z = Math.floorDiv(bounds.minZ(), 16); z <= Math.floorDiv(bounds.maxZ(), 16); z++)
				chunks.add(new ChunkPos(x, z));
		return chunks;
	}

	private static ScanResult result(State state, @Nullable ComputerStructureSnapshot snapshot,
		@Nullable UUID memberId) {
		return new ScanResult(state, snapshot, memberId);
	}

	private enum ObservationStatus { NORMAL, UNLOADED, WRONG_SPACE }
	private enum Axis { X, Y, Z }
	private enum Flag { UNLOADED, WRONG_SPACE, CASING, ILLEGAL, COMPUTER }

	private record Observation(ObservationStatus status, @Nullable CellKind cell,
		@Nullable ComputerObservation computer) {
		private static final Observation UNLOADED = new Observation(ObservationStatus.UNLOADED, null, null);
		private static final Observation WRONG_SPACE = new Observation(ObservationStatus.WRONG_SPACE, null, null);
	}

	private static final class ObservationWindow {
		private final View view;
		private final BlockPos seed;
		private final int radius;
		private final int side;
		private final int minX;
		private final int minY;
		private final int minZ;
		private final Observation[] observations;

		private ObservationWindow(View view, BlockPos seed, int radius) {
			this.view = view;
			this.seed = seed;
			this.radius = radius;
			this.side = radius * 2 + 1;
			this.minX = seed.getX() - radius;
			this.minY = seed.getY() - radius;
			this.minZ = seed.getZ() - radius;
			this.observations = new Observation[Math.multiplyExact(Math.multiplyExact(side, side), side)];
		}

		private Observation observe(BlockPos pos) {
			int x = x(pos), y = y(pos), z = z(pos);
			int index = index(x, y, z);
			Observation existing = observations[index];
			if (existing != null) return existing;
			Observation observed;
			if (!view.isLoaded(pos)) observed = Observation.UNLOADED;
			else if (!view.sameSpace(seed, pos)) observed = Observation.WRONG_SPACE;
			else {
				CellKind cell = Objects.requireNonNull(view.cellAt(pos), "view cell");
				ComputerObservation computer = cell == CellKind.COMPUTER ? view.computerAt(pos) : null;
				observed = new Observation(ObservationStatus.NORMAL, cell, computer);
			}
			observations[index] = observed;
			return observed;
		}

		private void fill() {
			for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) for (int z = 0; z < side; z++)
				if (observations[index(x, y, z)] == null) observe(worldPos(x, y, z));
		}

		private Observation at(int x, int y, int z) { return observations[index(x, y, z)]; }
		private BlockPos worldPos(int x, int y, int z) { return new BlockPos(minX + x, minY + y, minZ + z); }
		private int x(BlockPos pos) { return pos.getX() - minX; }
		private int y(BlockPos pos) { return pos.getY() - minY; }
		private int z(BlockPos pos) { return pos.getZ() - minZ; }
		private int index(int x, int y, int z) { return (x * side + y) * side + z; }

		private Observation axis(Axis axis, int distance) {
			return switch (axis) {
				case X -> at(radius + distance, radius, radius);
				case Y -> at(radius, radius + distance, radius);
				case Z -> at(radius, radius, radius + distance);
			};
		}
	}

	private static final class PrefixVolumes {
		private final int side;
		private final int stride;
		private final int plane;
		private final int[][] values;

		private PrefixVolumes(ObservationWindow window) {
			this.side = window.side + 1;
			this.stride = side;
			this.plane = side * side;
			this.values = new int[Flag.values().length][Math.multiplyExact(plane, side)];
			for (int x = 1; x < side; x++) for (int y = 1; y < side; y++) for (int z = 1; z < side; z++) {
				Observation observation = window.at(x - 1, y - 1, z - 1);
				for (Flag flag : Flag.values()) {
					int[] prefix = values[flag.ordinal()];
					int value = matches(flag, observation) ? 1 : 0;
					prefix[index(x, y, z)] = value
						+ prefix[index(x - 1, y, z)] + prefix[index(x, y - 1, z)] + prefix[index(x, y, z - 1)]
						- prefix[index(x - 1, y - 1, z)] - prefix[index(x - 1, y, z - 1)]
						- prefix[index(x, y - 1, z - 1)] + prefix[index(x - 1, y - 1, z - 1)];
				}
			}
		}

		private int count(Flag flag, Candidate box) {
			int[] prefix = values[flag.ordinal()];
			int x0 = box.minX, y0 = box.minY, z0 = box.minZ;
			int x1 = box.maxX + 1, y1 = box.maxY + 1, z1 = box.maxZ + 1;
			return prefix[index(x1, y1, z1)] - prefix[index(x0, y1, z1)] - prefix[index(x1, y0, z1)]
				- prefix[index(x1, y1, z0)] + prefix[index(x0, y0, z1)] + prefix[index(x0, y1, z0)]
				+ prefix[index(x1, y0, z0)] - prefix[index(x0, y0, z0)];
		}

		private int index(int x, int y, int z) { return x * plane + y * stride + z; }

		private static boolean matches(Flag flag, Observation observation) {
			return switch (flag) {
				case UNLOADED -> observation.status == ObservationStatus.UNLOADED;
				case WRONG_SPACE -> observation.status == ObservationStatus.WRONG_SPACE;
				case CASING -> observation.cell == CellKind.CASING;
				case ILLEGAL -> observation.cell == CellKind.ILLEGAL;
				case COMPUTER -> observation.cell == CellKind.COMPUTER;
			};
		}
	}

	private record AxisInterval(int min, int max) {
		private int size() { return max - min + 1; }
	}

	private record Candidate(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		private int volume() { return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1); }
		private Candidate interior() { return new Candidate(minX + 1, minY + 1, minZ + 1, maxX - 1, maxY - 1, maxZ - 1); }
		private boolean contains(int x, int y, int z) {
			return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
		}
		private BoundingBox bounds(ObservationWindow window) {
			BlockPos min = window.worldPos(minX, minY, minZ);
			BlockPos max = window.worldPos(maxX, maxY, maxZ);
			return new BoundingBox(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
		}
	}

	private record NodeAt(BlockPos pos, ComputerStructureNode node) {}
	private record CandidateResult(State state, @Nullable ComputerStructureSnapshot snapshot,
		@Nullable UUID memberId) {
		private static CandidateResult failure(State state) { return new CandidateResult(state, null, null); }
	}

	private static final class LongArrayBuilder {
		private long[] values;
		private int size;

		private LongArrayBuilder(int initialCapacity) { values = new long[Math.max(1, initialCapacity)]; }
		private void add(long value) {
			if (size == values.length) values = Arrays.copyOf(values, Math.multiplyExact(size, 2));
			values[size++] = value;
		}
		private long[] toArray() { return Arrays.copyOf(values, size); }
	}
}
