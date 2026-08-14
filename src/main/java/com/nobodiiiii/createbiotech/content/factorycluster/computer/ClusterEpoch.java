package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Immutable membership, profiles, and relocatable geometry for one active epoch. */
public record ClusterEpoch(UUID epochId, UUID clusterId, UUID computerStructureMemberId,
	UUID coordinatorId, SpaceAddress coordinatorAddress, BoundingBox bounds, List<EpochNode> nodes) {
	private static final int VERSION = 1;
	private static final int MAX_NODES = 256;
	private static final int MAX_VOLUME = 4096;
	private static final Set<String> TOP_KEYS = Set.of("Version", "EpochId", "ClusterId",
		"StructureMemberId", "CoordinatorId", "CoordinatorAddress", "Bounds", "Nodes");
	private static final Set<String> BOUNDS_KEYS = Set.of("MinX", "MinY", "MinZ",
		"MaxX", "MaxY", "MaxZ");
	private static final Set<String> NODE_KEYS = Set.of("ComputerId", "Address", "Profile");
	private static final Set<String> ADDRESS_KEYS = Set.of("Dimension", "Pos");
	private static final Set<String> SUBLEVEL_ADDRESS_KEYS = Set.of("Dimension", "Pos", "SubLevel");
	private static final List<Rotation> ROTATIONS = rotations();

	public ClusterEpoch {
		Objects.requireNonNull(epochId, "epochId");
		Objects.requireNonNull(clusterId, "clusterId");
		Objects.requireNonNull(computerStructureMemberId, "computerStructureMemberId");
		Objects.requireNonNull(coordinatorId, "coordinatorId");
		Objects.requireNonNull(coordinatorAddress, "coordinatorAddress");
		bounds = copyBounds(Objects.requireNonNull(bounds, "bounds"));
		nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
		validate(bounds, nodes, coordinatorId, coordinatorAddress);
	}

	@Override
	public BoundingBox bounds() {
		return copyBounds(bounds);
	}

	public static ClusterEpoch freeze(UUID clusterId, UUID structureMemberId,
		ComputerStructureSnapshot snapshot) {
		Objects.requireNonNull(clusterId, "clusterId");
		Objects.requireNonNull(structureMemberId, "structureMemberId");
		Objects.requireNonNull(snapshot, "snapshot");
		if (snapshot.nodes().isEmpty() || snapshot.nodes().size() > MAX_NODES)
			throw new IllegalArgumentException("epoch requires 1..256 physical nodes");

		List<ComputerStructureNode> current = new ArrayList<>(snapshot.nodes());
		current.sort(Comparator.comparing(ComputerStructureNode::computerId));
		List<EpochNode> frozen = new ArrayList<>(current.size());
		UUID previous = null;
		SpaceAddress first = null;
		for (ComputerStructureNode node : current) {
			if (node == null || node.computerId() == null || node.address() == null || node.profile() == null)
				throw new IllegalArgumentException("every physical node requires identity, address, and profile");
			if (previous != null && previous.compareTo(node.computerId()) >= 0)
				throw new IllegalArgumentException("duplicate computer identity");
			previous = node.computerId();
			if (first == null) first = node.address();
			else requireSameSpace(first, node.address());
			frozen.add(new EpochNode(node.computerId(), node.address(), node.profile()));
		}
		EpochNode coordinator = frozen.getFirst();
		return new ClusterEpoch(UUID.randomUUID(), clusterId, structureMemberId,
			coordinator.computerId(), coordinator.address(), snapshot.bounds(), frozen);
	}

	public ClusterEpoch relocate(ComputerStructureSnapshot current) {
		Objects.requireNonNull(current, "current");
		Map<UUID, ComputerStructureNode> currentById = new HashMap<>();
		for (ComputerStructureNode node : current.nodes())
			if (currentById.put(node.computerId(), node) != null)
				throw new IllegalArgumentException("duplicate current computer identity");

		SpaceAddress frozenSpace = nodes.getFirst().address();
		List<ComputerStructureNode> matched = new ArrayList<>(nodes.size());
		for (EpochNode frozen : nodes) {
			ComputerStructureNode node = currentById.get(frozen.computerId());
			if (node == null) throw new IllegalArgumentException("missing frozen computer " + frozen.computerId());
			if (!frozen.profile().equals(node.profile()))
				throw new IllegalArgumentException("frozen profile changed for " + frozen.computerId());
			requireSameSpace(frozenSpace, node.address());
			matched.add(node);
		}

		BoundingBox currentBounds = current.bounds();
		for (Rotation rotation : ROTATIONS) {
			long[] transformed = transformedBounds(bounds, rotation);
			long tx = (long) currentBounds.minX() - transformed[0];
			long ty = (long) currentBounds.minY() - transformed[1];
			long tz = (long) currentBounds.minZ() - transformed[2];
			if (transformed[3] + tx != currentBounds.maxX()
				|| transformed[4] + ty != currentBounds.maxY()
				|| transformed[5] + tz != currentBounds.maxZ()) continue;

			boolean positionsMatch = true;
			for (int i = 0; i < nodes.size(); i++) {
				long[] rotated = rotation.apply(nodes.get(i).address().localPos());
				BlockPos actual = matched.get(i).address().localPos();
				if (rotated[0] + tx != actual.getX() || rotated[1] + ty != actual.getY()
					|| rotated[2] + tz != actual.getZ()) {
					positionsMatch = false;
					break;
				}
			}
			if (!positionsMatch) continue;

			List<EpochNode> relocated = new ArrayList<>(nodes.size());
			for (int i = 0; i < nodes.size(); i++) {
				EpochNode frozen = nodes.get(i);
				relocated.add(new EpochNode(frozen.computerId(), matched.get(i).address(), frozen.profile()));
			}
			SpaceAddress relocatedCoordinator = relocated.stream()
				.filter(node -> node.computerId().equals(coordinatorId)).findFirst().orElseThrow().address();
			return new ClusterEpoch(epochId, clusterId, computerStructureMemberId, coordinatorId,
				relocatedCoordinator, currentBounds, relocated);
		}
		throw new IllegalArgumentException("current structure is not a rigid proper relocation");
	}

	public int totalPatternRangeBonus() {
		int total = 0;
		for (EpochNode node : nodes)
			if (node.profile().kind() == NodeKind.WANDERING_TRADER)
				total = Math.addExact(total, node.profile().patternRangeBonus());
		return total;
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", VERSION);
		tag.putUUID("EpochId", epochId);
		tag.putUUID("ClusterId", clusterId);
		tag.putUUID("StructureMemberId", computerStructureMemberId);
		tag.putUUID("CoordinatorId", coordinatorId);
		tag.put("CoordinatorAddress", coordinatorAddress.save());
		tag.put("Bounds", saveBounds(bounds));
		ListTag nodeTags = new ListTag();
		for (EpochNode node : nodes) nodeTags.add(saveNode(node));
		tag.put("Nodes", nodeTags);
		return tag;
	}

	public static Optional<ClusterEpoch> load(CompoundTag tag) {
		if (tag == null || !tag.getAllKeys().equals(TOP_KEYS)
			|| !hasType(tag, "Version", Tag.TAG_INT) || tag.getInt("Version") != VERSION
			|| !hasUuid(tag, "EpochId") || !hasUuid(tag, "ClusterId")
			|| !hasUuid(tag, "StructureMemberId") || !hasUuid(tag, "CoordinatorId")
			|| !hasType(tag, "CoordinatorAddress", Tag.TAG_COMPOUND)
			|| !hasType(tag, "Bounds", Tag.TAG_COMPOUND) || !hasType(tag, "Nodes", Tag.TAG_LIST))
			return Optional.empty();
		try {
			Optional<SpaceAddress> coordinatorAddress = loadAddress(tag.getCompound("CoordinatorAddress"));
			if (coordinatorAddress.isEmpty()) return Optional.empty();
			CompoundTag boundsTag = tag.getCompound("Bounds");
			if (!hasExactIntKeys(boundsTag, BOUNDS_KEYS)) return Optional.empty();
			BoundingBox bounds = new BoundingBox(boundsTag.getInt("MinX"), boundsTag.getInt("MinY"),
				boundsTag.getInt("MinZ"), boundsTag.getInt("MaxX"), boundsTag.getInt("MaxY"),
				boundsTag.getInt("MaxZ"));

			ListTag nodeTags = (ListTag) tag.get("Nodes");
			if (nodeTags == null || nodeTags.size() < 1 || nodeTags.size() > MAX_NODES
				|| nodeTags.getElementType() != Tag.TAG_COMPOUND) return Optional.empty();
			List<EpochNode> nodes = new ArrayList<>(nodeTags.size());
			for (Tag value : nodeTags) {
				if (!(value instanceof CompoundTag child) || !child.getAllKeys().equals(NODE_KEYS)
					|| !hasUuid(child, "ComputerId") || !hasType(child, "Address", Tag.TAG_COMPOUND)
					|| !hasType(child, "Profile", Tag.TAG_COMPOUND)) return Optional.empty();
				Optional<SpaceAddress> address = loadAddress(child.getCompound("Address"));
				Optional<ComputerProfile> profile = ComputerProfile.load(child.getCompound("Profile"));
				if (address.isEmpty() || profile.isEmpty()) return Optional.empty();
				nodes.add(new EpochNode(child.getUUID("ComputerId"), address.orElseThrow(), profile.orElseThrow()));
			}
			return Optional.of(new ClusterEpoch(tag.getUUID("EpochId"), tag.getUUID("ClusterId"),
				tag.getUUID("StructureMemberId"), tag.getUUID("CoordinatorId"),
				coordinatorAddress.orElseThrow(), bounds, nodes));
		} catch (IllegalArgumentException | ArithmeticException | IllegalStateException exception) {
			return Optional.empty();
		}
	}

	private static void validate(BoundingBox bounds, List<EpochNode> nodes, UUID coordinatorId,
		SpaceAddress coordinatorAddress) {
		long sizeX = (long) bounds.maxX() - bounds.minX() + 1;
		long sizeY = (long) bounds.maxY() - bounds.minY() + 1;
		long sizeZ = (long) bounds.maxZ() - bounds.minZ() + 1;
		long volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		if (sizeX < 3 || sizeY < 3 || sizeZ < 3 || volume > MAX_VOLUME
			|| nodes.isEmpty() || nodes.size() > MAX_NODES)
			throw new IllegalArgumentException("invalid epoch bounds or node limits");

		UUID previous = null;
		Set<BlockPos> positions = new HashSet<>();
		SpaceAddress first = null;
		EpochNode coordinator = null;
		for (EpochNode node : nodes) {
			Objects.requireNonNull(node, "node");
			if (previous != null && previous.compareTo(node.computerId()) >= 0)
				throw new IllegalArgumentException("nodes are not uniquely UUID-sorted");
			previous = node.computerId();
			if (!contains(bounds, node.address().localPos()) || !positions.add(node.address().localPos()))
				throw new IllegalArgumentException("node position is outside bounds or duplicated");
			if (first == null) first = node.address();
			else requireSameSpace(first, node.address());
			if (node.computerId().equals(coordinatorId)) coordinator = node;
		}
		if (!nodes.getFirst().computerId().equals(coordinatorId) || coordinator == null
			|| !coordinator.address().equals(coordinatorAddress))
			throw new IllegalArgumentException("coordinator is not the minimum UUID at its frozen address");
	}

	private static CompoundTag saveBounds(BoundingBox bounds) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("MinX", bounds.minX());
		tag.putInt("MinY", bounds.minY());
		tag.putInt("MinZ", bounds.minZ());
		tag.putInt("MaxX", bounds.maxX());
		tag.putInt("MaxY", bounds.maxY());
		tag.putInt("MaxZ", bounds.maxZ());
		return tag;
	}

	private static CompoundTag saveNode(EpochNode node) {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("ComputerId", node.computerId());
		tag.put("Address", node.address().save());
		tag.put("Profile", node.profile().save());
		return tag;
	}

	private static Optional<SpaceAddress> loadAddress(CompoundTag tag) {
		boolean hasSubLevel = tag.contains("SubLevel");
		if (!tag.getAllKeys().equals(hasSubLevel ? SUBLEVEL_ADDRESS_KEYS : ADDRESS_KEYS))
			return Optional.empty();
		return SpaceAddress.tryLoad(tag);
	}

	private static void requireSameSpace(SpaceAddress expected, SpaceAddress actual) {
		if (!expected.dimension().equals(actual.dimension())
			|| !Objects.equals(expected.subLevelId(), actual.subLevelId()))
			throw new IllegalArgumentException("computer nodes span dimensions or Sable spaces");
	}

	private static boolean contains(BoundingBox bounds, BlockPos pos) {
		return pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
			&& pos.getY() >= bounds.minY() && pos.getY() <= bounds.maxY()
			&& pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
	}

	private static BoundingBox copyBounds(BoundingBox bounds) {
		return new BoundingBox(bounds.minX(), bounds.minY(), bounds.minZ(),
			bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}

	private static long[] transformedBounds(BoundingBox bounds, Rotation rotation) {
		long[] result = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
			Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
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
		List<Rotation> rotations = new ArrayList<>(24);
		int[][] permutations = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2},
			{1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
		for (int[] axes : permutations) {
			int parity = permutationParity(axes);
			for (int sx : new int[] {-1, 1})
				for (int sy : new int[] {-1, 1})
					for (int sz : new int[] {-1, 1})
						if (parity * sx * sy * sz == 1)
							rotations.add(new Rotation(axes.clone(), new int[] {sx, sy, sz}));
		}
		if (rotations.size() != 24) throw new IllegalStateException("proper cube rotation enumeration failed");
		return List.copyOf(rotations);
	}

	private static int permutationParity(int[] axes) {
		int inversions = 0;
		for (int i = 0; i < axes.length; i++)
			for (int j = i + 1; j < axes.length; j++)
				if (axes[i] > axes[j]) inversions++;
		return inversions % 2 == 0 ? 1 : -1;
	}

	private static boolean hasExactIntKeys(CompoundTag tag, Set<String> keys) {
		return tag.getAllKeys().equals(keys) && keys.stream().allMatch(key -> hasType(tag, key, Tag.TAG_INT));
	}

	private static boolean hasUuid(CompoundTag tag, String key) {
		return hasType(tag, key, Tag.TAG_INT_ARRAY) && tag.hasUUID(key);
	}

	private static boolean hasType(CompoundTag tag, String key, int type) {
		Tag value = tag.get(key);
		return value != null && value.getId() == type;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof ClusterEpoch epoch)) return false;
		return epochId.equals(epoch.epochId) && clusterId.equals(epoch.clusterId)
			&& computerStructureMemberId.equals(epoch.computerStructureMemberId)
			&& coordinatorId.equals(epoch.coordinatorId) && coordinatorAddress.equals(epoch.coordinatorAddress)
			&& sameBounds(bounds, epoch.bounds) && nodes.equals(epoch.nodes);
	}

	@Override
	public int hashCode() {
		return Objects.hash(epochId, clusterId, computerStructureMemberId, coordinatorId,
			coordinatorAddress, bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(),
			bounds.maxZ(), nodes);
	}

	private static boolean sameBounds(BoundingBox first, BoundingBox second) {
		return first.minX() == second.minX() && first.minY() == second.minY() && first.minZ() == second.minZ()
			&& first.maxX() == second.maxX() && first.maxY() == second.maxY() && first.maxZ() == second.maxZ();
	}

	private record Rotation(int[] axes, int[] signs) {
		long[] apply(BlockPos pos) {
			return apply(pos.getX(), pos.getY(), pos.getZ());
		}

		long[] apply(int x, int y, int z) {
			long[] source = {x, y, z};
			return new long[] {signs[0] * source[axes[0]], signs[1] * source[axes[1]],
				signs[2] * source[axes[2]]};
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Rotation rotation && Arrays.equals(axes, rotation.axes)
				&& Arrays.equals(signs, rotation.signs);
		}

		@Override
		public int hashCode() {
			return 31 * Arrays.hashCode(axes) + Arrays.hashCode(signs);
		}
	}
}
