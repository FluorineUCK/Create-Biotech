package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Immutable, strictly persisted value snapshot of one accepted Computer cuboid. */
public final class ComputerStructureSnapshot {
	private static final int VERSION = 1;
	private static final int MAX_VOLUME = 4096;
	private static final int MAX_NODES = 256;
	private static final Set<String> TOP_KEYS = Set.of("Version", "Bounds", "Nodes",
		"CasingPositions", "ContainingChunks");
	private static final Set<String> BOUNDS_KEYS = Set.of("MinX", "MinY", "MinZ",
		"MaxX", "MaxY", "MaxZ");
	private static final Set<String> NODE_KEYS = Set.of("ComputerId", "Address");
	private static final Set<String> PROFILE_NODE_KEYS = Set.of("ComputerId", "Address", "Profile");
	private static final Set<String> ADDRESS_KEYS = Set.of("Dimension", "Pos");
	private static final Set<String> SUBLEVEL_ADDRESS_KEYS = Set.of("Dimension", "Pos", "SubLevel");

	private final int minX;
	private final int minY;
	private final int minZ;
	private final int maxX;
	private final int maxY;
	private final int maxZ;
	private final List<ComputerStructureNode> nodes;
	private final Set<BlockPos> casingPositions;
	private final Set<ChunkPos> containingChunks;

	public ComputerStructureSnapshot(BoundingBox bounds, List<ComputerStructureNode> nodes,
		Set<BlockPos> casingPositions, Set<ChunkPos> containingChunks) {
		Objects.requireNonNull(bounds, "bounds");
		this.minX = bounds.minX();
		this.minY = bounds.minY();
		this.minZ = bounds.minZ();
		this.maxX = bounds.maxX();
		this.maxY = bounds.maxY();
		this.maxZ = bounds.maxZ();
		Objects.requireNonNull(nodes, "nodes");
		this.nodes = List.copyOf(nodes);
		Objects.requireNonNull(casingPositions, "casingPositions");
		LinkedHashSet<BlockPos> immutableCasing = new LinkedHashSet<>();
		for (BlockPos pos : casingPositions)
			immutableCasing.add(Objects.requireNonNull(pos, "casing position").immutable());
		this.casingPositions = Collections.unmodifiableSet(immutableCasing);
		Objects.requireNonNull(containingChunks, "containingChunks");
		this.containingChunks = Collections.unmodifiableSet(new LinkedHashSet<>(containingChunks));
		validate();
	}

	public BoundingBox bounds() {
		return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
	}

	public List<ComputerStructureNode> nodes() { return nodes; }
	public Set<BlockPos> casingPositions() { return casingPositions; }
	public Set<ChunkPos> containingChunks() { return containingChunks; }

	public Optional<ComputerStructureNode> node(UUID computerId) {
		if (computerId == null) return Optional.empty();
		return nodes.stream().filter(node -> node.computerId().equals(computerId)).findFirst();
	}

	public Set<UUID> computerIds() {
		LinkedHashSet<UUID> result = new LinkedHashSet<>();
		nodes.forEach(node -> result.add(node.computerId()));
		return Collections.unmodifiableSet(result);
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", VERSION);
		tag.put("Bounds", saveBounds());
		ListTag nodeTags = new ListTag();
		for (ComputerStructureNode node : nodes) nodeTags.add(saveNode(node));
		tag.put("Nodes", nodeTags);
		tag.putLongArray("CasingPositions", casingPositions.stream().mapToLong(BlockPos::asLong).sorted().toArray());
		tag.putLongArray("ContainingChunks", containingChunks.stream().mapToLong(ChunkPos::toLong).sorted().toArray());
		return tag;
	}

	public static Optional<ComputerStructureSnapshot> load(CompoundTag tag) {
		if (tag == null || !tag.getAllKeys().equals(TOP_KEYS)
			|| !hasType(tag, "Version", Tag.TAG_INT) || tag.getInt("Version") != VERSION
			|| !hasType(tag, "Bounds", Tag.TAG_COMPOUND) || !hasType(tag, "Nodes", Tag.TAG_LIST)
			|| !hasType(tag, "CasingPositions", Tag.TAG_LONG_ARRAY)
			|| !hasType(tag, "ContainingChunks", Tag.TAG_LONG_ARRAY))
			return Optional.empty();
		try {
			CompoundTag boundsTag = tag.getCompound("Bounds");
			if (!hasExactIntKeys(boundsTag, BOUNDS_KEYS)) return Optional.empty();
			BoundingBox bounds = new BoundingBox(boundsTag.getInt("MinX"), boundsTag.getInt("MinY"),
				boundsTag.getInt("MinZ"), boundsTag.getInt("MaxX"), boundsTag.getInt("MaxY"),
				boundsTag.getInt("MaxZ"));
			ListTag nodeTags = tag.getList("Nodes", Tag.TAG_COMPOUND);
			if (nodeTags.size() < 1 || nodeTags.size() > MAX_NODES) return Optional.empty();
			List<ComputerStructureNode> nodes = new ArrayList<>(nodeTags.size());
			for (Tag value : nodeTags) {
				if (!(value instanceof CompoundTag child)) return Optional.empty();
				Optional<ComputerStructureNode> node = loadNode(child);
				if (node.isEmpty()) return Optional.empty();
				nodes.add(node.orElseThrow());
			}
			long[] casingLongs = tag.getLongArray("CasingPositions");
			long[] chunkLongs = tag.getLongArray("ContainingChunks");
			if (casingLongs.length > MAX_VOLUME || !strictlyIncreasing(casingLongs)
				|| !strictlyIncreasing(chunkLongs)) return Optional.empty();
			Set<BlockPos> casing = new LinkedHashSet<>();
			for (long value : casingLongs) casing.add(BlockPos.of(value).immutable());
			Set<ChunkPos> chunks = new LinkedHashSet<>();
			for (long value : chunkLongs) chunks.add(new ChunkPos(value));
			return Optional.of(new ComputerStructureSnapshot(bounds, nodes, casing, chunks));
		} catch (IllegalArgumentException | ArithmeticException exception) {
			return Optional.empty();
		}
	}

	private void validate() {
		long sizeX = (long) maxX - minX + 1;
		long sizeY = (long) maxY - minY + 1;
		long sizeZ = (long) maxZ - minZ + 1;
		long volume;
		try {
			volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException("bounds volume overflow", exception);
		}
		if (sizeX < 3 || sizeY < 3 || sizeZ < 3 || volume > MAX_VOLUME
			|| nodes.isEmpty() || nodes.size() > MAX_NODES || casingPositions.size() > volume)
			throw new IllegalArgumentException("invalid computer snapshot bounds or limits");

		UUID previous = null;
		Set<BlockPos> nodePositions = new HashSet<>();
		SpaceAddress firstAddress = null;
		for (ComputerStructureNode node : nodes) {
			Objects.requireNonNull(node, "node");
			if (previous != null && previous.compareTo(node.computerId()) >= 0)
				throw new IllegalArgumentException("nodes are not uniquely UUID-sorted");
			previous = node.computerId();
			BlockPos pos = node.address().localPos();
			if (!contains(pos) || !nodePositions.add(pos))
				throw new IllegalArgumentException("invalid or duplicate node position");
			if (firstAddress == null) firstAddress = node.address();
			else if (!firstAddress.dimension().equals(node.address().dimension())
				|| !Objects.equals(firstAddress.subLevelId(), node.address().subLevelId()))
				throw new IllegalArgumentException("nodes span multiple spaces");
		}

		for (BlockPos pos : casingPositions)
			if (!contains(pos)) throw new IllegalArgumentException("casing outside bounds");
		for (int x = minX; x <= maxX; x++)
			for (int y = minY; y <= maxY; y++)
				for (int z = minZ; z <= maxZ; z++)
					if (isShell(x, y, z) && !casingPositions.contains(new BlockPos(x, y, z)))
						throw new IllegalArgumentException("snapshot shell is incomplete");
		if (!chunksForBounds(minX, minZ, maxX, maxZ).equals(containingChunks))
			throw new IllegalArgumentException("containing chunks do not match bounds");
	}

	private CompoundTag saveBounds() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("MinX", minX);
		tag.putInt("MinY", minY);
		tag.putInt("MinZ", minZ);
		tag.putInt("MaxX", maxX);
		tag.putInt("MaxY", maxY);
		tag.putInt("MaxZ", maxZ);
		return tag;
	}

	private static CompoundTag saveNode(ComputerStructureNode node) {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("ComputerId", node.computerId());
		tag.put("Address", node.address().save());
		if (node.profile() != null) tag.put("Profile", node.profile().save());
		return tag;
	}

	private static Optional<ComputerStructureNode> loadNode(CompoundTag tag) {
		boolean hasProfile = tag.contains("Profile");
		if (!tag.getAllKeys().equals(hasProfile ? PROFILE_NODE_KEYS : NODE_KEYS)
			|| !tag.hasUUID("ComputerId") || !hasType(tag, "Address", Tag.TAG_COMPOUND)
			|| (hasProfile && !hasType(tag, "Profile", Tag.TAG_COMPOUND))) return Optional.empty();
		CompoundTag addressTag = tag.getCompound("Address");
		boolean hasSubLevel = addressTag.contains("SubLevel");
		if (!addressTag.getAllKeys().equals(hasSubLevel ? SUBLEVEL_ADDRESS_KEYS : ADDRESS_KEYS))
			return Optional.empty();
		Optional<SpaceAddress> address = SpaceAddress.tryLoad(addressTag);
		if (address.isEmpty()) return Optional.empty();
		ComputerProfile profile = null;
		if (hasProfile) {
			Optional<ComputerProfile> loaded = ComputerProfile.load(tag.getCompound("Profile"));
			if (loaded.isEmpty()) return Optional.empty();
			profile = loaded.orElseThrow();
		}
		return Optional.of(new ComputerStructureNode(tag.getUUID("ComputerId"), address.orElseThrow(), profile));
	}

	private boolean contains(BlockPos pos) {
		return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY
			&& pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}

	private boolean isShell(int x, int y, int z) {
		return x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
	}

	private static Set<ChunkPos> chunksForBounds(int minX, int minZ, int maxX, int maxZ) {
		Set<ChunkPos> chunks = new LinkedHashSet<>();
		for (int x = Math.floorDiv(minX, 16); x <= Math.floorDiv(maxX, 16); x++)
			for (int z = Math.floorDiv(minZ, 16); z <= Math.floorDiv(maxZ, 16); z++)
				chunks.add(new ChunkPos(x, z));
		return chunks;
	}

	private static boolean strictlyIncreasing(long[] values) {
		for (int i = 1; i < values.length; i++) if (values[i - 1] >= values[i]) return false;
		return true;
	}

	private static boolean hasExactIntKeys(CompoundTag tag, Set<String> keys) {
		if (!tag.getAllKeys().equals(keys)) return false;
		return keys.stream().allMatch(key -> hasType(tag, key, Tag.TAG_INT));
	}

	private static boolean hasType(CompoundTag tag, String key, int type) {
		Tag value = tag.get(key);
		return value != null && value.getId() == type;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof ComputerStructureSnapshot snapshot)) return false;
		return minX == snapshot.minX && minY == snapshot.minY && minZ == snapshot.minZ
			&& maxX == snapshot.maxX && maxY == snapshot.maxY && maxZ == snapshot.maxZ
			&& nodes.equals(snapshot.nodes) && casingPositions.equals(snapshot.casingPositions)
			&& containingChunks.equals(snapshot.containingChunks);
	}

	@Override
	public int hashCode() {
		return Objects.hash(minX, minY, minZ, maxX, maxY, maxZ, nodes, casingPositions, containingChunks);
	}
}
