package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructureCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer.*;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

class ComputerStructureLocatorTest {
	private static final RegistryAccess REGISTRIES = RegistryAccess.EMPTY;
	private static final ComputerStructureScanner.Limits LIMITS =
		new ComputerStructureScanner.Limits(3, 3, 27, 8);
	private static final ProductionResourceProfile PROFILE = ProductionResourceProfile
		.forProfession(ProductionProfession.LIBRARIAN, 3, 0).orElseThrow();

	@BeforeAll
	static void bootstrapMinecraft() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			Field field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void uniqueClickedShellCasingResolvesCoordinatorWithoutCasingFlood() {
		LocatorWorld world = new LocatorWorld();
		Structure structure = world.addStructure(uuid(10), uuid(11), 0, pos(0, 0, 0));

		assertSame(structure.coordinator,
			ComputerStructureLocator.findCoordinatorEntity(world, pos(0, 0, 1), LIMITS)
				.orElseThrow());
		assertTrue(world.candidateReads <= 126);
	}

	@Test
	void externalSpurDoesNotResolveWhileThatStructuresShellStillDoes() {
		LocatorWorld world = new LocatorWorld();
		world.addStructure(uuid(20), uuid(21), 0, pos(0, 0, 0));

		assertTrue(ComputerStructureLocator.findCoordinatorEntity(world, pos(0, 0, 3), LIMITS)
			.isEmpty());
		assertTrue(ComputerStructureLocator.findCoordinatorEntity(world, pos(0, 0, 1), LIMITS)
			.isPresent());
	}

	@Test
	void nearbyUnrelatedStructureCannotClaimClickedCasingOutsideItsSnapshot() {
		LocatorWorld world = new LocatorWorld();
		Structure first = world.addStructure(uuid(30), uuid(31), 0, pos(0, 0, 0));
		world.addStructure(uuid(40), uuid(41), 4, pos(4, 0, 0));

		assertSame(first.coordinator,
			ComputerStructureLocator.findCoordinatorEntity(world, pos(0, 0, 1),
				new ComputerStructureScanner.Limits(3, 6, 216, 8)).orElseThrow());
	}

	@Test
	void ambiguousClickedCasingReturnsEmpty() {
		LocatorWorld world = new LocatorWorld();
		Structure first = world.addStructure(uuid(50), uuid(51), 0, pos(0, 0, 0));
		Structure second = world.addStructure(uuid(60), uuid(61), 1, pos(1, 0, 0));
		BlockPos clicked = pos(1, 0, 1);
		assertTrue(first.scan.casingPositions().contains(clicked));
		assertTrue(second.scan.casingPositions().contains(clicked));

		assertTrue(ComputerStructureLocator.findCoordinatorEntity(world, clicked, LIMITS).isEmpty());
	}

	@Test
	void unloadedCandidateReturnsEmptyWithoutLoadingOrReadingIt() {
		LocatorWorld world = new LocatorWorld();
		Structure structure = world.addStructure(uuid(70), uuid(71), 0, pos(0, 0, 0));
		world.unloaded.add(structure.coordinator.owner().getBlockPos());

		assertTrue(ComputerStructureLocator.findCoordinatorEntity(world, pos(0, 0, 1), LIMITS)
			.isEmpty());
		assertEquals(0, world.forbiddenReads);
	}

	@Test
	void sableSpaceMismatchReturnsEmptyBeforeEntityRead() {
		LocatorWorld world = new LocatorWorld();
		Structure structure = world.addStructure(uuid(80), uuid(81), 0, pos(0, 0, 0));
		world.wrongSpace.add(structure.coordinator.owner().getBlockPos());

		assertTrue(ComputerStructureLocator.findCoordinatorEntity(world, pos(0, 0, 1), LIMITS)
			.isEmpty());
		assertEquals(0, world.forbiddenReads);
	}

	@Test
	void structurallyEqualIndependentlyDecodedStoredAndRescannedSnapshotsResolve() {
		LocatorWorld world = new LocatorWorld();
		Structure structure = world.addStructure(uuid(90), uuid(91), 0, pos(0, 0, 0));
		ProductionStructure decoded = ProductionStructureCodec.loadSnapshot(ProductionStructureCodec.saveSnapshot(structure.scan))
			.orElseThrow();
		assertTrue(decoded != structure.scan);
		world.scans.put(structure.coordinator.owner().getBlockPos(), decoded);

		assertSame(structure.coordinator,
			ComputerStructureLocator.findCoordinatorEntity(world, pos(0, 0, 1), LIMITS)
				.orElseThrow());
	}

	@Test
	void moveOrUnloadRaceInAnyStructuralIdentityComponentReturnsEmpty() {
		for (Race race : Race.values()) {
			LocatorWorld world = new LocatorWorld();
			Structure structure = world.addStructure(uuid(100 + race.ordinal() * 2L),
				uuid(101 + race.ordinal() * 2L), 0, pos(0, 0, 0));
			world.race = race;
			world.raceTarget = structure;

			assertTrue(ComputerStructureLocator.findCoordinatorEntity(world, pos(0, 0, 1), LIMITS)
				.isEmpty(), race.name());
		}
	}

	private static ProductionStructure withAdditionalCasing(ProductionStructure source,
		BlockPos added) {
		Set<BlockPos> casing = new HashSet<>(source.casingPositions());
		casing.add(added);
		return new ProductionStructure(new ProductionLayout(source.layout().bounds(), source.layout().members(), List.of()), casing, source.containingChunks());
	}

	private static UUID uuid(long value) { return new UUID(0, value); }
	private static BlockPos pos(int x, int y, int z) { return new BlockPos(x, y, z); }
	private static ProductionLayout.Address address(BlockPos pos) {
		return new ProductionLayout.Address(Level.OVERWORLD, null, pos);
	}

	private static ProductionComputer computer(UUID id, BlockPos pos) {
		ProductionComputer computer = TestComputer.create(BlockEntityType.FURNACE, pos,
			Blocks.FURNACE.defaultBlockState());
		CompoundTag saved = new CompoundTag();
		physical(computer).write(saved, REGISTRIES, false);
		saved.getCompound("ComputerData").putUUID("ComputerId", id);
		physical(computer).read(saved, REGISTRIES, false);
		physical(computer).installResidentSnapshot(new ItemStack(Items.PAPER), PROFILE);
		return computer;
	}

	private static ProductionStructure snapshot(int offset,
		List<ProductionLayout.Member> nodes) {
		BoundingBox bounds = new BoundingBox(offset, 0, 0, offset + 2, 2, 2);
		Set<BlockPos> casing = new HashSet<>();
		for (int x = bounds.minX(); x <= bounds.maxX(); x++)
			for (int y = 0; y <= 2; y++)
				for (int z = 0; z <= 2; z++)
					if (x == bounds.minX() || x == bounds.maxX() || y == 0 || y == 2
						|| z == 0 || z == 2) casing.add(pos(x, y, z));
		return new ProductionStructure(new ProductionLayout(productionBounds(bounds), nodes.stream()
			.sorted(java.util.Comparator.comparing(ProductionLayout.Member::computerId)).toList(), List.of()), casing, Set.of(new ChunkPos(offset >> 4, 0)));
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null) return;
			type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private enum Race { UNLOAD, SNAPSHOT, OBJECT, ADDRESS, COMPUTER_ID, STRUCTURE_MEMBER }

	private static final class Structure {
		private ProductionComputer coordinator;
		private final ProductionComputer other;
		private ProductionStructure scan;
		private final UUID member;

		private Structure(ProductionComputer coordinator, ProductionComputer other,
			ProductionStructure scan, UUID member) {
			this.coordinator = coordinator;
			this.other = other;
			this.scan = scan;
			this.member = member;
		}
	}

	private static final class LocatorWorld implements ComputerTopologyController.WorldAccess {
		private final Map<BlockPos, ProductionComputer> computers = new LinkedHashMap<>();
		private final Map<BlockPos, ProductionStructure> scans = new HashMap<>();
		private final Set<BlockPos> unloaded = new HashSet<>();
		private final Set<BlockPos> wrongSpace = new HashSet<>();
		private int candidateReads;
		private int forbiddenReads;
		private int revalidationScans;
		private Race race;
		private Structure raceTarget;

		private Structure addStructure(UUID coordinatorId, UUID otherId, int offset,
			BlockPos coordinatorPos) {
			ProductionComputer coordinator = computer(coordinatorId, coordinatorPos);
			BlockPos otherPos = pos(offset + 2, 0, 0);
			ProductionComputer other = computer(otherId, otherPos);
			ProductionStructure snapshot = snapshot(offset, List.of(
				new ProductionLayout.Member(coordinatorId, address(coordinatorPos), PROFILE),
				new ProductionLayout.Member(otherId, address(otherPos), PROFILE)));
			UUID member = uuid(1000 + coordinatorId.getLeastSignificantBits());
			ProductionStructure.Revision record = new ProductionStructure.Revision(member, 0,
				coordinatorId, snapshot);
			coordinator.applyTopologyState(record, null, true, null, Set.of());
			other.applyTopologyState(record, null, true, null, Set.of());
			computers.put(coordinatorPos, coordinator);
			computers.put(otherPos, other);
			scans.put(coordinatorPos, snapshot);
			scans.put(otherPos, snapshot);
			return new Structure(coordinator, other, snapshot, member);
		}

		@Override
		public ComputerStructureScanner.ScanResult scan(BlockPos seed,
			ComputerStructureScanner.Limits limits) {
			ProductionStructure snapshot = scans.get(seed);
			if (snapshot == null)
				return new ComputerStructureScanner.ScanResult(
					ComputerStructureScanner.State.UNFORMED, null, null);
			if (race != null && raceTarget != null && seed.equals(raceTarget.coordinator.owner().getBlockPos())
				&& ++revalidationScans == 2) applyRace();
			return new ComputerStructureScanner.ScanResult(ComputerStructureScanner.State.VALID,
				scans.get(seed), computers.get(seed).computerStructureMemberId().orElse(null));
		}

		private void applyRace() {
			Structure target = raceTarget;
			switch (race) {
				case UNLOAD -> unloaded.add(target.coordinator.owner().getBlockPos());
				case SNAPSHOT -> scans.put(target.coordinator.owner().getBlockPos(),
					withAdditionalCasing(target.scan, pos(1, 1, 1)));
				case OBJECT -> computers.put(target.coordinator.owner().getBlockPos(),
					computer(target.coordinator.computerId().orElseThrow(),
						target.coordinator.owner().getBlockPos()));
				case ADDRESS -> {
					ProductionStructure.Revision old = target.coordinator.currentStructureRecord().orElseThrow();
					List<ProductionLayout.Member> changedNodes = old.structure().layout().members().stream()
						.map(node -> node.computerId().equals(old.coordinatorId())
							? new ProductionLayout.Member(node.computerId(), address(pos(0, 0, 1)),
								node.profile()) : node).toList();
					ProductionStructure moved = new ProductionStructure(new ProductionLayout(old.structure().layout().bounds(), changedNodes, List.of()), old.structure().casingPositions(), old.structure().containingChunks());
					target.coordinator.applyTopologyState(new ProductionStructure.Revision(target.member,
						old.revision() + 1, old.coordinatorId(), moved), null, true, null, Set.of());
				}
				case COMPUTER_ID -> computers.put(target.coordinator.owner().getBlockPos(),
					computer(uuid(9999), target.coordinator.owner().getBlockPos()));
				case STRUCTURE_MEMBER -> target.coordinator.applyTopologyState(
					new ProductionStructure.Revision(uuid(9998), 0,
						target.coordinator.computerId().orElseThrow(), target.scan),
					null, true, null, Set.of());
			}
		}

		@Override public boolean isLoaded(BlockPos pos) { return !unloaded.contains(pos); }
		@Override public boolean isChunkLoaded(ChunkPos chunk) { return true; }
		@Override public boolean sameSpace(BlockPos origin, BlockPos pos) {
			return !wrongSpace.contains(pos);
		}
		@Override public ProductionComputer loadedComputer(BlockPos pos) {
			candidateReads++;
			if (unloaded.contains(pos) || wrongSpace.contains(pos)) {
				forbiddenReads++;
				throw new AssertionError("entity read after loaded/same-space failure");
			}
			return computers.get(pos);
		}
		@Override public ProductionLayout.Address address(BlockPos pos) {
			return ComputerStructureLocatorTest.address(pos);
		}
		@Override public UUID newStructureMemberId() { return uuid(5000); }
	}
	private static ProductionLayout.Bounds productionBounds(BoundingBox bounds) {
		return new ProductionLayout.Bounds(bounds.minX(), bounds.minY(), bounds.minZ(),
			bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}
}
