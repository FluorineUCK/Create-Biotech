package com.nobodiiiii.createbiotech.content.factorycluster;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

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
			address(Level.OVERWORLD), List.of(new LogisticsBinding(OPEN)));
		UUID targetCluster = UUID.fromString("00000000-0000-0000-0000-000000000030");
		target = member(TARGET_ID, targetCluster, 4, ClusterMemberType.PANEL,
			new ClusterAuthority(ClusterMemberType.PANEL, TARGET_ID),
			address(Level.OVERWORLD), List.of(new LogisticsBinding(LOCKED)));
		targetInNether = member(TARGET_ID, targetCluster, 4, ClusterMemberType.PANEL,
			new ClusterAuthority(ClusterMemberType.PANEL, TARGET_ID),
			address(Level.NETHER), List.of(new LogisticsBinding(LOCKED)));
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
			List.of(new LogisticsBinding(PARTICIPANT_OLD)));
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
			List.of(new LogisticsBinding(PARTICIPANT_OLD)));
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
			new LogisticsBinding(OPEN),
			new LogisticsBinding(PARTICIPANT_OLD)));
		AtomicBoolean selectionCleared = new AtomicBoolean();

		ClusterBindingService.BindResult result = ClusterBindingService.bind(source,
			target, List.of(participant, source, target), id -> true, false,
			() -> selectionCleared.set(true));

		List<LogisticsBinding> expected = List.of(
			new LogisticsBinding(OPEN),
			new LogisticsBinding(LOCKED));
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
			address(Level.OVERWORLD), List.of(new LogisticsBinding(LOCKED)));

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
			new LogisticsBinding(PARTICIPANT_OLD),
			new LogisticsBinding(OPEN));

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
			List.of(new LogisticsBinding(PARTICIPANT_OLD)));

		assertEquals(ClusterBindingService.ReconcileResult.UPDATED,
			ClusterBindingService.reconcileLoaded(stale, List.of(source, stale)));
		assertSame(source.binding, stale.binding);
		assertEquals(0, source.commitCount);
		assertEquals(1, stale.commitCount);
	}

	@Test
	void pureSnapshotAccessFailsClosedForAReconcileableStaleReplicaWithoutMutation() {
		FakeMember stale = replica(SOURCE_ID, UUID.randomUUID(), 1,
			List.of(new LogisticsBinding(PARTICIPANT_OLD)));

		assertEquals(ClusterBindingService.BindingAccess.CONFLICT,
			ClusterBindingService.snapshotBindingAccess(stale, List.of(source, stale)));
		assertEquals(0, source.prepareCount);
		assertEquals(0, source.commitCount);
		assertEquals(0, stale.prepareCount);
		assertEquals(0, stale.commitCount);
		assertEquals(ClusterBindingService.ReconcileResult.UPDATED,
			ClusterBindingService.reconcileLoaded(stale, List.of(source, stale)));
		assertEquals(ClusterBindingService.BindingAccess.READY,
			ClusterBindingService.snapshotBindingAccess(stale, List.of(source, stale)));
	}

	@Test
	void pureSnapshotAccessRequiresExactStableIdentitiesAndCurrentAuthorityState() {
		FakeMember exactReplica = replica(SOURCE_ID, UUID.randomUUID(),
			source.binding.revision(), source.logisticsBindings());
		exactReplica.binding = source.binding;
		FakeMember duplicateAuthority = member(SOURCE_ID, CLUSTER,
			source.binding.revision(), ClusterMemberType.PANEL, source.binding.authority(),
			address(Level.OVERWORLD), source.logisticsBindings());

		assertEquals(ClusterBindingService.BindingAccess.READY,
			ClusterBindingService.snapshotBindingAccess(exactReplica,
				List.of(source, exactReplica)));
		assertEquals(ClusterBindingService.BindingAccess.CONFLICT,
			ClusterBindingService.snapshotBindingAccess(exactReplica,
				List.of(source, duplicateAuthority, exactReplica)));
		assertEquals(0, source.prepareCount);
		assertEquals(0, source.commitCount);
		assertEquals(0, exactReplica.prepareCount);
		assertEquals(0, exactReplica.commitCount);
	}

	@Test
	void replicaNewerThanLoadedAuthorityReportsConflictWithoutMutation() {
		FakeMember impossibleNewer = replica(SOURCE_ID, UUID.randomUUID(), 9,
			List.of(new LogisticsBinding(PARTICIPANT_OLD)));

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
			List.of(new LogisticsBinding(PARTICIPANT_OLD)));

		assertEquals(ClusterBindingService.ReconcileResult.UPDATED,
			ClusterBindingService.reconcileLoaded(offlineReplica,
				List.of(source, computer, offlineReplica)));
		assertEquals(transferred, offlineReplica.binding);
		assertEquals(1, offlineReplica.commitCount);
		assertEquals(0, source.commitCount);
		assertEquals(0, computer.commitCount);
	}

	@Test
	void targetReplicaOfflineDuringMergeFollowsAuthorityAcrossClusterIds() {
		FakeMember offlineTargetReplica = member(UUID.randomUUID(),
			target.binding.clusterId(), target.binding.revision(), ClusterMemberType.PANEL,
			target.binding.authority(), address(Level.OVERWORLD), target.logisticsBindings());

		assertEquals(ClusterBindingService.BindResult.OK,
			ClusterBindingService.bind(source, target, List.of(source, target),
				id -> true, false, () -> {}));
		ClusterBinding merged = source.binding;
		assertEquals(merged, target.binding);

		assertEquals(ClusterBindingService.ReconcileResult.UPDATED,
			ClusterBindingService.reconcileLoaded(offlineTargetReplica,
				List.of(offlineTargetReplica, target, source)));
		assertSame(merged, offlineTargetReplica.binding);
		assertEquals(merged.clusterId(), offlineTargetReplica.clusterId());
		assertEquals(merged.revision(), offlineTargetReplica.binding.revision());
		assertEquals(merged.authority(), offlineTargetReplica.binding.authority());
		assertEquals(merged.logisticsBindings(), offlineTargetReplica.logisticsBindings());
		assertEquals(1, offlineTargetReplica.commitCount);
	}

	@Test
	void crossClusterReconciliationRefusesMissingAuthorityRedirect() {
		UUID missingAuthorityId = UUID.randomUUID();
		FakeMember offlineReplica = member(UUID.randomUUID(), UUID.randomUUID(), 4,
			ClusterMemberType.PANEL,
			new ClusterAuthority(ClusterMemberType.PANEL, missingAuthorityId),
			address(Level.OVERWORLD), source.logisticsBindings());

		assertEquals(ClusterBindingService.ReconcileResult.AUTHORITY_OFFLINE,
			ClusterBindingService.reconcileLoaded(offlineReplica,
				List.of(offlineReplica, source)));
		assertEquals(0, offlineReplica.commitCount);
	}

	@Test
	void crossClusterReconciliationRefusesAuthorityRedirectCycle() {
		UUID oldCluster = UUID.randomUUID();
		UUID mergedCluster = UUID.randomUUID();
		UUID firstAuthorityId = UUID.randomUUID();
		UUID secondAuthorityId = UUID.randomUUID();
		ClusterAuthority firstAuthority = new ClusterAuthority(ClusterMemberType.PANEL,
			firstAuthorityId);
		ClusterAuthority secondAuthority = new ClusterAuthority(ClusterMemberType.PANEL,
			secondAuthorityId);
		FakeMember offlineReplica = member(UUID.randomUUID(), oldCluster, 1,
			ClusterMemberType.PANEL, firstAuthority, address(Level.OVERWORLD),
			source.logisticsBindings());
		FakeMember first = member(firstAuthorityId, mergedCluster, 2,
			ClusterMemberType.PANEL, secondAuthority, address(Level.OVERWORLD),
			source.logisticsBindings());
		FakeMember second = member(secondAuthorityId, mergedCluster, 3,
			ClusterMemberType.PANEL, firstAuthority, address(Level.OVERWORLD),
			source.logisticsBindings());

		assertEquals(ClusterBindingService.ReconcileResult.CONFLICT,
			ClusterBindingService.reconcileLoaded(offlineReplica,
				List.of(offlineReplica, first, second)));
		assertEquals(0, offlineReplica.commitCount);
	}

	@Test
	void crossClusterReconciliationRefusesDuplicateLoadingMemberIdentity() {
		FakeMember offlineReplica = replica(SOURCE_ID, TARGET_ID, 1,
			List.of(new LogisticsBinding(PARTICIPANT_OLD)));
		FakeMember duplicate = member(TARGET_ID, UUID.randomUUID(), 2,
			ClusterMemberType.PANEL,
			new ClusterAuthority(ClusterMemberType.PANEL, TARGET_ID),
			address(Level.OVERWORLD), source.logisticsBindings());

		assertEquals(ClusterBindingService.ReconcileResult.CONFLICT,
			ClusterBindingService.reconcileLoaded(offlineReplica,
				List.of(offlineReplica, duplicate, source)));
		assertEquals(0, offlineReplica.commitCount);
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

	@Test
	void formedPanelLogisticsReplacementCommitsTheExactWholeCluster() {
		UUID coordinatorId = UUID.randomUUID();
		ClusterAuthority authorityId = new ClusterAuthority(
			ClusterMemberType.COMPUTER_COORDINATOR, coordinatorId);
		List<LogisticsBinding> oldBindings = bindings(0, 1);
		FakeMember coordinator = member(coordinatorId, CLUSTER, 7,
			ClusterMemberType.COMPUTER_COORDINATOR, authorityId,
			address(Level.OVERWORLD), oldBindings);
		FakeMember panel = member(UUID.randomUUID(), CLUSTER, 7,
			ClusterMemberType.PANEL, authorityId, address(Level.OVERWORLD), oldBindings);
		FakeMember pattern = member(UUID.randomUUID(), CLUSTER, 7,
			ClusterMemberType.PATTERN_CORE, authorityId,
			address(Level.OVERWORLD), oldBindings);
		List<LogisticsBinding> replacement = bindings(10, 1);

		assertEquals(ClusterBindingService.BindResult.OK,
			ClusterBindingService.replaceFormedBindings(panel, replacement,
				List.of(panel, coordinator, pattern), id -> true, false, true));
		for (FakeMember member : List.of(panel, coordinator, pattern)) {
			assertEquals(replacement, member.logisticsBindings());
			assertEquals(8, member.bindingState().revision());
			assertEquals(authorityId, member.bindingState().authority());
			assertEquals(1, member.prepareCount);
			assertEquals(1, member.commitCount);
		}
	}

	@Test
	void formedPanelReplacementFailsClosedBeforeAnyCommitOnPermissionOrReplicaRefusal() {
		UUID coordinatorId = UUID.randomUUID();
		ClusterAuthority authorityId = new ClusterAuthority(
			ClusterMemberType.COMPUTER_COORDINATOR, coordinatorId);
		List<LogisticsBinding> oldBindings = bindings(0, 1);
		FakeMember coordinator = member(coordinatorId, CLUSTER, 7,
			ClusterMemberType.COMPUTER_COORDINATOR, authorityId,
			address(Level.OVERWORLD), oldBindings);
		FakeMember panel = member(UUID.randomUUID(), CLUSTER, 7,
			ClusterMemberType.PANEL, authorityId, address(Level.OVERWORLD), oldBindings);
		FakeMember refusing = member(UUID.randomUUID(), CLUSTER, 7,
			ClusterMemberType.PATTERN_CORE, authorityId,
			address(Level.OVERWORLD), oldBindings);
		List<LogisticsBinding> replacement = bindings(10, 1);

		assertEquals(ClusterBindingService.BindResult.PERMISSION,
			ClusterBindingService.replaceFormedBindings(panel, replacement,
				List.of(panel, coordinator, refusing), id -> false, false, true));
		assertEquals(0, panel.commitCount);
		refusing.prepareResult = ClusterBindingPreparation.ACTIVE;
		assertEquals(ClusterBindingService.BindResult.ACTIVE,
			ClusterBindingService.replaceFormedBindings(panel, replacement,
				List.of(panel, coordinator, refusing), id -> true, false, true));
		assertEquals(0, panel.commitCount);
		assertEquals(0, coordinator.commitCount);
		assertEquals(0, refusing.commitCount);
	}

	@Test
	void attachedCoreCanConfigureTheSameFormedAuthorityWithoutInventingASeparateNetworkList() {
		UUID coordinatorId = UUID.randomUUID();
		var authorityId = new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, coordinatorId);
		var old = bindings(0, 1);
		FakeMember coordinator = member(coordinatorId, CLUSTER, 7, ClusterMemberType.COMPUTER_COORDINATOR,
			authorityId, address(Level.OVERWORLD), old);
		FakeMember core = member(UUID.randomUUID(), CLUSTER, 7, ClusterMemberType.PATTERN_CORE,
			authorityId, address(Level.OVERWORLD), old);
		var appended = bindings(0, 2);
		assertEquals(ClusterBindingService.BindResult.OK, ClusterBindingService.replaceFormedBindings(core,
			appended, List.of(coordinator, core), id -> true, false, true));
		assertEquals(appended, coordinator.logisticsBindings());
		assertEquals(coordinator.bindingState(), core.bindingState());
		assertEquals(8, core.bindingState().revision());
	}

	@Test
	void ordinaryBindingPreparesEveryExactParticipantBeforeCommitAndPreservesTheFirstRefusal() {
		for (boolean refuse : List.of(false, true)) {
			setUp();
			var follower = replica(SOURCE_ID, new UUID(0, 90), 1, List.of());
			var members = List.of(source, target, follower); var original = members.stream().map(FakeMember::bindingState).toList();
			var events = new ArrayList<String>();
			for (int i = 0; i < members.size(); i++) {
				int index = i; var member = members.get(i);
				member.onPrepare = () -> events.add("prepare:" + index);
				member.onCommit = () -> {
					assertSame(source.lastPrepared, member.lastPrepared, "One exact prepared proposal for every replica");
					assertSame(member.lastPrepared, member.binding);
					events.add("commit:" + index);
				};
			}
			if (refuse) { target.prepareResult = ClusterBindingPreparation.CAPACITY; follower.prepareResult = ClusterBindingPreparation.ACTIVE; }
			var result = ClusterBindingService.bind(source, target, List.of(follower, source, target, follower),
				id -> true, false, () -> events.add("clear"));
			assertEquals(refuse ? ClusterBindingService.BindResult.TOO_MANY_BINDINGS : ClusterBindingService.BindResult.OK, result);
			assertEquals(refuse ? List.of("prepare:0", "prepare:1", "prepare:2")
				: List.of("prepare:0", "prepare:1", "prepare:2", "commit:0", "commit:1", "commit:2", "clear"), events);
			if (refuse) assertEquals(original, members.stream().map(FakeMember::bindingState).toList());
		}
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
			bindings.add(new LogisticsBinding(new UUID(1, index + 1L)));
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
		private ClusterBinding lastPrepared;
		private Runnable onPrepare = () -> {}, onCommit = () -> {};

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
			lastPrepared = proposed;
			onPrepare.run();
			if (throwDuringPrepare)
				throw new IllegalStateException("prepare refused");
			return prepareResult;
		}

		@Override
		public void commitClusterBinding(ClusterBinding prepared) {
			commitCount++;
			binding = prepared;
			onCommit.run();
		}
	}
}
