package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public class ComputerCoordinatorMemberTest {
	private static final RegistryAccess REGISTRIES = RegistryAccess.EMPTY;
	private static final ComputerStructureScanner.Limits LIMITS =
		new ComputerStructureScanner.Limits(3, 4, 64, 16);
	private static final ComputerProfile PROFILE =
		ComputerProfile.forKind(NodeKind.LIBRARIAN, 3, 0).orElseThrow();
	private static final UUID LOW = uuid(1);
	private static final UUID HIGH = uuid(2);

	@BeforeAll
	static void bootstrap() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			java.lang.reflect.Field field =
				net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void stableAdapterUsesStructureIdentityAndOnlyReadyElectedComputerIsPublished() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ComputerCoordinatorMember lowDormant = fixture.be(LOW).coordinatorMember;
		ComputerCoordinatorMember highDormant = fixture.be(HIGH).coordinatorMember;

		fixture.refresh(LOW);

		UUID structureId = fixture.be(LOW).computerStructureMemberId().orElseThrow();
		assertSame(lowDormant, fixture.be(LOW).coordinatorMember);
		assertSame(highDormant, fixture.be(HIGH).coordinatorMember);
		assertFalse(ClusterMember.class.isInstance(fixture.be(LOW)));
		assertEquals(structureId, lowDormant.memberId());
		assertEquals(LOW, lowDormant.coordinatorComputerId());
		assertNotEquals(lowDormant.memberId(), lowDormant.coordinatorComputerId());
		assertEquals(ClusterMemberType.COMPUTER_COORDINATOR, lowDormant.memberType());
		assertSame(lowDormant, fixture.be(LOW).publishedCoordinatorMember().orElseThrow());
		assertTrue(fixture.be(HIGH).publishedCoordinatorMember().isEmpty());

		Fixture notReady = fixture(empty(LOW, pos(0)));
		notReady.refresh(LOW);
		assertTrue(notReady.be(LOW).currentStructureRecord().isPresent());
		assertEquals(ComputerAvailabilityReason.NOT_READY,
			notReady.be(LOW).availabilityReason());
		assertTrue(notReady.be(LOW).publishedCoordinatorMember().isEmpty());
	}

	@Test
	void profileLossWithdrawsPublishedCoordinatorBeforeNotReadyStateIsDiscarded() {
		Fixture fixture = readyBoundFixture();
		ComputerCoordinatorMember adapter = fixture.be(LOW).coordinatorMember;

		fixture.be(HIGH).clearResidentSource();

		assertTrue(fixture.be(LOW).publishedCoordinatorMember().isEmpty());
		assertFalse(adapter.canRebind());
	}

	@Test
	void prepareRejectsIdentityEpochFaultMissingReplicaRevisionAndUnreadyAuthorityWithoutMutation() {
		Fixture fixture = readyBoundFixture();
		ComputerCoordinatorMember adapter = fixture.be(LOW).coordinatorMember;
		ClusterBinding current = adapter.bindingState();
		ClusterBinding next = binding(adapter, current.revision() + 1,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, adapter.memberId()));

		assertPreparationDoesNotMutate(fixture, adapter, new ClusterBinding(next.clusterId(),
			next.revision(), null, next.logisticsBindings()), ClusterBindingPreparation.IDENTITY);

		ClusterEpoch epoch = ClusterEpoch.freeze(current.clusterId(), adapter.memberId(),
			fixture.snapshot);
		for (ComputerBlockEntity computer : fixture.computers.values()) {
			ComputerBlockEntity.TopologyState state = computer.topologyState();
			computer.applyTopologyState(state.record(), state.binding(), true, epoch, Set.of());
		}
		assertPreparationDoesNotMutate(fixture, adapter, next, ClusterBindingPreparation.ACTIVE);

		for (ComputerBlockEntity computer : fixture.computers.values()) {
			ComputerBlockEntity.TopologyState state = computer.topologyState();
			computer.applyTopologyState(state.record(), state.binding(), true, epoch,
				Set.of(EpochFault.WIDTH));
		}
		assertPreparationDoesNotMutate(fixture, adapter, next, ClusterBindingPreparation.ACTIVE);

		Fixture missing = readyBoundFixture();
		ComputerCoordinatorMember missingAdapter = missing.be(LOW).coordinatorMember;
		missing.unload(HIGH);
		assertPreparationDoesNotMutate(missing, missingAdapter,
			binding(missingAdapter, missingAdapter.bindingState().revision() + 1,
				new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
					missingAdapter.memberId())), ClusterBindingPreparation.IDENTITY);

		Fixture stale = readyBoundFixture();
		ComputerCoordinatorMember staleAdapter = stale.be(LOW).coordinatorMember;
		ClusterBinding ahead = binding(staleAdapter, 5,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
				staleAdapter.memberId()));
		ComputerBlockEntity high = stale.be(HIGH);
		high.applyTopologyState(high.currentStructureRecord().orElseThrow(), ahead,
			true, null, Set.of());
		assertPreparationDoesNotMutate(stale, staleAdapter,
			binding(staleAdapter, 4, ahead.authority()), ClusterBindingPreparation.REVISION);
		ClusterBinding divergent = new ClusterBinding(ahead.clusterId(), 5,
			ahead.authority(), List.of(new LogisticsBinding(uuid(900), "divergent")));
		assertPreparationDoesNotMutate(stale, staleAdapter, divergent,
			ClusterBindingPreparation.REVISION);

		Fixture offline = readyBoundFixture();
		offline.bindingAccess = ClusterBindingService.BindingAccess.AUTHORITY_OFFLINE;
		offline.refresh(LOW);
		assertEquals(ComputerAvailabilityReason.AUTHORITY_OFFLINE,
			offline.be(LOW).availabilityReason());
		assertPreparationDoesNotMutate(offline, offline.be(LOW).coordinatorMember,
			binding(offline.be(LOW).coordinatorMember, 2,
				new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
					offline.be(LOW).coordinatorMember.memberId())),
			ClusterBindingPreparation.ACTIVE);

		List<LogisticsBinding> oversized = new ArrayList<>();
		for (int i = 0; i <= ClusterBinding.MAX_BINDINGS; i++)
			oversized.add(new LogisticsBinding(uuid(1000 + i), "n" + i));
		assertThrows(IllegalArgumentException.class, () -> new ClusterBinding(uuid(300), 1,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, adapter.memberId()),
			oversized));
	}

	@Test
	void successfulPrepareCachesExactReplicasAndCommitWritesOneBindingWithoutWorldRescan() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.refresh(LOW);
		ComputerCoordinatorMember adapter = fixture.be(LOW).coordinatorMember;
		ClusterBinding prepared = binding(adapter, 1,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, adapter.memberId()));
		fixture.resetProbes();

		assertEquals(ClusterBindingPreparation.READY,
			adapter.prepareClusterBinding(prepared));
		int afterPrepare = fixture.worldProbes();
		adapter.commitClusterBinding(prepared);

		assertEquals(afterPrepare, fixture.worldProbes());
		assertSame(prepared, fixture.be(LOW).bindingState());
		assertSame(prepared, fixture.be(HIGH).bindingState());
	}

	@Test
	void activeEpochRejectsBindingButNaturalClosePreservesIdentityAndAllowsNextCommit() {
		Fixture fixture = readyBoundFixture();
		ComputerCoordinatorMember adapter = fixture.be(LOW).coordinatorMember;
		UUID structureId = adapter.memberId();
		ClusterBinding old = adapter.bindingState();
		assertEquals(ComputerBlockEntity.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(fixture.be(LOW), old.clusterId(),
				fixture, LIMITS));
		ClusterBinding next = binding(adapter, old.revision() + 1,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, structureId));
		assertEquals(ClusterBindingPreparation.ACTIVE,
			adapter.prepareClusterBinding(next));

		UUID epochId = fixture.be(LOW).epoch().orElseThrow().epochId();
		assertEquals(ComputerBlockEntity.EpochCloseResult.CLOSED,
			ComputerTopologyController.closeIdleEpoch(fixture.be(LOW), epochId,
				new ReadyQuiescence(), fixture, LIMITS));

		assertEquals(structureId, adapter.memberId());
		assertSame(old, adapter.bindingState());
		assertEquals(ClusterBindingPreparation.READY,
			adapter.prepareClusterBinding(next));
		adapter.commitClusterBinding(next);
		fixture.computers.values().forEach(computer -> assertSame(next, computer.bindingState()));
	}

	@Test
	void lowerReplicaAdoptsOnlyReadyFoundationStateAndNeverPublishesOverCoordinator() {
		Fixture fixture = readyBoundFixture();
		ComputerCoordinatorMember adapter = fixture.be(LOW).coordinatorMember;
		ClusterBinding authoritative = binding(adapter, 2,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, adapter.memberId()));
		assertEquals(ClusterBindingPreparation.READY,
			adapter.prepareClusterBinding(authoritative));
		adapter.commitClusterBinding(authoritative);
		ClusterBinding lower = new ClusterBinding(authoritative.clusterId(), 1,
			authoritative.authority(), authoritative.logisticsBindings());
		ComputerBlockEntity replica = fixture.be(HIGH);
		replica.applyTopologyState(replica.currentStructureRecord().orElseThrow(), lower,
			true, null, Set.of());

		fixture.bindingAccess = ClusterBindingService.BindingAccess.AUTHORITY_OFFLINE;
		fixture.refresh(LOW);
		assertSame(lower, replica.bindingState());
		assertTrue(replica.publishedCoordinatorMember().isEmpty());

		fixture.bindingAccess = ClusterBindingService.BindingAccess.READY;
		fixture.refresh(LOW);
		assertSame(authoritative, replica.bindingState());
		assertSame(adapter, fixture.be(LOW).publishedCoordinatorMember().orElseThrow());
		assertTrue(replica.publishedCoordinatorMember().isEmpty());

		ClusterBinding divergent = new ClusterBinding(authoritative.clusterId(),
			authoritative.revision(), authoritative.authority(),
			List.of(new LogisticsBinding(uuid(999), "divergent")));
		replica.applyTopologyState(replica.currentStructureRecord().orElseThrow(), divergent,
			true, null, Set.of());
		assertFalse(adapter.updateBindingAccess(ClusterBindingService.BindingAccess.READY));
		assertFalse(adapter.canRebind());
	}

	@Test
	void unloadReloadAndPostCloseReelectionUseExactAdaptersAndStableStructureIdentity() {
		Fixture fixture = readyBoundFixture();
		ComputerCoordinatorMember original = fixture.be(LOW).coordinatorMember;
		UUID structureId = original.memberId();
		CompoundTag saved = save(fixture.be(LOW));
		fixture.unload(LOW);
		assertTrue(fixture.be(HIGH).publishedCoordinatorMember().isEmpty());

		ComputerBlockEntity reloaded = computer(LOW, pos(0), PROFILE);
		reloaded.read(saved, REGISTRIES, false);
		fixture.put(reloaded);
		fixture.refresh(LOW);
		assertNotSame(original, reloaded.coordinatorMember);
		assertEquals(structureId, reloaded.coordinatorMember.memberId());
		assertEquals(ComputerAvailabilityReason.NONE, reloaded.availabilityReason());
		assertSame(reloaded.coordinatorMember,
			reloaded.publishedCoordinatorMember().orElseThrow());

		ClusterBinding binding = reloaded.bindingState();
		assertEquals(ComputerBlockEntity.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(reloaded, binding.clusterId(), fixture, LIMITS));
		UUID epochId = reloaded.epoch().orElseThrow().epochId();
		assertEquals(ComputerBlockEntity.EpochCloseResult.CLOSED,
			ComputerTopologyController.closeIdleEpoch(reloaded, epochId,
				new ReadyQuiescence(), fixture, LIMITS));
		fixture.destroy(LOW);
		fixture.snapshot = snapshot(List.of(profiledNode(HIGH, pos(2))));
		fixture.refresh(HIGH);
		assertEquals(structureId, fixture.be(HIGH).coordinatorMember.memberId());
		assertSame(fixture.be(HIGH).coordinatorMember,
			fixture.be(HIGH).publishedCoordinatorMember().orElseThrow());
	}

	@Test
	void activeCoordinatorLossPublishesNoReplacement() {
		Fixture fixture = readyBoundFixture();
		ClusterBinding binding = fixture.be(LOW).bindingState();
		assertEquals(ComputerBlockEntity.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(fixture.be(LOW), binding.clusterId(),
				fixture, LIMITS));

		fixture.unload(LOW);
		fixture.snapshot = snapshot(List.of(profiledNode(HIGH, pos(2))));
		fixture.refresh(HIGH);

		assertTrue(fixture.be(HIGH).publishedCoordinatorMember().isEmpty());
		assertEquals(ComputerAvailabilityReason.COORDINATOR_MISSING,
			fixture.be(HIGH).availabilityReason());
	}

	@Test
	void startEpochRequiresSameTickOwnedAdapterAndReadyFoundationAccess() {
		Fixture unbound = fixture(profiled(LOW, pos(0)));
		unbound.refresh(LOW);
		assertEquals(ComputerBlockEntity.EpochStartResult.BINDING_UNAVAILABLE,
			ComputerTopologyController.startEpoch(unbound.be(LOW), uuid(400), unbound, LIMITS));

		Fixture offline = readyBoundFixture(profiled(LOW, pos(0)));
		offline.bindingAccess = ClusterBindingService.BindingAccess.AUTHORITY_OFFLINE;
		assertEquals(ComputerBlockEntity.EpochStartResult.BINDING_UNAVAILABLE,
			ComputerTopologyController.startEpoch(offline.be(LOW),
				offline.be(LOW).bindingState().clusterId(), offline, LIMITS));

		Fixture conflict = readyBoundFixture(profiled(LOW, pos(0)));
		conflict.bindingAccess = ClusterBindingService.BindingAccess.CONFLICT;
		assertEquals(ComputerBlockEntity.EpochStartResult.BINDING_UNAVAILABLE,
			ComputerTopologyController.startEpoch(conflict.be(LOW),
				conflict.be(LOW).bindingState().clusterId(), conflict, LIMITS));

		Fixture ready = readyBoundFixture(profiled(LOW, pos(0)));
		assertEquals(ComputerBlockEntity.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(ready.be(LOW),
				ready.be(LOW).bindingState().clusterId(), ready, LIMITS));
	}

	private static void assertPreparationDoesNotMutate(Fixture fixture,
		ComputerCoordinatorMember adapter, ClusterBinding proposed,
		ClusterBindingPreparation expected) {
		Map<UUID, CompoundTag> before = bytes(fixture);
		assertEquals(expected, adapter.prepareClusterBinding(proposed));
		assertEquals(before, bytes(fixture));
	}

	private static Fixture readyBoundFixture(NodeSpec... specs) {
		Fixture fixture = specs.length == 0
			? fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2))) : fixture(specs);
		fixture.refresh(fixture.snapshot.nodes().getFirst().computerId());
		ComputerCoordinatorMember adapter = fixture.snapshot.nodes().stream()
			.map(ComputerStructureNode::computerId).map(fixture::be)
			.map(computer -> computer.publishedCoordinatorMember().orElse(null))
			.filter(Objects::nonNull).findFirst().orElseThrow();
		ClusterBinding initial = binding(adapter, 1,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, adapter.memberId()));
		assertEquals(ClusterBindingPreparation.READY,
			adapter.prepareClusterBinding(initial));
		adapter.commitClusterBinding(initial);
		fixture.bindingAccess = ClusterBindingService.BindingAccess.READY;
		return fixture;
	}

	private static ClusterBinding binding(ComputerCoordinatorMember member, long revision,
		ClusterAuthority authority) {
		UUID cluster = member.bindingState() == null ? uuid(500)
			: member.bindingState().clusterId();
		return new ClusterBinding(cluster, revision, authority,
			List.of(new LogisticsBinding(uuid(501), "network")));
	}

	private static Fixture fixture(NodeSpec... specs) { return new Fixture(specs); }

	public static PublishedStructure publishedStructure(UUID structureId,
		List<UUID> computerIds) {
		List<ComputerBlockEntity> computers = new ArrayList<>();
		List<ComputerStructureNode> nodes = new ArrayList<>();
		for (int i = 0; i < computerIds.size(); i++) {
			BlockPos position = pos(i);
			UUID computerId = computerIds.get(i);
			computers.add(computer(computerId, position, PROFILE));
			nodes.add(new ComputerStructureNode(computerId, address(position), PROFILE));
		}
		ComputerStructureSnapshot structure = snapshot(nodes);
		ComputerStructureRecord record = new ComputerStructureRecord(structureId, 0,
			computerIds.getFirst(), structure);
		computers.forEach(computer -> computer.applyTopologyState(record, null,
			true, null, Set.of()));
		computers.getFirst().publishCoordinatorMember(computers, structure.computerIds());
		return new PublishedStructure(structureId, record, computers);
	}

	public static final class PublishedStructure {
		private final UUID structureId;
		private final ComputerStructureRecord record;
		private final List<ComputerBlockEntity> computers;

		private PublishedStructure(UUID structureId, ComputerStructureRecord record,
			List<ComputerBlockEntity> computers) {
			this.structureId = structureId;
			this.record = record;
			this.computers = List.copyOf(computers);
		}

		public UUID structureId() { return structureId; }
		public ComputerStructureRecord record() { return record; }
		public List<ComputerBlockEntity> computers() { return computers; }
		public ComputerCoordinatorMember coordinator() {
			return computers.getFirst().coordinatorMember;
		}
		public void installTopology(int index, ClusterBinding binding) {
			computers.get(index).applyTopologyState(record, binding, true, null, Set.of());
		}
		public void republish() {
			computers.getFirst().publishCoordinatorMember(computers,
				record.snapshot().computerIds());
		}
	}

	private static NodeSpec profiled(UUID id, BlockPos pos) { return new NodeSpec(id, pos, PROFILE); }
	private static NodeSpec empty(UUID id, BlockPos pos) { return new NodeSpec(id, pos, null); }
	private static ComputerStructureNode profiledNode(UUID id, BlockPos pos) {
		return new ComputerStructureNode(id, address(pos), PROFILE);
	}

	private static ComputerBlockEntity computer(UUID id, BlockPos pos,
		ComputerProfile profile) {
		ComputerBlockEntity computer = new TestComputer(pos);
		CompoundTag saved = save(computer);
		saved.getCompound("ComputerData").putUUID("ComputerId", id);
		computer.read(saved, REGISTRIES, false);
		if (profile != null)
			computer.installResidentSnapshot(capturedBox(id), profile);
		return computer;
	}
	private static ItemStack capturedBox(UUID id) {
		ItemStack stack = new ItemStack(Items.PAPER);
		CompoundTag captured = new CompoundTag();
		captured.putString("id", "minecraft:villager");
		captured.putUUID("UUID", id);
		captured.put("Offers", new CompoundTag());
		captured.put("Brain", new CompoundTag());
		captured.put("Inventory", new ListTag());
		CompoundTag itemData = new CompoundTag();
		itemData.put("CapturedEntity", captured);
		CBItemData.set(stack, itemData);
		return stack;
	}

	private static ComputerStructureSnapshot snapshot(List<ComputerStructureNode> nodes) {
		BoundingBox bounds = new BoundingBox(0, 0, 0, 2, 2, 2);
		Set<BlockPos> casing = new LinkedHashSet<>();
		for (int x = 0; x <= 2; x++)
			for (int y = 0; y <= 2; y++)
				for (int z = 0; z <= 2; z++)
					if (x == 0 || x == 2 || y == 0 || y == 2 || z == 0 || z == 2)
						casing.add(new BlockPos(x, y, z));
		return new ComputerStructureSnapshot(bounds,
			nodes.stream().sorted(java.util.Comparator.comparing(
				ComputerStructureNode::computerId)).toList(), casing,
			Set.of(new ChunkPos(0, 0)));
	}

	private static BlockPos pos(int x) { return new BlockPos(x, 1, 1); }
	private static SpaceAddress address(BlockPos pos) {
		return new SpaceAddress(Level.OVERWORLD, null, pos);
	}
	private static UUID uuid(long value) { return new UUID(0, value); }
	private static CompoundTag save(ComputerBlockEntity computer) {
		CompoundTag tag = new CompoundTag();
		computer.write(tag, REGISTRIES, false);
		return tag;
	}
	private static Map<UUID, CompoundTag> bytes(Fixture fixture) {
		Map<UUID, CompoundTag> result = new LinkedHashMap<>();
		fixture.computers.values().forEach(computer ->
			result.put(computer.computerId().orElseThrow(), save(computer)));
		return result;
	}

	private record NodeSpec(UUID id, BlockPos pos, ComputerProfile profile) {}

	private static final class TestComputer extends ComputerBlockEntity {
		private final SpaceAddress address;
		private TestComputer(BlockPos pos) {
			super(BlockEntityType.FURNACE, pos, Blocks.FURNACE.defaultBlockState());
			address = ComputerCoordinatorMemberTest.address(pos);
		}
		@Override SpaceAddress coordinatorMemberAddress() { return address; }
	}

	private static final class Fixture implements ComputerTopologyController.WorldAccess {
		private final Map<UUID, ComputerBlockEntity> computers = new LinkedHashMap<>();
		private final Map<BlockPos, ComputerBlockEntity> byPosition = new HashMap<>();
		private final Set<BlockPos> unloaded = new HashSet<>();
		private ComputerStructureSnapshot snapshot;
		private ComputerStructureScanner.State scanState;
		private ClusterBindingService.BindingAccess bindingAccess =
			ClusterBindingService.BindingAccess.CONFLICT;
		private int probes;

		private Fixture(NodeSpec... specs) {
			List<ComputerStructureNode> nodes = new ArrayList<>();
			for (NodeSpec spec : specs) {
				ComputerBlockEntity computer = computer(spec.id(), spec.pos(), spec.profile());
				put(computer);
				nodes.add(new ComputerStructureNode(spec.id(), address(spec.pos()), spec.profile()));
			}
			snapshot = snapshot(nodes);
			scanState = nodes.stream().anyMatch(node -> node.profile() == null)
				? ComputerStructureScanner.State.VALID_NOT_READY
				: ComputerStructureScanner.State.VALID;
		}

		private void put(ComputerBlockEntity computer) {
			UUID id = computer.computerId().orElseThrow();
			computers.put(id, computer);
			byPosition.put(computer.getBlockPos(), computer);
			unloaded.remove(computer.getBlockPos());
		}
		private ComputerBlockEntity be(UUID id) {
			return Objects.requireNonNull(computers.get(id));
		}
		private void refresh(UUID id) {
			ComputerTopologyController.refresh(be(id), this, LIMITS);
		}
		private void unload(UUID id) {
			ComputerBlockEntity computer = be(id);
			computer.invalidate();
			computers.remove(id);
			byPosition.remove(computer.getBlockPos());
			unloaded.add(computer.getBlockPos());
		}
		private void destroy(UUID id) {
			ComputerBlockEntity computer = be(id);
			computer.invalidate();
			computers.remove(id);
			byPosition.remove(computer.getBlockPos());
			unloaded.remove(computer.getBlockPos());
		}
		private void resetProbes() { probes = 0; }
		private int worldProbes() { return probes; }

		@Override public ComputerStructureScanner.ScanResult scan(BlockPos seed,
			ComputerStructureScanner.Limits limits) {
			probes++;
			UUID observed = computers.values().stream()
				.map(ComputerBlockEntity::computerStructureMemberId)
				.flatMap(java.util.Optional::stream).findFirst().orElse(null);
			return new ComputerStructureScanner.ScanResult(scanState, snapshot, observed);
		}
		@Override public boolean isLoaded(BlockPos pos) { probes++; return !unloaded.contains(pos); }
		@Override public boolean isChunkLoaded(ChunkPos chunk) { probes++; return true; }
		@Override public boolean sameSpace(BlockPos origin, BlockPos pos) { probes++; return true; }
		@Override public ComputerBlockEntity loadedComputer(BlockPos pos) {
			probes++;
			return unloaded.contains(pos) ? null : byPosition.get(pos);
		}
		@Override public SpaceAddress address(BlockPos pos) { return ComputerCoordinatorMemberTest.address(pos); }
		@Override public UUID newStructureMemberId() { return uuid(600); }
		@Override public ClusterBindingService.BindingAccess bindingAccess(
			ComputerCoordinatorMember member) { return bindingAccess; }
		@Override public boolean enforceFoundationPublication() { return true; }
	}

	private static final class ReadyQuiescence implements EpochQuiescence {
		@Override public boolean allRootsStopped() { return true; }
		@Override public boolean nodeIdle(UUID computerId) { return true; }
		@Override public boolean nodeRootFree(UUID computerId) { return true; }
		@Override public boolean nodeMailboxEmpty(UUID computerId) { return true; }
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null) return;
			Object empty = type.getMethod("of", List.class, List.class, List.class,
				List.class, java.util.Map.class).invoke(null, List.of(), List.of(),
					List.of(), List.of(), java.util.Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
