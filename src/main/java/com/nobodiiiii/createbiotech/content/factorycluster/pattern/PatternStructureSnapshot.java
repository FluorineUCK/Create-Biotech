package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternLibraryScanner.StructureState;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

/** Immutable persisted description of the members found by a pattern-library scan. */
public final class PatternStructureSnapshot {
	private static final String STATE = "State";
	private static final String MEMBERS = "Members";
	private static final String ORDINARY = "Ordinary";
	private static final String CHISELED = "Chiseled";
	private static final String MIN = "Min";
	private static final String MAX = "Max";
	private static final String QUEUE_COUNT = "QueueCount";
	private static final String CHUNKS = "Chunks";

	private final StructureState state;
	private final List<BlockPos> members;
	private final List<BlockPos> ordinaryShelves;
	private final List<BlockPos> chiseledShelves;
	private final BlockPos min;
	private final BlockPos max;
	private final int queueCount;
	private final Set<ChunkPos> chunks;

	public PatternStructureSnapshot(StructureState state, List<BlockPos> members, List<BlockPos> ordinaryShelves,
		List<BlockPos> chiseledShelves, BlockPos min, BlockPos max, int queueCount) {
		this(state, members, ordinaryShelves, chiseledShelves, min, max, queueCount, chunksFor(members));
	}

	private PatternStructureSnapshot(StructureState state, List<BlockPos> members, List<BlockPos> ordinaryShelves,
		List<BlockPos> chiseledShelves, BlockPos min, BlockPos max, int queueCount, Set<ChunkPos> chunks) {
		this.state = Objects.requireNonNull(state, "state");
		this.members = immutablePositions(members, "members");
		this.ordinaryShelves = immutablePositions(ordinaryShelves, "ordinaryShelves");
		this.chiseledShelves = immutablePositions(chiseledShelves, "chiseledShelves");
		this.min = Objects.requireNonNull(min, "min").immutable();
		this.max = Objects.requireNonNull(max, "max").immutable();
		this.queueCount = queueCount;
		this.chunks = Set.copyOf(Objects.requireNonNull(chunks, "chunks"));
		validate();
	}

	public StructureState state() { return state; }
	public List<BlockPos> members() { return members; }
	public List<BlockPos> ordinaryShelves() { return ordinaryShelves; }
	public List<BlockPos> chiseledShelves() { return chiseledShelves; }
	public BlockPos min() { return min; }
	public BlockPos max() { return max; }
	public int queueCount() { return queueCount; }
	public Set<ChunkPos> chunks() { return chunks; }
	public int axisSpanX() { return max.getX() - min.getX() + 1; }
	public int axisSpanY() { return max.getY() - min.getY() + 1; }
	public int axisSpanZ() { return max.getZ() - min.getZ() + 1; }

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString(STATE, state.name());
		tag.put(MEMBERS, savePositions(members));
		tag.put(ORDINARY, savePositions(ordinaryShelves));
		tag.put(CHISELED, savePositions(chiseledShelves));
		tag.putLong(MIN, min.asLong());
		tag.putLong(MAX, max.asLong());
		tag.putInt(QUEUE_COUNT, queueCount);
		tag.put(CHUNKS, saveChunks(chunks));
		return tag;
	}

	public static Optional<PatternStructureSnapshot> load(CompoundTag tag) {
		if (!hasExactKeys(tag, STATE, MEMBERS, ORDINARY, CHISELED, MIN, MAX, QUEUE_COUNT, CHUNKS)
			|| !hasType(tag, STATE, Tag.TAG_STRING) || !hasType(tag, MEMBERS, Tag.TAG_LIST)
			|| !hasType(tag, ORDINARY, Tag.TAG_LIST) || !hasType(tag, CHISELED, Tag.TAG_LIST)
			|| !hasType(tag, MIN, Tag.TAG_LONG) || !hasType(tag, MAX, Tag.TAG_LONG)
			|| !hasType(tag, QUEUE_COUNT, Tag.TAG_INT) || !hasType(tag, CHUNKS, Tag.TAG_LIST))
			return Optional.empty();
		try {
			Optional<StructureState> state = enumValue(tag.getString(STATE));
			Optional<List<BlockPos>> members = loadPositions((ListTag) tag.get(MEMBERS));
			Optional<List<BlockPos>> ordinary = loadPositions((ListTag) tag.get(ORDINARY));
			Optional<List<BlockPos>> chiseled = loadPositions((ListTag) tag.get(CHISELED));
			Optional<Set<ChunkPos>> chunks = loadChunks((ListTag) tag.get(CHUNKS));
			if (state.isEmpty() || members.isEmpty() || ordinary.isEmpty() || chiseled.isEmpty() || chunks.isEmpty())
				return Optional.empty();
			return Optional.of(new PatternStructureSnapshot(state.orElseThrow(), members.orElseThrow(),
				ordinary.orElseThrow(), chiseled.orElseThrow(), BlockPos.of(tag.getLong(MIN)),
				BlockPos.of(tag.getLong(MAX)), tag.getInt(QUEUE_COUNT), chunks.orElseThrow()));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private void validate() {
		if (members.isEmpty() || queueCount < 0 || min.getX() > max.getX() || min.getY() > max.getY()
			|| min.getZ() > max.getZ() || new LinkedHashSet<>(members).size() != members.size()
			|| new LinkedHashSet<>(ordinaryShelves).size() != ordinaryShelves.size()
			|| new LinkedHashSet<>(chiseledShelves).size() != chiseledShelves.size())
			throw new IllegalArgumentException("invalid pattern structure snapshot");
		if (!java.util.Collections.disjoint(ordinaryShelves, chiseledShelves))
			throw new IllegalArgumentException("shelf classifications overlap");
		Set<BlockPos> shelves = new LinkedHashSet<>(ordinaryShelves);
		shelves.addAll(chiseledShelves);
		Set<BlockPos> expectedShelves = new LinkedHashSet<>(members);
		expectedShelves.remove(members.getFirst());
		if (!expectedShelves.equals(shelves) || !followsMemberOrder(ordinaryShelves)
			|| !followsMemberOrder(chiseledShelves) || !hasExactBounds() || !chunksFor(members).equals(chunks))
			throw new IllegalArgumentException("inconsistent pattern structure snapshot");
	}

	private boolean followsMemberOrder(List<BlockPos> classifiedMembers) {
		int next = 0;
		for (BlockPos member : classifiedMembers) {
			int offset = members.subList(next, members.size()).indexOf(member);
			if (offset < 0) return false;
			next += offset + 1;
		}
		return true;
	}

	private boolean hasExactBounds() {
		int minX = members.stream().mapToInt(BlockPos::getX).min().orElseThrow();
		int minY = members.stream().mapToInt(BlockPos::getY).min().orElseThrow();
		int minZ = members.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
		int maxX = members.stream().mapToInt(BlockPos::getX).max().orElseThrow();
		int maxY = members.stream().mapToInt(BlockPos::getY).max().orElseThrow();
		int maxZ = members.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
		return min.equals(new BlockPos(minX, minY, minZ)) && max.equals(new BlockPos(maxX, maxY, maxZ));
	}

	private static List<BlockPos> immutablePositions(List<BlockPos> positions, String name) {
		Objects.requireNonNull(positions, name);
		return positions.stream().map(pos -> Objects.requireNonNull(pos, name + " member").immutable()).toList();
	}

	private static Set<ChunkPos> chunksFor(List<BlockPos> positions) {
		Set<ChunkPos> chunks = new LinkedHashSet<>();
		positions.forEach(pos -> chunks.add(new ChunkPos(pos)));
		return chunks;
	}

	private static ListTag savePositions(List<BlockPos> positions) {
		ListTag tag = new ListTag();
		positions.forEach(pos -> tag.add(LongTag.valueOf(pos.asLong())));
		return tag;
	}

	private static ListTag saveChunks(Set<ChunkPos> chunks) {
		ListTag tag = new ListTag();
		chunks.stream().sorted(java.util.Comparator.comparingLong(ChunkPos::toLong)).forEach(chunk -> {
			CompoundTag child = new CompoundTag();
			child.putInt("X", chunk.x);
			child.putInt("Z", chunk.z);
			tag.add(child);
		});
		return tag;
	}

	private static Optional<List<BlockPos>> loadPositions(ListTag tag) {
		List<BlockPos> positions = new ArrayList<>();
		for (Tag value : tag) {
			if (!(value instanceof LongTag position)) return Optional.empty();
			positions.add(BlockPos.of(position.getAsLong()).immutable());
		}
		return Optional.of(List.copyOf(positions));
	}

	private static Optional<Set<ChunkPos>> loadChunks(ListTag tag) {
		Set<ChunkPos> chunks = new LinkedHashSet<>();
		for (Tag value : tag) {
			if (!(value instanceof CompoundTag child) || !hasExactKeys(child, "X", "Z")
				|| !hasType(child, "X", Tag.TAG_INT) || !hasType(child, "Z", Tag.TAG_INT))
				return Optional.empty();
			ChunkPos chunk = new ChunkPos(child.getInt("X"), child.getInt("Z"));
			if (!chunks.add(chunk)) return Optional.empty();
		}
		return Optional.of(Set.copyOf(chunks));
	}

	private static Optional<StructureState> enumValue(String value) {
		try { return Optional.of(StructureState.valueOf(value)); }
		catch (IllegalArgumentException exception) { return Optional.empty(); }
	}

	private static boolean hasType(CompoundTag tag, String key, int type) {
		Tag value = tag.get(key);
		return value != null && value.getId() == type;
	}

	private static boolean hasExactKeys(CompoundTag tag, String... keys) {
		return tag.getAllKeys().size() == keys.length && Set.of(keys).equals(tag.getAllKeys());
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof PatternStructureSnapshot snapshot)) return false;
		return state == snapshot.state && members.equals(snapshot.members)
			&& ordinaryShelves.equals(snapshot.ordinaryShelves) && chiseledShelves.equals(snapshot.chiseledShelves)
			&& min.equals(snapshot.min) && max.equals(snapshot.max) && queueCount == snapshot.queueCount
			&& chunks.equals(snapshot.chunks);
	}

	@Override
	public int hashCode() {
		return Objects.hash(state, members, ordinaryShelves, chiseledShelves, min, max, queueCount, chunks);
	}
}
