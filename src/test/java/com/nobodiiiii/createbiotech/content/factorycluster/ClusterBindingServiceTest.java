package com.nobodiiiii.createbiotech.content.factorycluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

class ClusterBindingServiceTest {
	private static final String SELECTION_KEY =
		"create_biotech:factory_cluster_binding";
	private static final UUID CLUSTER =
		UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID SOURCE_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000010");
	private static final UUID TARGET_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000020");
	private static final UUID OPEN =
		UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID LOCKED =
		UUID.fromString("00000000-0000-0000-0000-000000000003");
	private static final UUID PARTICIPANT_OLD =
		UUID.fromString("00000000-0000-0000-0000-000000000004");

	private FakeMember source;
	private FakeMember target;
	private FakeMember targetInNether;

	@BeforeEach
	void setUp() {
		source = member(SOURCE_ID, CLUSTER, 2, ClusterMemberType.PANEL,
			new ClusterAuthority(ClusterMemberType.PANEL, SOURCE_ID),
			address(Level.OVERWORLD), List.of(new LogisticsBinding(OPEN, "open")));
		UUID targetCluster = UUID.fromString("00000000-0000-0000-0000-000000000030");
		target = member(TARGET_ID, targetCluster, 4, ClusterMemberType.PANEL,
			new ClusterAuthority(ClusterMemberType.PANEL, TARGET_ID),
			address(Level.OVERWORLD), List.of(new LogisticsBinding(LOCKED, "locked")));
		targetInNether = member(TARGET_ID, targetCluster, 4, ClusterMemberType.PANEL,
			new ClusterAuthority(ClusterMemberType.PANEL, TARGET_ID),
			address(Level.NETHER), List.of(new LogisticsBinding(LOCKED, "locked")));
	}

	@Test
	void bindRejectsDifferentRootDimension() {
		assertEquals(ClusterBindingService.BindResult.DIMENSION,
			ClusterBindingService.validate(source, targetInNether, id -> true,
				List.of(source, targetInNether)));
	}

	@Test
	void bindRequiresAdministrationOfEveryOldAndNewNetwork() {
		assertEquals(ClusterBindingService.BindResult.PERMISSION,
			ClusterBindingService.validate(source, target, id -> !id.equals(LOCKED),
				List.of(source, target)));
	}

	@Test
	void everyLoadedClusterMemberMustBeRebindable() {
		FakeMember active = replica(SOURCE_ID, UUID.randomUUID(), 1,
			List.of(new LogisticsBinding(PARTICIPANT_OLD, "participant")));
		active.rebindable = false;
		assertEquals(ClusterBindingService.BindResult.ACTIVE,
			ClusterBindingService.validate(source, target, id -> true,
				List.of(source, target, active)));
	}

	@Test
	void sourceMustAlreadyBelongToACluster() {
		source.binding = null;
		assertEquals(ClusterBindingService.BindResult.NO_SOURCE,
			ClusterBindingService.validate(source, target, id -> true,
				List.of(source, target)));
	}

	@Test
	void bindingRequiresAtLeastOneLogisticsNetwork() {
		source = member(SOURCE_ID, CLUSTER, 2, ClusterMemberType.PANEL,
			new ClusterAuthority(ClusterMemberType.PANEL, SOURCE_ID),
			address(Level.OVERWORLD), List.of());
		target = member(TARGET_ID, target.binding.clusterId(), 4, ClusterMemberType.PANEL,
			new ClusterAuthority(ClusterMemberType.PANEL, TARGET_ID),
			address(Level.OVERWORLD), List.of());
		assertEquals(ClusterBindingService.BindResult.EMPTY_NETWORKS,
			ClusterBindingService.validate(source, target, id -> true,
				List.of(source, target)));
	}

	@Test
	void knownAuthorityMustBeLoadedBeforeMutation() {
		UUID offlineComputer = UUID.randomUUID();
		source.binding = new ClusterBinding(CLUSTER, 7,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, offlineComputer),
			source.logisticsBindings());

		ClusterBindingService.BindResult result = ClusterBindingService.bind(source,
			target, List.of(source, target), id -> true, false, () -> {});

		assertEquals(ClusterBindingService.BindResult.AUTHORITY_OFFLINE, result);
		assertEquals(0, source.commitCount);
		assertEquals(0, target.commitCount);
	}

	@Test
	void refusingPreparePreventsEveryCommitAndKeepsSelection() {
		FakeMember refusing = replica(SOURCE_ID, UUID.randomUUID(), 1,
			List.of(new LogisticsBinding(PARTICIPANT_OLD, "participant")));
		refusing.prepareResult = ClusterBindingPreparation.IDENTITY;
		AtomicBoolean selectionCleared = new AtomicBoolean();

		ClusterBindingService.BindResult result = ClusterBindingService.bind(source,
			target, List.of(source, target, refusing), id -> true, false,
			() -> selectionCleared.set(true));

		assertEquals(ClusterBindingService.BindResult.PARTICIPANT_REJECTED, result);
		assertEquals(1, source.prepareCount);
		assertEquals(1, target.prepareCount);
		assertEquals(1, refusing.prepareCount);
		assertEquals(0, source.commitCount);
		assertEquals(0, target.commitCount);
		assertEquals(0, refusing.commitCount);
		assertFalse(selectionCleared.get());
	}

	@Test
	void throwingPreparePreventsEveryCommit() {
		FakeMember throwing = replica(SOURCE_ID, UUID.randomUUID(), 1, List.of());
		throwing.throwDuringPrepare = true;

		assertEquals(ClusterBindingService.BindResult.PARTICIPANT_REJECTED,
			ClusterBindingService.bind(source, target, List.of(source, target, throwing),
				id -> true, false, () -> {}));
		assertEquals(0, source.commitCount);
		assertEquals(0, target.commitCount);
		assertEquals(0, throwing.commitCount);
	}

	@Test
	void successfulBindUsesAuthorityStateInsteadOfReintroducingStaleReplicaBindings() {
		FakeMember participant = replica(SOURCE_ID,
			UUID.fromString("00000000-0000-0000-0000-000000000040"), 1, List.of(
			new LogisticsBinding(OPEN, "replacement"),
			new LogisticsBinding(PARTICIPANT_OLD, "participant")));
		AtomicBoolean selectionCleared = new AtomicBoolean();

		ClusterBindingService.BindResult result = ClusterBindingService.bind(source,
			target, List.of(participant, source, target), id -> true, false,
			() -> selectionCleared.set(true));

		List<LogisticsBinding> expected = List.of(
			new LogisticsBinding(OPEN, "open"),
			new LogisticsBinding(LOCKED, "locked"));
		assertEquals(ClusterBindingService.BindResult.OK, result);
		assertEquals(expected, source.binding.logisticsBindings());
		assertSame(source.binding, target.binding);
		assertSame(source.binding, participant.binding);
		assertEquals(5, source.binding.revision());
		assertEquals(new ClusterAuthority(ClusterMemberType.PANEL, SOURCE_ID),
			source.binding.authority());
		assertEquals(1, source.commitCount);
		assertEquals(1, target.commitCount);
		assertEquals(1, participant.commitCount);
		assertTrue(selectionCleared.get());
	}

	@Test
	void computerBecomesAuthorityWhenBoundAndAllAuthoritiesAreLoaded() {
		UUID computerId = UUID.randomUUID();
		FakeMember computer = member(computerId, UUID.randomUUID(), 6,
			ClusterMemberType.COMPUTER_COORDINATOR,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, computerId),
			address(Level.OVERWORLD), List.of(new LogisticsBinding(LOCKED, "locked")));

		assertEquals(ClusterBindingService.BindResult.OK,
			ClusterBindingService.bind(source, computer, List.of(source, computer),
				id -> true, false, () -> {}));
		assertEquals(new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, computerId),
			source.binding.authority());
	}

	@Test
	void unionOverflowAtThirtyThreeMutatesNobody() {
		source.binding = new ClusterBinding(CLUSTER, 2, source.binding.authority(),
			bindings(0, 16));
		target.binding = new ClusterBinding(target.binding.clusterId(), 4,
			target.binding.authority(), bindings(16, 17));

		ClusterBindingService.BindResult result = ClusterBindingService.bind(source,
			target, List.of(source, target), id -> true, false, () -> {});

		assertEquals(ClusterBindingService.BindResult.TOO_MANY_BINDINGS, result);
		assertEquals(0, source.commitCount);
		assertEquals(0, target.commitCount);
	}

	@Test
	void publicReplacementAppliesExactOrderClusterWide() {
		FakeMember replica = replica(SOURCE_ID, UUID.randomUUID(), 1,
			source.logisticsBindings());
		List<LogisticsBinding> replacement = List.of(
			new LogisticsBinding(PARTICIPANT_OLD, "first"),
			new LogisticsBinding(OPEN, "second"));

		ClusterBindingService.BindResult result = ClusterBindingService.replaceBindings(
			source, replacement, List.of(source, replica), id -> true, false);

		assertEquals(ClusterBindingService.BindResult.OK, result);
		assertEquals(replacement, source.logisticsBindings());
		assertSame(source.binding, replica.binding);
		assertEquals(3, source.binding.revision());
	}

	@Test
	void replacementMayRemoveTheFinalUnusedBinding() {
		FakeMember replica = replica(SOURCE_ID, UUID.randomUUID(), 1,
			source.logisticsBindings());

		ClusterBindingService.BindResult result = ClusterBindingService.replaceBindings(
			source, List.of(), List.of(source, replica), id -> true, false);

		assertEquals(ClusterBindingService.BindResult.OK, result);
		assertTrue(source.logisticsBindings().isEmpty());
		assertSame(source.binding, replica.binding);
		assertEquals(3, source.binding.revision());
	}

	@Test
	void staleReplicaAdoptsLoadedAuthorityButCannotOverwriteIt() {
		FakeMember stale = replica(SOURCE_ID, UUID.randomUUID(), 1,
			List.of(new LogisticsBinding(PARTICIPANT_OLD, "stale")));

		assertEquals(ClusterBindingService.ReconcileResult.UPDATED,
			ClusterBindingService.reconcileLoaded(stale, List.of(source, stale)));
		assertSame(source.binding, stale.binding);
		assertEquals(0, source.commitCount);
		assertEquals(1, stale.commitCount);
	}

	@Test
	void replicaNewerThanLoadedAuthorityReportsConflictWithoutMutation() {
		FakeMember impossibleNewer = replica(SOURCE_ID, UUID.randomUUID(), 9,
			List.of(new LogisticsBinding(PARTICIPANT_OLD, "newer")));

		assertEquals(ClusterBindingService.ReconcileResult.CONFLICT,
			ClusterBindingService.reconcileLoaded(impossibleNewer,
				List.of(source, impossibleNewer)));
		assertEquals(0, source.commitCount);
		assertEquals(0, impossibleNewer.commitCount);
	}

	@Test
	void replicaThatMissedAuthorityTransferFollowsLoadedAuthorityChain() {
		UUID computerId = UUID.randomUUID();
		ClusterAuthority computerAuthority = new ClusterAuthority(
			ClusterMemberType.COMPUTER_COORDINATOR, computerId);
		ClusterBinding transferred = new ClusterBinding(CLUSTER, 5,
			computerAuthority, source.logisticsBindings());
		source.binding = transferred;
		FakeMember computer = member(computerId, CLUSTER, 5,
			ClusterMemberType.COMPUTER_COORDINATOR, computerAuthority,
			address(Level.OVERWORLD), source.logisticsBindings());
		FakeMember offlineReplica = replica(SOURCE_ID, UUID.randomUUID(), 1,
			List.of(new LogisticsBinding(PARTICIPANT_OLD, "old")));

		assertEquals(ClusterBindingService.ReconcileResult.UPDATED,
			ClusterBindingService.reconcileLoaded(offlineReplica,
				List.of(source, computer, offlineReplica)));
		assertEquals(transferred, offlineReplica.binding);
		assertEquals(1, offlineReplica.commitCount);
		assertEquals(0, source.commitCount);
		assertEquals(0, computer.commitCount);
	}

	@Test
	void wrongTypedSelectionRootIsCleared() {
		CompoundTag persistentData = new CompoundTag();
		persistentData.putString(SELECTION_KEY, "not a compound");

		assertTrue(ClusterBindingSelection.decode(persistentData).isEmpty());
		assertFalse(persistentData.contains(SELECTION_KEY));
	}

	@Test
	void malformedSelectionFieldsAreClearedWithoutDefaults() {
		List<Consumer<CompoundTag>> corruptions = List.of(
			tag -> tag.remove("MemberId"),
			tag -> tag.putString("MemberId", "wrong type"),
			tag -> tag.remove("Expires"),
			tag -> tag.putInt("Expires", 200),
			tag -> tag.remove("Dimension"),
			tag -> tag.putLong("Dimension", 0),
			tag -> tag.putString("Dimension", ""),
			tag -> tag.remove("Pos"),
			tag -> tag.putString("Pos", "wrong type"),
			tag -> tag.putString("SubLevel", "wrong type"));

		for (Consumer<CompoundTag> corruption : corruptions) {
			CompoundTag persistentData = validPersistentSelection();
			corruption.accept(persistentData.getCompound(SELECTION_KEY));
			assertTrue(ClusterBindingSelection.decode(persistentData).isEmpty());
			assertFalse(persistentData.contains(SELECTION_KEY));
		}
	}

	@Test
	void okIsTheOnlySuccessfulResult() {
		assertTrue(ClusterBindingService.BindResult.OK.succeeded());
		assertFalse(ClusterBindingService.BindResult.PERMISSION.succeeded());
	}

	private static CompoundTag validPersistentSelection() {
		CompoundTag selection = address(Level.OVERWORLD).save();
		selection.putUUID("MemberId", UUID.randomUUID());
		selection.putLong("Expires", 200);
		CompoundTag persistentData = new CompoundTag();
		persistentData.put(SELECTION_KEY, selection);
		return persistentData;
	}

	private static FakeMember replica(UUID authorityId, UUID memberId, long revision,
		List<LogisticsBinding> bindings) {
		return member(memberId, CLUSTER, revision, ClusterMemberType.PANEL,
			new ClusterAuthority(ClusterMemberType.PANEL, authorityId),
			address(Level.OVERWORLD), bindings);
	}

	private static FakeMember member(UUID memberId, UUID clusterId, long revision,
		ClusterMemberType type, ClusterAuthority authority, SpaceAddress address,
		List<LogisticsBinding> bindings) {
		return new FakeMember(memberId,
			new ClusterBinding(clusterId, revision, authority, bindings), type, address);
	}

	private static SpaceAddress address(ResourceKey<Level> dimension) {
		return new SpaceAddress(dimension, null, BlockPos.ZERO);
	}

	private static List<LogisticsBinding> bindings(int start, int count) {
		List<LogisticsBinding> bindings = new ArrayList<>();
		for (int index = start; index < start + count; index++)
			bindings.add(new LogisticsBinding(new UUID(1, index + 1L), "network-" + index));
		return List.copyOf(bindings);
	}

	private static final class FakeMember implements ClusterMember {
		private final UUID memberId;
		private ClusterBinding binding;
		private final ClusterMemberType type;
		private final SpaceAddress address;
		private boolean rebindable = true;
		private ClusterBindingPreparation prepareResult = ClusterBindingPreparation.READY;
		private boolean throwDuringPrepare;
		private int prepareCount;
		private int commitCount;

		private FakeMember(UUID memberId, ClusterBinding binding,
			ClusterMemberType type, SpaceAddress address) {
			this.memberId = memberId;
			this.binding = binding;
			this.type = type;
			this.address = address;
		}

		@Override
		public UUID memberId() {
			return memberId;
		}

		@Override
		public ClusterBinding bindingState() {
			return binding;
		}

		@Override
		public ClusterMemberType memberType() {
			return type;
		}

		@Override
		public SpaceAddress memberAddress() {
			return address;
		}

		@Override
		public boolean canRebind() {
			return rebindable;
		}

		@Override
		public ClusterBindingPreparation prepareClusterBinding(ClusterBinding proposed) {
			prepareCount++;
			if (throwDuringPrepare)
				throw new IllegalStateException("prepare refused");
			return prepareResult;
		}

		@Override
		public void commitClusterBinding(ClusterBinding prepared) {
			commitCount++;
			binding = prepared;
		}
	}
}
