package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

class ClusterEpochTest {
	private static final UUID CLUSTER = new UUID(40, 1);
	private static final UUID STRUCTURE_MEMBER = new UUID(40, 2);
	private static final UUID SPACE = new UUID(40, 3);
	private static final UUID OTHER_SPACE = new UUID(40, 4);
	private static final UUID LOW = new UUID(0, 1);
	private static final UUID HIGH = new UUID(0, 2);
	private static final UUID THIRD = new UUID(0, 3);
	private static final UUID FOURTH = new UUID(0, 4);
	private static final UUID EXTRA = new UUID(0, 99);
	private static final BoundingBox ORIGINAL_BOUNDS = box(0, 0, 0, 3, 4, 5);
	private static final List<ComputerStructureNode> ORIGINAL_NODES = List.of(
		node(LOW, new BlockPos(1, 1, 1), librarian()),
		node(HIGH, new BlockPos(2, 1, 1), villager()),
		node(THIRD, new BlockPos(1, 3, 1), trader(70)),
		node(FOURTH, new BlockPos(1, 1, 4), nitwit()));

	@Test
	void freezeUsesUuidOrderAndRequiresEveryPhysicalNodeProfile() {
		ClusterEpoch epoch = epoch();

		assertEquals(LOW, epoch.coordinatorId());
		assertEquals(List.of(LOW, HIGH, THIRD, FOURTH),
			epoch.nodes().stream().map(EpochNode::computerId).toList());
		assertEquals(STRUCTURE_MEMBER, epoch.computerStructureMemberId());
		assertEquals(address(ORIGINAL_NODES.getFirst().address().localPos()), epoch.coordinatorAddress());
		assertThrows(IllegalArgumentException.class, () -> ClusterEpoch.freeze(CLUSTER, STRUCTURE_MEMBER,
			snapshot(ORIGINAL_BOUNDS, List.of(new ComputerStructureNode(LOW,
				address(new BlockPos(1, 1, 1)), null)))));
	}

	@Test
	void epochValuesDefensivelyCopyBoundsAndNodeLists() {
		ClusterEpoch epoch = epoch();
		BoundingBox first = epoch.bounds();
		BoundingBox second = epoch.bounds();

		assertNotSame(first, second);
		assertBounds(ORIGINAL_BOUNDS, first);
		assertThrows(UnsupportedOperationException.class,
			() -> epoch.nodes().add(epoch.nodes().getFirst()));
		assertEquals(Set.of(EpochFault.WIDTH, EpochFault.DEPTH), Set.of(EpochFault.values()));
	}

	@Test
	void rigidTranslationUpdatesAddressesWithoutChangingFrozenValues() {
		ClusterEpoch epoch = epoch();
		ComputerStructureSnapshot translated = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			pos -> pos.offset(17, -9, 31), Level.OVERWORLD, SPACE, List.of());

		ClusterEpoch moved = epoch.relocate(translated);

		assertFrozenIdentity(epoch, moved);
		assertBounds(translated.bounds(), moved.bounds());
		assertEquals(new BlockPos(18, -8, 32), moved.nodes().getFirst().address().localPos());
	}

	@Test
	void rigidRotationUpdatesBoundsCoordinatorAndAddressesWithoutChangingEpoch() {
		ClusterEpoch epoch = epoch();
		UnaryOperator<BlockPos> rotation = pos -> new BlockPos(-pos.getY() + 20, pos.getX() + 30,
			pos.getZ() + 40);
		ComputerStructureSnapshot rotated = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			rotation, Level.OVERWORLD, SPACE, List.of());

		ClusterEpoch moved = epoch.relocate(rotated);

		assertFrozenIdentity(epoch, moved);
		assertNotEquals(boundsTuple(epoch.bounds()), boundsTuple(moved.bounds()));
		assertEquals(moved.nodes().stream().filter(node -> node.computerId().equals(LOW))
			.findFirst().orElseThrow().address(), moved.coordinatorAddress());
		assertEquals(epoch.nodes().stream().map(EpochNode::profile).toList(),
			moved.nodes().stream().map(EpochNode::profile).toList());
	}

	@Test
	void relocationIgnoresPendingExtraIds() {
		ClusterEpoch epoch = epoch();
		ComputerStructureNode extra = node(EXTRA, new BlockPos(2, 3, 4), trader(4096));
		ComputerStructureSnapshot current = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			UnaryOperator.identity(), Level.OVERWORLD, SPACE, List.of(extra));

		ClusterEpoch moved = epoch.relocate(current);

		assertEquals(epoch.nodes(), moved.nodes());
		assertFalse(moved.nodes().stream().anyMatch(node -> node.computerId().equals(EXTRA)));
		assertEquals(70, moved.totalPatternRangeBonus());
	}

	@Test
	void relocationRejectsMissingOrChangedFrozenNodes() {
		ClusterEpoch epoch = epoch();
		List<ComputerStructureNode> missing = ORIGINAL_NODES.stream()
			.filter(node -> !node.computerId().equals(FOURTH)).toList();
		List<ComputerStructureNode> changedProfile = replace(ORIGINAL_NODES, HIGH,
			node(HIGH, ORIGINAL_NODES.get(1).address().localPos(), librarian()));

		assertThrows(IllegalArgumentException.class, () -> epoch.relocate(snapshot(ORIGINAL_BOUNDS, missing)));
		assertThrows(IllegalArgumentException.class,
			() -> epoch.relocate(snapshot(ORIGINAL_BOUNDS, changedProfile)));
	}

	@Test
	void relocationRejectsReflectionAndNonRigidDisplacement() {
		ClusterEpoch epoch = epoch();
		ComputerStructureSnapshot reflection = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			pos -> new BlockPos(-pos.getX() + 30, pos.getY() + 40, pos.getZ() + 50),
			Level.OVERWORLD, SPACE, List.of());
		List<ComputerStructureNode> displaced = replace(ORIGINAL_NODES, FOURTH,
			node(FOURTH, new BlockPos(2, 2, 4), nitwit()));

		assertThrows(IllegalArgumentException.class, () -> epoch.relocate(reflection));
		assertThrows(IllegalArgumentException.class,
			() -> epoch.relocate(snapshot(ORIGINAL_BOUNDS, displaced)));
	}

	@Test
	void relocationRequiresExactDimensionAndNullableSableSpaceIdentity() {
		ClusterEpoch epoch = epoch();
		ComputerStructureSnapshot otherDimension = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			UnaryOperator.identity(), Level.NETHER, SPACE, List.of());
		ComputerStructureSnapshot otherSublevel = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			UnaryOperator.identity(), Level.OVERWORLD, OTHER_SPACE, List.of());
		ComputerStructureSnapshot outerSpace = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			UnaryOperator.identity(), Level.OVERWORLD, null, List.of());

		assertThrows(IllegalArgumentException.class, () -> epoch.relocate(otherDimension));
		assertThrows(IllegalArgumentException.class, () -> epoch.relocate(otherSublevel));
		assertThrows(IllegalArgumentException.class, () -> epoch.relocate(outerSpace));
	}

	@Test
	void codecRoundTripsExactImmutableEpochValues() {
		ClusterEpoch epoch = epoch();
		CompoundTag saved = epoch.save();
		ClusterEpoch decoded = ClusterEpoch.load(saved).orElseThrow();

		assertEquals(Set.of("Version", "EpochId", "ClusterId", "StructureMemberId", "CoordinatorId",
			"CoordinatorAddress", "Bounds", "Nodes"), saved.getAllKeys());
		assertEquals(epoch, decoded);
		assertEquals(epoch.hashCode(), decoded.hashCode());
		assertNotSame(epoch.bounds(), decoded.bounds());
	}

	@Test
	void codecRejectsDuplicateMissingAndMismatchedCoordinatorIdentity() {
		CompoundTag saved = epoch().save();
		CompoundTag duplicate = saved.copy();
		ListTag duplicateNodes = duplicate.getList("Nodes", Tag.TAG_COMPOUND);
		duplicateNodes.getCompound(1).putUUID("ComputerId", LOW);
		CompoundTag missing = saved.copy();
		missing.putUUID("CoordinatorId", EXTRA);
		CompoundTag mismatchedAddress = saved.copy();
		mismatchedAddress.getCompound("CoordinatorAddress").putLong("Pos", new BlockPos(2, 2, 2).asLong());

		assertTrue(ClusterEpoch.load(duplicate).isEmpty());
		assertTrue(ClusterEpoch.load(missing).isEmpty());
		assertTrue(ClusterEpoch.load(mismatchedAddress).isEmpty());
	}

	@Test
	void codecRejectsBadBoundsAndBadProfiles() {
		CompoundTag badBounds = epoch().save();
		badBounds.getCompound("Bounds").putInt("MinX", 100);
		CompoundTag outsideBounds = epoch().save();
		outsideBounds.getCompound("Bounds").putInt("MaxX", 1);
		CompoundTag badProfile = epoch().save();
		badProfile.getList("Nodes", Tag.TAG_COMPOUND).getCompound(0)
			.getCompound("Profile").putInt("Slots", 999);

		assertTrue(ClusterEpoch.load(badBounds).isEmpty());
		assertTrue(ClusterEpoch.load(outsideBounds).isEmpty());
		assertTrue(ClusterEpoch.load(badProfile).isEmpty());
	}

	@Test
	void codecRejectsWrongKeysTypesVersionsAndNonCompoundNodeLists() {
		List<CompoundTag> invalid = new ArrayList<>();
		CompoundTag wrongVersion = epoch().save();
		wrongVersion.putInt("Version", 2);
		invalid.add(wrongVersion);
		CompoundTag topExtra = epoch().save();
		topExtra.putInt("Extra", 1);
		invalid.add(topExtra);
		CompoundTag wrongTopType = epoch().save();
		wrongTopType.putString("EpochId", "not-a-uuid");
		invalid.add(wrongTopType);
		CompoundTag boundsExtra = epoch().save();
		boundsExtra.getCompound("Bounds").putInt("Extra", 1);
		invalid.add(boundsExtra);
		CompoundTag boundsWrongType = epoch().save();
		boundsWrongType.getCompound("Bounds").putString("MinX", "0");
		invalid.add(boundsWrongType);
		CompoundTag nodeExtra = epoch().save();
		nodeExtra.getList("Nodes", Tag.TAG_COMPOUND).getCompound(0).putInt("Extra", 1);
		invalid.add(nodeExtra);
		CompoundTag addressExtra = epoch().save();
		addressExtra.getCompound("CoordinatorAddress").putInt("Extra", 1);
		invalid.add(addressExtra);
		CompoundTag profileExtra = epoch().save();
		profileExtra.getList("Nodes", Tag.TAG_COMPOUND).getCompound(0)
			.getCompound("Profile").putInt("Extra", 1);
		invalid.add(profileExtra);
		CompoundTag stringNodes = epoch().save();
		ListTag strings = new ListTag();
		strings.add(StringTag.valueOf("node"));
		stringNodes.put("Nodes", strings);
		invalid.add(stringNodes);

		invalid.forEach(tag -> assertTrue(ClusterEpoch.load(tag).isEmpty(), tag::toString));
	}

	@Test
	void codecRequiresOneToTwoHundredFiftySixAlreadySortedNodes() {
		CompoundTag empty = epoch().save();
		empty.put("Nodes", new ListTag());
		CompoundTag unsorted = epoch().save();
		ListTag original = unsorted.getList("Nodes", Tag.TAG_COMPOUND);
		ListTag reversed = new ListTag();
		for (int i = original.size() - 1; i >= 0; i--) reversed.add(original.getCompound(i).copy());
		unsorted.put("Nodes", reversed);
		CompoundTag oversized = epoch().save();
		ListTag tooMany = new ListTag();
		CompoundTag template = oversized.getList("Nodes", Tag.TAG_COMPOUND).getCompound(0);
		for (int i = 0; i < 257; i++) {
			CompoundTag child = template.copy();
			child.putUUID("ComputerId", new UUID(1, i));
			child.getCompound("Address").putLong("Pos", new BlockPos(1, 1, 1).offset(0, 0, i).asLong());
			tooMany.add(child);
		}
		oversized.put("Nodes", tooMany);

		assertTrue(ClusterEpoch.load(empty).isEmpty());
		assertTrue(ClusterEpoch.load(unsorted).isEmpty());
		assertTrue(ClusterEpoch.load(oversized).isEmpty());
	}

	@Test
	void totalPatternRangeBonusUsesCheckedAdditionAndOnlyFrozenTraderProfiles() {
		List<ComputerStructureNode> nodes = List.of(
			node(LOW, new BlockPos(1, 1, 1), trader(300)),
			node(HIGH, new BlockPos(2, 1, 1), new ComputerProfile(NodeKind.VILLAGER, 1, 1, false, 900)),
			node(THIRD, new BlockPos(1, 3, 1), trader(400)));
		ClusterEpoch epoch = ClusterEpoch.freeze(CLUSTER, STRUCTURE_MEMBER, snapshot(ORIGINAL_BOUNDS, nodes));
		ComputerStructureSnapshot withPendingExtra = transformedSnapshot(ORIGINAL_BOUNDS, nodes,
			UnaryOperator.identity(), Level.OVERWORLD, SPACE,
			List.of(node(EXTRA, new BlockPos(2, 3, 4), trader(4096))));

		ClusterEpoch moved = epoch.relocate(withPendingExtra);
		int approvedDefaultRange = Math.min(512, Math.addExact(64, moved.totalPatternRangeBonus()));

		assertEquals(700, moved.totalPatternRangeBonus());
		assertEquals(512, approvedDefaultRange);

		ClusterEpoch overflowing = ClusterEpoch.freeze(CLUSTER, STRUCTURE_MEMBER,
			snapshot(ORIGINAL_BOUNDS, List.of(
				node(LOW, new BlockPos(1, 1, 1), trader(Integer.MAX_VALUE)),
				node(HIGH, new BlockPos(2, 1, 1), trader(1)))));
		assertThrows(ArithmeticException.class, overflowing::totalPatternRangeBonus);
	}

	@Test
	void quiescenceAndReformSeamsExposeOnlyTheLockedRuntimeIndependentMethods() {
		assertEquals(Set.of("allRootsStopped", "nodeIdle", "nodeRootFree", "nodeMailboxEmpty"),
			declaredMethodNames(EpochQuiescence.class));
		assertEquals(Set.of("stopAllAndClear"), declaredMethodNames(EpochReformControl.class));
		assertEquals(List.of(EpochQuiescence.class), List.of(EpochReformControl.class.getInterfaces()));
		for (Method method : EpochQuiescence.class.getDeclaredMethods())
			assertTrue(method.getReturnType().equals(boolean.class));
	}

	private static ClusterEpoch epoch() {
		return ClusterEpoch.freeze(CLUSTER, STRUCTURE_MEMBER, snapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES));
	}

	private static Set<String> declaredMethodNames(Class<?> type) {
		Set<String> names = new LinkedHashSet<>();
		for (Method method : type.getDeclaredMethods()) names.add(method.getName());
		return names;
	}

	private static void assertFrozenIdentity(ClusterEpoch before, ClusterEpoch after) {
		assertEquals(before.epochId(), after.epochId());
		assertEquals(before.clusterId(), after.clusterId());
		assertEquals(before.computerStructureMemberId(), after.computerStructureMemberId());
		assertEquals(before.coordinatorId(), after.coordinatorId());
		assertEquals(before.nodes().stream().map(EpochNode::computerId).toList(),
			after.nodes().stream().map(EpochNode::computerId).toList());
		assertEquals(before.nodes().stream().map(EpochNode::profile).toList(),
			after.nodes().stream().map(EpochNode::profile).toList());
	}

	private static List<ComputerStructureNode> replace(List<ComputerStructureNode> nodes, UUID id,
		ComputerStructureNode replacement) {
		return nodes.stream().map(node -> node.computerId().equals(id) ? replacement : node).toList();
	}

	private static ComputerStructureSnapshot transformedSnapshot(BoundingBox bounds,
		List<ComputerStructureNode> nodes, UnaryOperator<BlockPos> transform, ResourceKey<Level> dimension,
		UUID subLevelId, List<ComputerStructureNode> extras) {
		BoundingBox transformedBounds = transformedBounds(bounds, transform);
		List<ComputerStructureNode> transformed = new ArrayList<>();
		for (ComputerStructureNode node : nodes)
			transformed.add(new ComputerStructureNode(node.computerId(),
				new SpaceAddress(dimension, subLevelId, transform.apply(node.address().localPos())), node.profile()));
		transformed.addAll(extras);
		transformed.sort(Comparator.comparing(ComputerStructureNode::computerId));
		return snapshot(transformedBounds, transformed);
	}

	private static BoundingBox transformedBounds(BoundingBox bounds, UnaryOperator<BlockPos> transform) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (int x : new int[] {bounds.minX(), bounds.maxX()})
			for (int y : new int[] {bounds.minY(), bounds.maxY()})
				for (int z : new int[] {bounds.minZ(), bounds.maxZ()}) {
					BlockPos result = transform.apply(new BlockPos(x, y, z));
					minX = Math.min(minX, result.getX());
					minY = Math.min(minY, result.getY());
					minZ = Math.min(minZ, result.getZ());
					maxX = Math.max(maxX, result.getX());
					maxY = Math.max(maxY, result.getY());
					maxZ = Math.max(maxZ, result.getZ());
				}
		return box(minX, minY, minZ, maxX, maxY, maxZ);
	}

	private static ComputerStructureSnapshot snapshot(BoundingBox bounds, List<ComputerStructureNode> nodes) {
		return new ComputerStructureSnapshot(bounds, nodes, shell(bounds), chunks(bounds));
	}

	private static Set<BlockPos> shell(BoundingBox bounds) {
		Set<BlockPos> result = new LinkedHashSet<>();
		for (int x = bounds.minX(); x <= bounds.maxX(); x++)
			for (int y = bounds.minY(); y <= bounds.maxY(); y++)
				for (int z = bounds.minZ(); z <= bounds.maxZ(); z++)
					if (x == bounds.minX() || x == bounds.maxX() || y == bounds.minY()
						|| y == bounds.maxY() || z == bounds.minZ() || z == bounds.maxZ())
						result.add(new BlockPos(x, y, z));
		return result;
	}

	private static Set<ChunkPos> chunks(BoundingBox bounds) {
		Set<ChunkPos> result = new LinkedHashSet<>();
		for (int x = Math.floorDiv(bounds.minX(), 16); x <= Math.floorDiv(bounds.maxX(), 16); x++)
			for (int z = Math.floorDiv(bounds.minZ(), 16); z <= Math.floorDiv(bounds.maxZ(), 16); z++)
				result.add(new ChunkPos(x, z));
		return result;
	}

	private static ComputerStructureNode node(UUID id, BlockPos pos, ComputerProfile profile) {
		return new ComputerStructureNode(id, address(pos), profile);
	}

	private static SpaceAddress address(BlockPos pos) {
		return new SpaceAddress(Level.OVERWORLD, SPACE, pos);
	}

	private static ComputerProfile villager() {
		return ComputerProfile.forKind(NodeKind.VILLAGER, 1, 0).orElseThrow();
	}

	private static ComputerProfile librarian() {
		return ComputerProfile.forKind(NodeKind.LIBRARIAN, 2, 0).orElseThrow();
	}

	private static ComputerProfile nitwit() {
		return ComputerProfile.forKind(NodeKind.NITWIT, 1, 0).orElseThrow();
	}

	private static ComputerProfile trader(int bonus) {
		return new ComputerProfile(NodeKind.WANDERING_TRADER, 2, 1, false, bonus);
	}

	private static BoundingBox box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
	}

	private static List<Integer> boundsTuple(BoundingBox bounds) {
		return List.of(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}

	private static void assertBounds(BoundingBox expected, BoundingBox actual) {
		assertEquals(boundsTuple(expected), boundsTuple(actual));
	}
}
