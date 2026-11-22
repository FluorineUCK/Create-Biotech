package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionQuiescence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionReformControl;

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


import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
	private static final List<ProductionLayout.Member> ORIGINAL_NODES = List.of(
		node(LOW, new BlockPos(1, 1, 1), librarian()),
		node(HIGH, new BlockPos(2, 1, 1), villager()),
		node(THIRD, new BlockPos(1, 3, 1), trader(70)),
		node(FOURTH, new BlockPos(1, 1, 4), nitwit()));

	@Test
	void freezeUsesUuidOrderAndRequiresEveryPhysicalNodeProfile() {
		ProductionEpoch epoch = epoch();

		assertEquals(LOW, epoch.coordinatorId());
		assertEquals(List.of(LOW, HIGH, THIRD, FOURTH),
			epoch.layout().members().stream().map(ProductionLayout.Member::computerId).toList());
		assertEquals(STRUCTURE_MEMBER, epoch.structureId());
		assertEquals(address(ORIGINAL_NODES.getFirst().address().localPos()), epoch.coordinatorAddress());
		assertThrows(IllegalArgumentException.class, () -> ProductionEpoch.freeze(CLUSTER, STRUCTURE_MEMBER, snapshot(ORIGINAL_BOUNDS, List.of(new ProductionLayout.Member(LOW,
				address(new BlockPos(1, 1, 1)), null))).layout()));
	}

	@Test
	void epochValuesIsolateMutableInputBoundsAndCopyNodeLists() {
		BoundingBox input = box(0, 0, 0, 3, 4, 5);
		ProductionEpoch epoch = ProductionEpoch.freeze(CLUSTER, STRUCTURE_MEMBER, snapshot(input, ORIGINAL_NODES).layout());
		ProductionLayout.Bounds first = epoch.layout().bounds();
		input.move(100, 100, 100);
		ProductionLayout.Bounds second = epoch.layout().bounds();

		assertEquals(first, second);
		assertNotEquals(boundsTuple(input), boundsTuple(second), "Mutating the world fixture bounds cannot change a frozen value");
		assertEquals(boundsTuple(ORIGINAL_BOUNDS), boundsTuple(first));
		assertThrows(UnsupportedOperationException.class,
			() -> epoch.layout().members().add(epoch.layout().members().getFirst()));
		assertEquals(Set.of(EpochFault.WIDTH, EpochFault.DEPTH), Set.of(EpochFault.values()));
	}

	@Test
	void rigidTranslationUpdatesAddressesWithoutChangingFrozenValues() {
		ProductionEpoch epoch = epoch();
		ProductionStructure translated = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			pos -> pos.offset(17, -9, 31), Level.OVERWORLD, SPACE, List.of());

		ProductionEpoch moved = epoch.relocate(translated.layout());

		assertFrozenIdentity(epoch, moved);
		assertEquals(translated.layout().bounds(), moved.layout().bounds());
		assertEquals(new BlockPos(18, -8, 32), moved.layout().members().getFirst().address().localPos());
	}

	@Test
	void rigidRelocationMovesFrozenPanelAddressAndRotatesItsFacing() {
		ProductionLayout.Panel panel = new ProductionLayout.Panel(EXTRA,
			address(new BlockPos(0, 1, 1)), Direction.WEST, ComputerShellMaterial.TRANSPARENT);
		ProductionStructure original = new ProductionStructure(new ProductionLayout(productionBounds(ORIGINAL_BOUNDS), ORIGINAL_NODES, List.of(panel)), shell(ORIGINAL_BOUNDS), chunks(ORIGINAL_BOUNDS));
		ProductionEpoch epoch = ProductionEpoch.freeze(CLUSTER, STRUCTURE_MEMBER, original.layout());
		UnaryOperator<BlockPos> rotate = pos -> new BlockPos(pos.getZ() + 20, pos.getY() + 30,
			-pos.getX() + 40);
		BoundingBox movedBounds = transformedBounds(ORIGINAL_BOUNDS, rotate);
		List<ProductionLayout.Member> movedNodes = ORIGINAL_NODES.stream().map(node -> new ProductionLayout.Member(
			node.computerId(), address(rotate.apply(node.address().localPos())), node.profile())).toList();
		ProductionLayout.Panel movedPanel = new ProductionLayout.Panel(EXTRA,
			address(rotate.apply(panel.address().localPos())), Direction.SOUTH,
			ComputerShellMaterial.TRANSPARENT);
		ProductionStructure moved = new ProductionStructure(new ProductionLayout(productionBounds(movedBounds), movedNodes, List.of(movedPanel)), shell(movedBounds), chunks(movedBounds));

		ProductionEpoch relocated = epoch.relocate(moved.layout());
		assertEquals(epoch.epochId(), relocated.epochId());
		assertEquals(List.of(movedPanel), relocated.layout().panels());
		ProductionStructure wrongFace = new ProductionStructure(new ProductionLayout(productionBounds(movedBounds), movedNodes, List.of(new ProductionLayout.Panel(EXTRA, movedPanel.address(), Direction.WEST,
				ComputerShellMaterial.TRANSPARENT))), shell(movedBounds), chunks(movedBounds));
		assertThrows(IllegalArgumentException.class, () -> epoch.relocate(wrongFace.layout()));
	}

	@Test
	void relocationSkipsSymmetricRotationWhosePanelDoesNotMatchBeforeIdentity() {
		BoundingBox symmetricBounds = box(0, 0, 0, 2, 2, 2);
		ProductionLayout.Member centered = node(LOW, new BlockPos(1, 1, 1), librarian());
		ProductionLayout.Panel panel = new ProductionLayout.Panel(EXTRA,
			address(new BlockPos(0, 1, 1)), Direction.WEST, ComputerShellMaterial.OPAQUE);
		ProductionStructure snapshot = new ProductionStructure(new ProductionLayout(productionBounds(symmetricBounds), List.of(centered), List.of(panel)), shell(symmetricBounds), chunks(symmetricBounds));

		ProductionEpoch relocated = ProductionEpoch.freeze(CLUSTER, STRUCTURE_MEMBER, snapshot.layout()).relocate(snapshot.layout());

		assertEquals(List.of(panel), relocated.layout().panels());
		assertEquals(address(new BlockPos(1, 1, 1)), relocated.coordinatorAddress());
	}

	@Test
	void relocationRejectsChangedFrozenPanelIdentity() {
		ProductionLayout.Panel frozen = frozenPanel();
		ProductionEpoch epoch = ProductionEpoch.freeze(CLUSTER, STRUCTURE_MEMBER, snapshotWithPanel(frozen).layout());
		ProductionLayout.Panel replacement = new ProductionLayout.Panel(new UUID(0, 100),
			frozen.address(), frozen.facing(), frozen.baseMaterial());

		assertThrows(IllegalArgumentException.class,
			() -> epoch.relocate(snapshotWithPanel(replacement).layout()));
	}

	@Test
	void relocationRejectsChangedFrozenPanelBaseMaterial() {
		ProductionLayout.Panel frozen = frozenPanel();
		ProductionEpoch epoch = ProductionEpoch.freeze(CLUSTER, STRUCTURE_MEMBER, snapshotWithPanel(frozen).layout());
		ProductionLayout.Panel replacement = new ProductionLayout.Panel(frozen.panelId(),
			frozen.address(), frozen.facing(), ComputerShellMaterial.OPAQUE);

		assertThrows(IllegalArgumentException.class,
			() -> epoch.relocate(snapshotWithPanel(replacement).layout()));
	}

	@Test
	void relocationRejectsChangedFrozenPanelPosition() {
		ProductionLayout.Panel frozen = frozenPanel();
		ProductionEpoch epoch = ProductionEpoch.freeze(CLUSTER, STRUCTURE_MEMBER, snapshotWithPanel(frozen).layout());
		ProductionLayout.Panel replacement = new ProductionLayout.Panel(frozen.panelId(),
			address(new BlockPos(0, 1, 2)), frozen.facing(), frozen.baseMaterial());

		assertThrows(IllegalArgumentException.class,
			() -> epoch.relocate(snapshotWithPanel(replacement).layout()));
	}

	@Test
	void relocationRejectsAnAddedPanelOutsideTheFrozenIdentitySet() {
		ProductionLayout.Panel frozen = frozenPanel();
		ProductionEpoch epoch = ProductionEpoch.freeze(CLUSTER, STRUCTURE_MEMBER, snapshotWithPanel(frozen).layout());
		ProductionLayout.Panel added = new ProductionLayout.Panel(new UUID(0, 100),
			address(new BlockPos(0, 1, 2)), Direction.WEST, ComputerShellMaterial.OPAQUE);
		ProductionStructure withExtra = new ProductionStructure(new ProductionLayout(productionBounds(ORIGINAL_BOUNDS), ORIGINAL_NODES, List.of(frozen, added)), shell(ORIGINAL_BOUNDS), chunks(ORIGINAL_BOUNDS));

		assertThrows(IllegalArgumentException.class, () -> epoch.relocate(withExtra.layout()));
	}

	@Test
	void rigidRotationUpdatesBoundsCoordinatorAndAddressesWithoutChangingEpoch() {
		ProductionEpoch epoch = epoch();
		UnaryOperator<BlockPos> rotation = pos -> new BlockPos(-pos.getY() + 20, pos.getX() + 30,
			pos.getZ() + 40);
		ProductionStructure rotated = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			rotation, Level.OVERWORLD, SPACE, List.of());

		ProductionEpoch moved = epoch.relocate(rotated.layout());

		assertFrozenIdentity(epoch, moved);
		assertNotEquals(boundsTuple(epoch.layout().bounds()), boundsTuple(moved.layout().bounds()));
		assertEquals(moved.layout().members().stream().filter(node -> node.computerId().equals(LOW))
			.findFirst().orElseThrow().address(), moved.coordinatorAddress());
		assertEquals(epoch.layout().members().stream().map(ProductionLayout.Member::profile).toList(),
			moved.layout().members().stream().map(ProductionLayout.Member::profile).toList());
	}

	@Test
	void relocationIgnoresPendingExtraIds() {
		ProductionEpoch epoch = epoch();
		ProductionLayout.Member extra = node(EXTRA, new BlockPos(2, 3, 4), trader(4096));
		ProductionStructure current = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			UnaryOperator.identity(), Level.OVERWORLD, SPACE, List.of(extra));

		ProductionEpoch moved = epoch.relocate(current.layout());

		assertEquals(epoch.layout().members(), moved.layout().members());
		assertFalse(moved.layout().members().stream().anyMatch(node -> node.computerId().equals(EXTRA)));
	}

	@Test
	void relocationRejectsMissingOrChangedFrozenNodes() {
		ProductionEpoch epoch = epoch();
		List<ProductionLayout.Member> missing = ORIGINAL_NODES.stream()
			.filter(node -> !node.computerId().equals(FOURTH)).toList();
		List<ProductionLayout.Member> changedProfile = replace(ORIGINAL_NODES, HIGH,
			node(HIGH, ORIGINAL_NODES.get(1).address().localPos(), librarian()));

		assertThrows(IllegalArgumentException.class, () -> epoch.relocate(snapshot(ORIGINAL_BOUNDS, missing).layout()));
		assertThrows(IllegalArgumentException.class,
			() -> epoch.relocate(snapshot(ORIGINAL_BOUNDS, changedProfile).layout()));
	}

	@Test
	void relocationRejectsReflectionAndNonRigidDisplacement() {
		ProductionEpoch epoch = epoch();
		ProductionStructure reflection = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			pos -> new BlockPos(-pos.getX() + 30, pos.getY() + 40, pos.getZ() + 50),
			Level.OVERWORLD, SPACE, List.of());
		List<ProductionLayout.Member> displaced = replace(ORIGINAL_NODES, FOURTH,
			node(FOURTH, new BlockPos(2, 2, 4), nitwit()));

		assertThrows(IllegalArgumentException.class, () -> epoch.relocate(reflection.layout()));
		assertThrows(IllegalArgumentException.class,
			() -> epoch.relocate(snapshot(ORIGINAL_BOUNDS, displaced).layout()));
	}

	@Test
	void relocationRequiresExactDimensionAndNullableSableSpaceIdentity() {
		ProductionEpoch epoch = epoch();
		ProductionStructure otherDimension = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			UnaryOperator.identity(), Level.NETHER, SPACE, List.of());
		ProductionStructure otherSublevel = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			UnaryOperator.identity(), Level.OVERWORLD, OTHER_SPACE, List.of());
		ProductionStructure outerSpace = transformedSnapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES,
			UnaryOperator.identity(), Level.OVERWORLD, null, List.of());

		assertThrows(IllegalArgumentException.class, () -> epoch.relocate(otherDimension.layout()));
		assertThrows(IllegalArgumentException.class, () -> epoch.relocate(otherSublevel.layout()));
		assertThrows(IllegalArgumentException.class, () -> epoch.relocate(outerSpace.layout()));
	}

	@Test
	void codecRoundTripsExactImmutableEpochValues() {
		ProductionEpoch epoch = epoch();
		CompoundTag saved = ProductionEpochCodec.save(epoch);
		ProductionEpoch decoded = ProductionEpochCodec.load(saved).orElseThrow();

		assertEquals(Set.of("Version", "EpochId", "ClusterId", "StructureMemberId", "CoordinatorId",
			"CoordinatorAddress", "Bounds", "Nodes", "Panels"), saved.getAllKeys());
		assertEquals(epoch, decoded);
		assertEquals(epoch.hashCode(), decoded.hashCode());
		assertNotSame(epoch.layout().bounds(), decoded.layout().bounds());
	}

	@Test
	void codecRejectsTwoPanelsAtTheSamePhysicalPosition() {
		CompoundTag saved = ProductionEpochCodec.save(ProductionEpoch.freeze(CLUSTER, STRUCTURE_MEMBER, snapshotWithPanel(frozenPanel()).layout()));
		ListTag panels = saved.getList("Panels", Tag.TAG_COMPOUND);
		CompoundTag second = panels.getCompound(0).copy();
		second.putUUID("PanelId", new UUID(0, 100));
		panels.add(second);

		assertTrue(ProductionEpochCodec.load(saved).isEmpty(), "Two panel identities cannot own one physical block");
	}

	@Test
	void codecRejectsDuplicateMissingAndMismatchedCoordinatorIdentity() {
		CompoundTag saved = ProductionEpochCodec.save(epoch());
		CompoundTag duplicate = saved.copy();
		ListTag duplicateNodes = duplicate.getList("Nodes", Tag.TAG_COMPOUND);
		duplicateNodes.getCompound(1).putUUID("ComputerId", LOW);
		CompoundTag missing = saved.copy();
		missing.putUUID("CoordinatorId", EXTRA);
		CompoundTag mismatchedAddress = saved.copy();
		mismatchedAddress.getCompound("CoordinatorAddress").putLong("Pos", new BlockPos(2, 2, 2).asLong());

		assertTrue(ProductionEpochCodec.load(duplicate).isEmpty());
		assertTrue(ProductionEpochCodec.load(missing).isEmpty());
		assertTrue(ProductionEpochCodec.load(mismatchedAddress).isEmpty());
	}

	@Test
	void codecRejectsBadBoundsAndBadProfiles() {
		CompoundTag badBounds = ProductionEpochCodec.save(epoch());
		badBounds.getCompound("Bounds").putInt("MinX", 100);
		CompoundTag outsideBounds = ProductionEpochCodec.save(epoch());
		outsideBounds.getCompound("Bounds").putInt("MaxX", 1);
		CompoundTag badProfile = ProductionEpochCodec.save(epoch());
		badProfile.getList("Nodes", Tag.TAG_COMPOUND).getCompound(0)
			.getCompound("Profile").putInt("ProcessSlots", 999);

		assertTrue(ProductionEpochCodec.load(badBounds).isEmpty());
		assertTrue(ProductionEpochCodec.load(outsideBounds).isEmpty());
		assertTrue(ProductionEpochCodec.load(badProfile).isEmpty());
	}

	@Test
	void codecRejectsWrongKeysTypesVersionsAndNonCompoundNodeLists() {
		List<CompoundTag> invalid = new ArrayList<>();
		CompoundTag wrongVersion = ProductionEpochCodec.save(epoch());
		wrongVersion.putInt("Version", 3);
		invalid.add(wrongVersion);
		CompoundTag topExtra = ProductionEpochCodec.save(epoch());
		topExtra.putInt("Extra", 1);
		invalid.add(topExtra);
		CompoundTag wrongTopType = ProductionEpochCodec.save(epoch());
		wrongTopType.putString("EpochId", "not-a-uuid");
		invalid.add(wrongTopType);
		CompoundTag boundsExtra = ProductionEpochCodec.save(epoch());
		boundsExtra.getCompound("Bounds").putInt("Extra", 1);
		invalid.add(boundsExtra);
		CompoundTag boundsWrongType = ProductionEpochCodec.save(epoch());
		boundsWrongType.getCompound("Bounds").putString("MinX", "0");
		invalid.add(boundsWrongType);
		CompoundTag nodeExtra = ProductionEpochCodec.save(epoch());
		nodeExtra.getList("Nodes", Tag.TAG_COMPOUND).getCompound(0).putInt("Extra", 1);
		invalid.add(nodeExtra);
		CompoundTag addressExtra = ProductionEpochCodec.save(epoch());
		addressExtra.getCompound("CoordinatorAddress").putInt("Extra", 1);
		invalid.add(addressExtra);
		CompoundTag profileExtra = ProductionEpochCodec.save(epoch());
		profileExtra.getList("Nodes", Tag.TAG_COMPOUND).getCompound(0)
			.getCompound("Profile").putInt("Extra", 1);
		invalid.add(profileExtra);
		CompoundTag stringNodes = ProductionEpochCodec.save(epoch());
		ListTag strings = new ListTag();
		strings.add(StringTag.valueOf("node"));
		stringNodes.put("Nodes", strings);
		invalid.add(stringNodes);

		invalid.forEach(tag -> assertTrue(ProductionEpochCodec.load(tag).isEmpty(), tag::toString));
	}

	@Test
	void codecRequiresOneToTwoHundredFiftySixAlreadySortedNodes() {
		CompoundTag empty = ProductionEpochCodec.save(epoch());
		empty.put("Nodes", new ListTag());
		CompoundTag unsorted = ProductionEpochCodec.save(epoch());
		ListTag original = unsorted.getList("Nodes", Tag.TAG_COMPOUND);
		ListTag reversed = new ListTag();
		for (int i = original.size() - 1; i >= 0; i--) reversed.add(original.getCompound(i).copy());
		unsorted.put("Nodes", reversed);
		CompoundTag oversized = ProductionEpochCodec.save(epoch());
		ListTag tooMany = new ListTag();
		CompoundTag template = oversized.getList("Nodes", Tag.TAG_COMPOUND).getCompound(0);
		for (int i = 0; i < 257; i++) {
			CompoundTag child = template.copy();
			child.putUUID("ComputerId", new UUID(1, i));
			child.getCompound("Address").putLong("Pos", new BlockPos(1, 1, 1).offset(0, 0, i).asLong());
			tooMany.add(child);
		}
		oversized.put("Nodes", tooMany);

		assertTrue(ProductionEpochCodec.load(empty).isEmpty());
		assertTrue(ProductionEpochCodec.load(unsorted).isEmpty());
		assertTrue(ProductionEpochCodec.load(oversized).isEmpty());
	}


	@Test
	void quiescenceAndReformSeamsExposeOnlyTheLockedRuntimeIndependentMethods() {
		Set<String> expected = Set.of("allRootsStopped", "nodeIdle", "nodeRootFree", "nodeMailboxEmpty");
		assertEquals(expected, declaredMethodNames(ProductionQuiescence.class));
		assertTrue(ProductionQuiescence.class.isAssignableFrom(EpochQuiescence.class));
		for (Class<?> type : List.of(ProductionQuiescence.class, EpochQuiescence.class)) {
			Set<String> exposed = new LinkedHashSet<>();
			assertEquals(expected.size(), type.getMethods().length);
			for (Method method : type.getMethods()) {
				exposed.add(method.getName());
				assertEquals(boolean.class, method.getReturnType());
				assertEquals(method.getName().equals("allRootsStopped") ? List.of() : List.of(UUID.class),
					List.of(method.getParameterTypes()));
			}
			assertEquals(expected, exposed);
		}
		assertEquals(Set.of("stopAllAndClear"), declaredMethodNames(ProductionReformControl.class));
		assertTrue(EpochQuiescence.class.isAssignableFrom(EpochReformControl.class));
		assertTrue(ProductionReformControl.class.isAssignableFrom(EpochReformControl.class));
		Set<String> reformMethods = new LinkedHashSet<>(expected);
		reformMethods.add("stopAllAndClear");
		for (Class<?> type : List.of(ProductionReformControl.class, EpochReformControl.class)) {
			Set<String> exposed = new LinkedHashSet<>();
			assertEquals(reformMethods.size(), type.getMethods().length);
			for (Method method : type.getMethods()) {
				exposed.add(method.getName());
				assertEquals(boolean.class, method.getReturnType());
				assertEquals(method.getName().equals("allRootsStopped") || method.getName().equals("stopAllAndClear")
					? List.of() : List.of(UUID.class), List.of(method.getParameterTypes()));
			}
			assertEquals(reformMethods, exposed);
		}
	}

	private static ProductionEpoch epoch() {
		return ProductionEpoch.freeze(CLUSTER, STRUCTURE_MEMBER, snapshot(ORIGINAL_BOUNDS, ORIGINAL_NODES).layout());
	}

	private static ProductionLayout.Panel frozenPanel() {
		return new ProductionLayout.Panel(EXTRA, address(new BlockPos(0, 1, 1)),
			Direction.WEST, ComputerShellMaterial.TRANSPARENT);
	}

	private static ProductionStructure snapshotWithPanel(ProductionLayout.Panel panel) {
		return new ProductionStructure(new ProductionLayout(productionBounds(ORIGINAL_BOUNDS), ORIGINAL_NODES, List.of(panel)), shell(ORIGINAL_BOUNDS), chunks(ORIGINAL_BOUNDS));
	}

	private static Set<String> declaredMethodNames(Class<?> type) {
		Set<String> names = new LinkedHashSet<>();
		for (Method method : type.getDeclaredMethods()) names.add(method.getName());
		return names;
	}

	private static void assertFrozenIdentity(ProductionEpoch before, ProductionEpoch after) {
		assertEquals(before.epochId(), after.epochId());
		assertEquals(before.groupId(), after.groupId());
		assertEquals(before.structureId(), after.structureId());
		assertEquals(before.coordinatorId(), after.coordinatorId());
		assertEquals(before.layout().members().stream().map(ProductionLayout.Member::computerId).toList(),
			after.layout().members().stream().map(ProductionLayout.Member::computerId).toList());
		assertEquals(before.layout().members().stream().map(ProductionLayout.Member::profile).toList(),
			after.layout().members().stream().map(ProductionLayout.Member::profile).toList());
	}

	private static List<ProductionLayout.Member> replace(List<ProductionLayout.Member> nodes, UUID id,
		ProductionLayout.Member replacement) {
		return nodes.stream().map(node -> node.computerId().equals(id) ? replacement : node).toList();
	}

	private static ProductionStructure transformedSnapshot(BoundingBox bounds,
		List<ProductionLayout.Member> nodes, UnaryOperator<BlockPos> transform, ResourceKey<Level> dimension,
		UUID subLevelId, List<ProductionLayout.Member> extras) {
		BoundingBox transformedBounds = transformedBounds(bounds, transform);
		List<ProductionLayout.Member> transformed = new ArrayList<>();
		for (ProductionLayout.Member node : nodes)
			transformed.add(new ProductionLayout.Member(node.computerId(),
				new ProductionLayout.Address(dimension, subLevelId, transform.apply(node.address().localPos())), node.profile()));
		transformed.addAll(extras);
		transformed.sort(Comparator.comparing(ProductionLayout.Member::computerId));
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

	private static ProductionStructure snapshot(BoundingBox bounds, List<ProductionLayout.Member> nodes) {
		return new ProductionStructure(new ProductionLayout(productionBounds(bounds), nodes, List.of()), shell(bounds), chunks(bounds));
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

	private static ProductionLayout.Member node(UUID id, BlockPos pos, ProductionResourceProfile profile) {
		return new ProductionLayout.Member(id, address(pos), profile);
	}

	private static ProductionLayout.Address address(BlockPos pos) {
		return new ProductionLayout.Address(Level.OVERWORLD, SPACE, pos);
	}

	private static ProductionResourceProfile villager() {
		return ProductionResourceProfile.forProfession(ProductionProfession.UNEMPLOYED, 1, 0).orElseThrow();
	}

	private static ProductionResourceProfile librarian() {
		return ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 2, 0).orElseThrow();
	}

	private static ProductionResourceProfile nitwit() {
		return ProductionResourceProfile.forProfession(ProductionProfession.NITWIT, 1, 0).orElseThrow();
	}

	private static ProductionResourceProfile trader(int bonus) {
		return new ProductionResourceProfile(ProductionProfession.WANDERING_TRADER, 2,
			Set.of(ProductionCapability.EXTENDED_RADIO), bonus);
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
	private static List<Integer> boundsTuple(ProductionLayout.Bounds bounds) {
		return List.of(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}
	private static ProductionLayout.Bounds productionBounds(BoundingBox bounds) {
		return new ProductionLayout.Bounds(bounds.minX(), bounds.minY(), bounds.minZ(),
			bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}
}
