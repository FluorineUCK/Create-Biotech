package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

final class ComputerTopologyController {
	private static final Comparator<UUID> UUID_ORDER = Comparator.naturalOrder();
	private static final Set<TransactionKey> ACTIVE_CLOSES = new HashSet<>();
	private static final Set<TransactionKey> ACTIVE_REFORMS = new HashSet<>();

	private ComputerTopologyController() {}

	interface WorldAccess {
		ComputerStructureScanner.ScanResult scan(BlockPos seed,
			ComputerStructureScanner.Limits limits);
		boolean isLoaded(BlockPos pos);
		boolean isChunkLoaded(ChunkPos chunk);
		boolean sameSpace(BlockPos origin, BlockPos pos);
		@Nullable ComputerBlockEntity loadedComputer(BlockPos pos);
		SpaceAddress address(BlockPos pos);
		UUID newStructureMemberId();
		default ClusterBindingService.BindingAccess bindingAccess(
			ComputerCoordinatorMember member) {
			return ClusterBindingService.BindingAccess.READY;
		}
		default boolean enforceFoundationPublication() { return false; }
		default @Nullable ComputerBlockEntity resolveComputer(SpaceAddress address) {
			return loadedComputer(address.localPos());
		}
	}

	static WorldAccess realWorld(ServerLevel level) {
		return new RealWorldAccess(level);
	}

	static RefreshOutcome refresh(ComputerBlockEntity owner, WorldAccess world,
		ComputerStructureScanner.Limits limits) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(limits, "limits");
		if (!owner.persistenceAvailable() || owner.computerId().isEmpty()) {
			fail(owner, ComputerAvailabilityReason.IDENTITY_CONFLICT);
			return RefreshOutcome.failure(ComputerAvailabilityReason.IDENTITY_CONFLICT);
		}
		ComputerStructureRecord known = owner.currentStructureRecord().orElse(null);
		if (known != null && !chunksLoaded(known.snapshot().containingChunks(), world)) {
			fail(owner, ComputerAvailabilityReason.PARTIAL_UNLOADED);
			return RefreshOutcome.failure(ComputerAvailabilityReason.PARTIAL_UNLOADED);
		}

		ComputerStructureScanner.ScanResult result = world.scan(owner.getBlockPos(), limits);
		if (result.state() != ComputerStructureScanner.State.VALID
			&& result.state() != ComputerStructureScanner.State.VALID_NOT_READY) {
			ComputerAvailabilityReason reason = reasonFor(result.state());
			fail(owner, reason);
			return RefreshOutcome.failure(reason);
		}
		ComputerStructureSnapshot snapshot = result.snapshot();
		if (snapshot == null) {
			fail(owner, ComputerAvailabilityReason.STRUCTURE_INVALID);
			return RefreshOutcome.failure(ComputerAvailabilityReason.STRUCTURE_INVALID);
		}
		ResolvedSnapshot resolved = resolveSnapshot(owner.getBlockPos(), snapshot, world);
		if (resolved.failure() != ResolveFailure.NONE) {
			ComputerAvailabilityReason reason = resolved.failure() == ResolveFailure.PARTIAL
				? ComputerAvailabilityReason.PARTIAL_UNLOADED
				: resolved.failure() == ResolveFailure.IDENTITY
					? ComputerAvailabilityReason.IDENTITY_CONFLICT
					: ComputerAvailabilityReason.STRUCTURE_INVALID;
			fail(owner, reason);
			return new RefreshOutcome(resolved.computers(), snapshot, reason, false);
		}
		UUID provisional = snapshot.nodes().getFirst().computerId();
		if (!owner.computerId().orElseThrow().equals(provisional)
			|| resolved.byId().get(provisional) != owner)
			return new RefreshOutcome(resolved.computers(), snapshot, null, false);

		Reconciliation reconciliation = reconcile(result, resolved);
		if (reconciliation.failureReason() != null) {
			setReasonAndWithdraw(resolved.computers(), reconciliation.failureReason());
			return new RefreshOutcome(resolved.computers(), snapshot,
					reconciliation.failureReason(), false);
		}
		commit(resolved, reconciliation.record(), reconciliation.binding(),
			reconciliation.epoch(), reconciliation.faults(), reconciliation.reason());
		return new RefreshOutcome(resolved.computers(), snapshot, reconciliation.reason(), true);
	}

	record RefreshOutcome(List<ComputerBlockEntity> members,
		@Nullable ComputerStructureSnapshot snapshot,
		@Nullable ComputerAvailabilityReason reason, boolean committed) {
		RefreshOutcome {
			members = List.copyOf(members);
		}

		static RefreshOutcome failure(ComputerAvailabilityReason reason) {
			return new RefreshOutcome(List.of(), null, reason, false);
		}
	}

	static void fanAvailability(List<ComputerBlockEntity> members,
		ComputerAvailabilityReason reason) {
		setReasonAndWithdraw(members, reason);
	}

	private static Reconciliation reconcile(ComputerStructureScanner.ScanResult scan,
		ResolvedSnapshot resolved) {
		List<ComputerBlockEntity> computers = resolved.computers();
		ComputerStructureSnapshot snapshot = scan.snapshot();
		if (snapshot == null) return Reconciliation.failure(ComputerAvailabilityReason.STRUCTURE_INVALID);
		List<ComputerBlockEntity.TopologyState> states = computers.stream()
			.map(ComputerBlockEntity::topologyState).toList();
		if (states.stream().anyMatch(state -> !state.persistenceAvailable()
			|| state.computerId() == null || !state.bindingValid()))
			return Reconciliation.failure(ComputerAvailabilityReason.IDENTITY_CONFLICT);

		Set<UUID> structureIds = new LinkedHashSet<>();
		for (ComputerBlockEntity.TopologyState state : states) {
			if (state.record() != null)
				structureIds.add(state.record().computerStructureMemberId());
			if (state.epoch() != null)
				structureIds.add(state.epoch().computerStructureMemberId());
		}
		if (structureIds.size() > 1)
			return Reconciliation.failure(ComputerAvailabilityReason.IDENTITY_CONFLICT);
		UUID structureId = structureIds.isEmpty()
			? resolved.world().newStructureMemberId() : structureIds.iterator().next();
		if (scan.observedStructureMemberId() != null
			&& !scan.observedStructureMemberId().equals(structureId))
			return Reconciliation.failure(ComputerAvailabilityReason.IDENTITY_CONFLICT);

		ClusterEpoch activeEpoch = null;
		for (ComputerBlockEntity.TopologyState state : states) {
			if (state.epoch() == null) continue;
			if (activeEpoch == null) activeEpoch = state.epoch();
			else if (!activeEpoch.equals(state.epoch()))
				return Reconciliation.failure(ComputerAvailabilityReason.PERSISTENCE_INVALID);
		}
		RecordSelection selection = authoritativeRecord(resolved, activeEpoch);
		if (!selection.valid())
			return Reconciliation.failure(ComputerAvailabilityReason.IDENTITY_CONFLICT);
		ComputerStructureRecord authority = selection.record();
		if (!recordsCompatible(states, authority, structureId))
			return Reconciliation.failure(ComputerAvailabilityReason.IDENTITY_CONFLICT);
		if (activeEpoch != null && !resolved.byId().containsKey(activeEpoch.coordinatorId()))
			return Reconciliation.failure(ComputerAvailabilityReason.COORDINATOR_MISSING);
		UUID bindingCoordinator = activeEpoch != null ? activeEpoch.coordinatorId()
			: authority != null ? authority.coordinatorId()
				: snapshot.nodes().getFirst().computerId();
		ComputerBlockEntity bindingOwner = resolved.byId().get(bindingCoordinator);
		boolean electingAfterDestruction = activeEpoch == null && authority != null
			&& bindingOwner == null;
		ClusterBinding binding = bindingOwner != null ? bindingOwner.bindingState()
			: electingAfterDestruction ? states.getFirst().binding() : null;
		for (ComputerBlockEntity.TopologyState state : states) {
			ClusterBinding replica = state.binding();
			if (electingAfterDestruction && !Objects.equals(binding, replica))
				return Reconciliation.failure(ComputerAvailabilityReason.IDENTITY_CONFLICT);
			boolean freshJoiner = state.record() == null && state.epoch() == null
				&& replica == null;
			if (freshJoiner) continue;
			if (binding == null ? replica != null : replica == null
				|| binding != null && (!binding.clusterId().equals(replica.clusterId())
					|| replica.revision() > binding.revision()
					|| replica.revision() == binding.revision() && !replica.equals(binding)))
				return Reconciliation.failure(ComputerAvailabilityReason.IDENTITY_CONFLICT);
		}
		if (activeEpoch != null && (binding == null
			|| !binding.clusterId().equals(activeEpoch.clusterId())))
			return Reconciliation.failure(ComputerAvailabilityReason.PERSISTENCE_INVALID);

		EnumSet<EpochFault> union = EnumSet.noneOf(EpochFault.class);
		if (activeEpoch != null) {
			for (ComputerBlockEntity.TopologyState state : states) {
				if (state.epoch() != null
					&& state.epoch().epochId().equals(activeEpoch.epochId()))
					union.addAll(state.faults());
			}
		} else if (states.stream().anyMatch(state -> !state.faults().isEmpty())) {
			return Reconciliation.failure(ComputerAvailabilityReason.PERSISTENCE_INVALID);
		}

		UUID desiredCoordinator;
		ClusterEpoch desiredEpoch = activeEpoch;
		ComputerAvailabilityReason reason;
		if (activeEpoch == null) {
			desiredCoordinator = snapshot.nodes().getFirst().computerId();
			if (authority != null && !authority.coordinatorId().equals(desiredCoordinator)) {
				BlockPos seed = resolved.byId().get(desiredCoordinator).getBlockPos();
				ProofFailure proof = proveInactiveReelectionDomain(seed, authority, snapshot,
					resolved.world());
				if (proof == ProofFailure.PARTIAL)
					return Reconciliation.failure(ComputerAvailabilityReason.PARTIAL_UNLOADED);
				if (proof == ProofFailure.SPACE)
					return Reconciliation.failure(ComputerAvailabilityReason.IDENTITY_CONFLICT);
				List<BlockPos> matches = computerIdPositionsInBounds(authority.coordinatorId(),
					List.of(authority.snapshot().bounds(), snapshot.bounds()), resolved.world());
				ComputerStructureNode currentCoordinator = snapshot
					.node(authority.coordinatorId()).orElse(null);
				if (currentCoordinator == null ? !matches.isEmpty()
					: matches.size() != 1
						|| !matches.getFirst().equals(currentCoordinator.address().localPos())
						|| !resolved.world().address(matches.getFirst())
							.equals(currentCoordinator.address()))
					return Reconciliation.failure(ComputerAvailabilityReason.IDENTITY_CONFLICT);
			}
			reason = scan.state() == ComputerStructureScanner.State.VALID_NOT_READY
				? ComputerAvailabilityReason.NOT_READY : ComputerAvailabilityReason.NONE;
		} else {
			desiredCoordinator = activeEpoch.coordinatorId();
			Set<UUID> current = snapshot.computerIds();
			Set<UUID> frozen = new LinkedHashSet<>();
			for (EpochNode node : activeEpoch.nodes()) frozen.add(node.computerId());
			if (!current.contains(activeEpoch.coordinatorId()))
				return Reconciliation.failure(ComputerAvailabilityReason.COORDINATOR_MISSING);
			for (EpochNode node : activeEpoch.nodes()) {
				Optional<ComputerStructureNode> currentNode = snapshot.node(node.computerId());
				if (currentNode.isPresent() && (currentNode.orElseThrow().profile() == null
					|| !node.profile().equals(currentNode.orElseThrow().profile())))
					return Reconciliation.failure(ComputerAvailabilityReason.PERSISTENCE_INVALID);
			}
			boolean frozenMissing = !current.containsAll(frozen);
			if (!frozenMissing) {
				try {
					desiredEpoch = activeEpoch.relocate(snapshot);
				} catch (IllegalArgumentException invalidRelocation) {
					return Reconciliation.failure(ComputerAvailabilityReason.STRUCTURE_INVALID);
				}
				reason = ComputerAvailabilityReason.NONE;
			} else {
				reason = ComputerAvailabilityReason.FROZEN_MEMBER_MISSING;
			}
			if (scan.state() == ComputerStructureScanner.State.VALID_NOT_READY) {
				for (ComputerStructureNode node : snapshot.nodes())
					if (node.profile() == null && frozen.contains(node.computerId()))
						return Reconciliation.failure(ComputerAvailabilityReason.PERSISTENCE_INVALID);
			}
		}

		ComputerStructureRecord desired;
		boolean noExistingRecord = states.stream().allMatch(state -> state.record() == null);
		if (noExistingRecord) {
			desired = new ComputerStructureRecord(structureId, 0, desiredCoordinator, snapshot);
		} else {
			ComputerStructureRecord base = authority;
			if (base == null) base = states.stream().map(ComputerBlockEntity.TopologyState::record)
				.filter(Objects::nonNull).findFirst().orElseThrow();
			if (base.computerStructureMemberId().equals(structureId)
				&& base.coordinatorId().equals(desiredCoordinator)
				&& base.snapshot().equals(snapshot)) desired = base;
			else {
				Optional<ComputerStructureRecord> revised = base.revise(desiredCoordinator, snapshot);
				if (revised.isEmpty())
					return Reconciliation.failure(ComputerAvailabilityReason.PERSISTENCE_INVALID);
				desired = revised.orElseThrow();
			}
		}
		return new Reconciliation(desired, binding, desiredEpoch, Set.copyOf(union), reason, null);
	}

	private static RecordSelection authoritativeRecord(ResolvedSnapshot resolved,
		@Nullable ClusterEpoch activeEpoch) {
		if (activeEpoch != null) {
			ComputerBlockEntity coordinator = resolved.byId().get(activeEpoch.coordinatorId());
			if (coordinator == null) return RecordSelection.valid(null);
			ComputerStructureRecord record = coordinator.currentStructureRecord().orElse(null);
			return record == null ? RecordSelection.invalid() : RecordSelection.valid(record);
		}
		Set<UUID> coordinators = new LinkedHashSet<>();
		for (ComputerBlockEntity computer : resolved.computers())
			computer.currentStructureRecord().ifPresent(record -> coordinators.add(record.coordinatorId()));
		if (coordinators.size() > 1) return RecordSelection.invalid();
		if (coordinators.isEmpty()) return RecordSelection.valid(null);
		UUID coordinatorId = coordinators.iterator().next();
		ComputerBlockEntity coordinator = resolved.byId().get(coordinatorId);
		if (coordinator != null) return coordinator.currentStructureRecord()
			.map(RecordSelection::valid).orElseGet(RecordSelection::invalid);
		List<ComputerStructureRecord> records = resolved.computers().stream()
			.map(ComputerBlockEntity::currentStructureRecord).flatMap(Optional::stream).toList();
		if (records.isEmpty()) return RecordSelection.valid(null);
		ComputerStructureRecord first = records.getFirst();
		return records.stream().allMatch(first::equals)
			? RecordSelection.valid(first) : RecordSelection.invalid();
	}

	private static boolean recordsCompatible(List<ComputerBlockEntity.TopologyState> states,
		@Nullable ComputerStructureRecord authority, UUID structureId) {
		for (ComputerBlockEntity.TopologyState state : states) {
			ComputerStructureRecord record = state.record();
			if (record == null) continue;
			if (!record.computerStructureMemberId().equals(structureId)) return false;
			if (authority == null) continue;
			if (record.revision() > authority.revision()) return false;
			if (record.revision() == authority.revision()
				&& (!record.coordinatorId().equals(authority.coordinatorId())
					|| !record.snapshot().equals(authority.snapshot()))) return false;
		}
		return true;
	}

	private static void commit(ResolvedSnapshot resolved, ComputerStructureRecord record,
		@Nullable ClusterBinding binding, @Nullable ClusterEpoch epoch, Set<EpochFault> faults,
		ComputerAvailabilityReason reason) {
		for (int i = 0; i < resolved.computers().size(); i++) {
			ComputerBlockEntity computer = resolved.computers().get(i);
			if (!computer.topologyMatches(resolved.states().get(i))) return;
		}
		boolean publish = publicationReady(resolved, record, epoch, faults, reason);
		ComputerBlockEntity elected = resolved.byId().get(record.coordinatorId());
		for (ComputerBlockEntity computer : resolved.computers())
			if (computer.isCoordinatorMemberPublished()
				&& (computer != elected || !publish)) computer.withdrawCoordinatorMember();
		for (int i = 0; i < resolved.computers().size(); i++) {
			ComputerBlockEntity computer = resolved.computers().get(i);
			ComputerBlockEntity.TopologyState previous = resolved.states().get(i);
			ClusterBinding stagedBinding = epoch != null || previous.record() == null
				? binding : previous.binding();
			computer.stageTopologyState(record, stagedBinding, true, epoch, faults);
		}
		for (ComputerBlockEntity computer : resolved.computers()) {
			computer.setAvailabilityReason(reason);
			computer.publishTopologyChange();
		}
		if (publish && elected != null) {
			elected.publishCoordinatorMember(resolved.computers(), record.snapshot().computerIds());
			updateFoundationAccess(resolved, elected, binding);
		}
	}

	private static void updateFoundationAccess(ResolvedSnapshot resolved,
		ComputerBlockEntity elected, @Nullable ClusterBinding binding) {
		ClusterBindingService.BindingAccess access =
			resolved.world().bindingAccess(elected.coordinatorMember);
		boolean coherent = elected.coordinatorMember.updateBindingAccess(access);
		if (binding == null || access == ClusterBindingService.BindingAccess.READY && coherent)
			return;
		ComputerAvailabilityReason unavailable = access
			== ClusterBindingService.BindingAccess.CONFLICT
			|| access == ClusterBindingService.BindingAccess.READY && !coherent
				? ComputerAvailabilityReason.IDENTITY_CONFLICT
				: ComputerAvailabilityReason.AUTHORITY_OFFLINE;
		setReason(resolved.computers(), unavailable);
	}

	static ComputerBlockEntity.EpochStartResult startEpoch(ComputerBlockEntity caller,
		UUID clusterId, WorldAccess world, ComputerStructureScanner.Limits limits) {
		Objects.requireNonNull(clusterId, "clusterId");
		ComputerBlockEntity.TopologyState callerState = caller.topologyState();
		if (callerState.epoch() != null) return ComputerBlockEntity.EpochStartResult.ALREADY_ACTIVE;
		if (!callerState.faults().isEmpty())
			return ComputerBlockEntity.EpochStartResult.REQUIRES_REFORM;
		if (caller.availabilityReason() == ComputerAvailabilityReason.NOT_READY)
			return ComputerBlockEntity.EpochStartResult.NOT_READY;
		ComputerStructureRecord record = callerState.record();
		if (!callerState.persistenceAvailable() || callerState.computerId() == null || record == null)
			return ComputerBlockEntity.EpochStartResult.NOT_READY;
		if (!record.coordinatorId().equals(callerState.computerId()) || !caller.coordinatorOnline())
			return ComputerBlockEntity.EpochStartResult.NOT_COORDINATOR;
		if (!callerState.bindingValid() || callerState.binding() == null
			|| !callerState.binding().clusterId().equals(clusterId))
			return ComputerBlockEntity.EpochStartResult.BINDING_UNAVAILABLE;
		if (!chunksLoaded(record.snapshot().containingChunks(), world))
			return ComputerBlockEntity.EpochStartResult.NOT_READY;
		ComputerStructureScanner.ScanResult scan = world.scan(caller.getBlockPos(), limits);
		if (scan.state() != ComputerStructureScanner.State.VALID || scan.snapshot() == null
			|| !record.computerStructureMemberId().equals(scan.observedStructureMemberId())
			|| !record.snapshot().equals(scan.snapshot()))
			return ComputerBlockEntity.EpochStartResult.NOT_READY;
		ResolvedSnapshot resolved = resolveSnapshot(caller.getBlockPos(), scan.snapshot(), world);
		if (resolved.failure() != ResolveFailure.NONE
			|| resolved.byId().get(callerState.computerId()) != caller
			|| scan.snapshot().nodes().stream().anyMatch(node -> node.profile() == null))
			return ComputerBlockEntity.EpochStartResult.NOT_READY;
		for (ComputerBlockEntity computer : resolved.computers()) {
			ComputerBlockEntity.TopologyState state = computer.topologyState();
			if (!state.persistenceAvailable() || state.record() == null
				|| !state.record().computerStructureMemberId()
					.equals(record.computerStructureMemberId())
				|| state.binding() == null || !state.binding().equals(callerState.binding())
				|| state.epoch() != null || !state.faults().isEmpty())
				return ComputerBlockEntity.EpochStartResult.NOT_READY;
		}
		if (world.enforceFoundationPublication()) {
			if (!caller.isCoordinatorMemberPublished())
				return ComputerBlockEntity.EpochStartResult.BINDING_UNAVAILABLE;
			ClusterBindingService.BindingAccess access = world.bindingAccess(caller.coordinatorMember);
			if (access != ClusterBindingService.BindingAccess.READY
				|| !caller.coordinatorMember.updateBindingAccess(access))
				return ComputerBlockEntity.EpochStartResult.BINDING_UNAVAILABLE;
		}
		ComputerBlockEntity.TopologyState authoritativeState = caller.topologyState();
		ClusterBinding authoritativeBinding = authoritativeState.binding();
		if (!authoritativeState.persistenceAvailable()
			|| authoritativeState.computerId() == null
			|| !authoritativeState.computerId().equals(record.coordinatorId())
			|| !Objects.equals(authoritativeState.record(), record)
			|| !authoritativeState.bindingValid() || authoritativeBinding == null
			|| !authoritativeBinding.clusterId().equals(clusterId)
			|| authoritativeState.epoch() != null || !authoritativeState.faults().isEmpty())
			return ComputerBlockEntity.EpochStartResult.NOT_READY;
		List<ComputerBlockEntity.TopologyState> finalStates = resolved.computers().stream()
			.map(ComputerBlockEntity::topologyState).toList();
		for (ComputerBlockEntity.TopologyState state : finalStates) {
			if (!state.persistenceAvailable() || state.record() == null
				|| !state.record().computerStructureMemberId()
					.equals(record.computerStructureMemberId())
				|| !state.bindingValid() || !Objects.equals(state.binding(), authoritativeBinding)
				|| state.epoch() != null || !state.faults().isEmpty())
				return ComputerBlockEntity.EpochStartResult.NOT_READY;
		}
		ClusterEpoch epoch;
		try {
			epoch = ClusterEpoch.freeze(clusterId, record.computerStructureMemberId(), scan.snapshot());
		} catch (IllegalArgumentException invalid) {
			return ComputerBlockEntity.EpochStartResult.NOT_READY;
		}
		for (int i = 0; i < resolved.computers().size(); i++)
			if (!resolved.computers().get(i).topologyMatches(finalStates.get(i)))
				return ComputerBlockEntity.EpochStartResult.NOT_READY;
		for (ComputerBlockEntity computer : resolved.computers())
			computer.stageTopologyState(record, authoritativeBinding, true, epoch, Set.of());
		for (ComputerBlockEntity computer : resolved.computers()) {
			computer.setAvailabilityReason(ComputerAvailabilityReason.NONE);
			computer.publishTopologyChange();
		}
		return ComputerBlockEntity.EpochStartResult.STARTED;
	}

	static ComputerBlockEntity.FaultLatchResult latchEpochFault(ComputerBlockEntity caller,
		UUID expectedEpochId, EpochFault fault, WorldAccess world) {
		Objects.requireNonNull(expectedEpochId, "expectedEpochId");
		Objects.requireNonNull(fault, "fault");
		ComputerBlockEntity.TopologyState callerState = caller.topologyState();
		if (!callerState.persistenceAvailable() || callerState.computerId() == null
			|| callerState.record() == null)
			return ComputerBlockEntity.FaultLatchResult.IDENTITY_INVALID;
		ClusterEpoch epoch = callerState.epoch();
		if (epoch == null) return ComputerBlockEntity.FaultLatchResult.NO_EPOCH;
		if (!epoch.epochId().equals(expectedEpochId))
			return ComputerBlockEntity.FaultLatchResult.EPOCH_MISMATCH;
		if (epoch.nodes().stream().noneMatch(node -> node.computerId().equals(callerState.computerId())))
			return ComputerBlockEntity.FaultLatchResult.IDENTITY_INVALID;
		EpochNode callerNode = epoch.nodes().stream()
			.filter(node -> node.computerId().equals(callerState.computerId())).findFirst().orElseThrow();
		if (!world.isLoaded(caller.getBlockPos())
			|| !world.sameSpace(caller.getBlockPos(), caller.getBlockPos())
			|| world.loadedComputer(caller.getBlockPos()) != caller
			|| !world.address(caller.getBlockPos()).equals(callerNode.address()))
			return ComputerBlockEntity.FaultLatchResult.IDENTITY_INVALID;

		List<ComputerBlockEntity> replicas = loadedEpochReplicas(caller, epoch,
			callerState.record(), world);
		EnumSet<EpochFault> union = EnumSet.noneOf(EpochFault.class);
		for (ComputerBlockEntity replica : replicas) union.addAll(replica.latchedEpochFaults());
		boolean already = union.contains(fault);
		union.add(fault);
		List<ComputerBlockEntity.TopologyState> states = replicas.stream()
			.map(ComputerBlockEntity::topologyState).toList();
		for (int i = 0; i < replicas.size(); i++)
			if (!replicas.get(i).topologyMatches(states.get(i)))
				return ComputerBlockEntity.FaultLatchResult.IDENTITY_INVALID;
		for (ComputerBlockEntity replica : replicas) {
			ComputerBlockEntity.TopologyState state = replica.topologyState();
			replica.stageTopologyState(state.record(), state.binding(), state.bindingValid(),
				state.epoch(), union);
		}
		for (ComputerBlockEntity replica : replicas)
			if (replica.isCoordinatorMemberPublished()) replica.withdrawCoordinatorMember();
		for (ComputerBlockEntity replica : replicas) replica.publishTopologyChange();
		return already ? ComputerBlockEntity.FaultLatchResult.ALREADY_LATCHED
			: ComputerBlockEntity.FaultLatchResult.LATCHED;
	}

	static ComputerBlockEntity.EpochCloseResult closeIdleEpoch(ComputerBlockEntity caller,
		UUID expectedEpochId, EpochQuiescence quiescence, WorldAccess world,
		ComputerStructureScanner.Limits limits) {
		Objects.requireNonNull(expectedEpochId, "expectedEpochId");
		Objects.requireNonNull(quiescence, "quiescence");
		ComputerBlockEntity.TopologyState callerState = caller.topologyState();
		ClusterEpoch epoch = callerState.epoch();
		if (epoch == null) return ComputerBlockEntity.EpochCloseResult.NO_EPOCH;
		if (!epoch.epochId().equals(expectedEpochId))
			return ComputerBlockEntity.EpochCloseResult.EPOCH_MISMATCH;
		if (callerState.faults().contains(EpochFault.WIDTH)
			|| callerState.faults().contains(EpochFault.DEPTH))
			return ComputerBlockEntity.EpochCloseResult.REQUIRES_REFORM;
		ComputerStructureRecord record = callerState.record();
		if (!callerState.persistenceAvailable() || record == null
			|| callerState.computerId() == null)
			return ComputerBlockEntity.EpochCloseResult.IDENTITY_CONFLICT;
		if (!exactLoadedCaller(caller, callerState, record, world))
			return ComputerBlockEntity.EpochCloseResult.IDENTITY_CONFLICT;
		TransactionKey key = new TransactionKey(record.computerStructureMemberId(), expectedEpochId);
		synchronized (ACTIVE_CLOSES) {
			if (!ACTIVE_CLOSES.add(key))
				return ComputerBlockEntity.EpochCloseResult.RUNTIME_NOT_QUIESCENT;
		}
		try {
			ProofFailure proof = proveOldDomain(caller.getBlockPos(), record, epoch, world);
			if (proof == ProofFailure.PARTIAL)
				return ComputerBlockEntity.EpochCloseResult.PARTIAL_UNLOADED;
			if (proof == ProofFailure.SPACE)
				return ComputerBlockEntity.EpochCloseResult.SPACE_UNCERTAIN;
			ComputerStructureScanner.ScanResult scan = world.scan(caller.getBlockPos(), limits);
			ComputerBlockEntity.EpochCloseResult scanFailure = closeScanFailure(scan);
			if (scanFailure != null) return scanFailure;
			ComputerStructureSnapshot snapshot = scan.snapshot();
			if (snapshot == null) return ComputerBlockEntity.EpochCloseResult.STRUCTURE_INVALID;
			if (!record.computerStructureMemberId().equals(scan.observedStructureMemberId()))
				return ComputerBlockEntity.EpochCloseResult.IDENTITY_CONFLICT;
			ProofFailure currentProof = proveCurrentDomain(caller.getBlockPos(), snapshot, world);
			if (currentProof == ProofFailure.PARTIAL)
				return ComputerBlockEntity.EpochCloseResult.PARTIAL_UNLOADED;
			if (currentProof == ProofFailure.SPACE)
				return ComputerBlockEntity.EpochCloseResult.SPACE_UNCERTAIN;
			ResolvedSnapshot resolved = resolveSnapshot(caller.getBlockPos(), snapshot, world);
			if (resolved.failure() == ResolveFailure.PARTIAL)
				return ComputerBlockEntity.EpochCloseResult.PARTIAL_UNLOADED;
			if (resolved.failure() == ResolveFailure.SPACE)
				return ComputerBlockEntity.EpochCloseResult.SPACE_UNCERTAIN;
			if (resolved.failure() != ResolveFailure.NONE)
				return ComputerBlockEntity.EpochCloseResult.IDENTITY_CONFLICT;
			if (resolved.byId().get(callerState.computerId()) != caller)
				return ComputerBlockEntity.EpochCloseResult.IDENTITY_CONFLICT;
			TransactionAuthority authority = closeAuthority(resolved, snapshot,
				record.computerStructureMemberId(), expectedEpochId);
			if (authority == null)
				return ComputerBlockEntity.EpochCloseResult.IDENTITY_CONFLICT;
			ClusterEpoch authoritativeEpoch = authority.epoch();
			ProofDomain proofDomain = captureProofDomain(caller.getBlockPos(),
				List.of(record, authority.record()), authoritativeEpoch, snapshot, world);
			if (proofDomain.failure() == ProofFailure.PARTIAL)
				return ComputerBlockEntity.EpochCloseResult.PARTIAL_UNLOADED;
			if (proofDomain.failure() == ProofFailure.SPACE)
				return ComputerBlockEntity.EpochCloseResult.SPACE_UNCERTAIN;
			List<ComputerBlockEntity.TopologyState> replicaStates = relevantReplicaStates(
				proofDomain, record.computerStructureMemberId(), expectedEpochId,
				snapshot.computerIds());
			if (!transactionReplicasMatch(replicaStates, authoritativeEpoch,
				authority.record()))
				return ComputerBlockEntity.EpochCloseResult.IDENTITY_CONFLICT;
			Set<UUID> frozen = new LinkedHashSet<>();
			for (EpochNode node : authoritativeEpoch.nodes()) {
				frozen.add(node.computerId());
				ComputerStructureNode current = snapshot.node(node.computerId()).orElse(null);
				if (current == null || !current.address().equals(node.address()))
					return ComputerBlockEntity.EpochCloseResult.FROZEN_MEMBER_MISSING;
				if (proofComputerCount(proofDomain, node.computerId()) != 1)
					return ComputerBlockEntity.EpochCloseResult.IDENTITY_CONFLICT;
				if (current.profile() == null || !current.profile().equals(node.profile()))
					return ComputerBlockEntity.EpochCloseResult.PROFILE_NOT_READY;
			}
			if (scan.state() == ComputerStructureScanner.State.VALID_NOT_READY)
				for (ComputerStructureNode node : snapshot.nodes())
					if (node.profile() == null && frozen.contains(node.computerId()))
						return ComputerBlockEntity.EpochCloseResult.PROFILE_NOT_READY;
			EnumSet<EpochFault> union = unionFaults(replicaStates, authoritativeEpoch);
			if (union.contains(EpochFault.WIDTH) || union.contains(EpochFault.DEPTH))
				return ComputerBlockEntity.EpochCloseResult.REQUIRES_REFORM;
			if (!quiescence.allRootsStopped())
				return ComputerBlockEntity.EpochCloseResult.ROOTS_REMAIN;
			for (UUID id : sortedUnion(frozen, snapshot.computerIds()))
				if (!quiescence.nodeIdle(id) || !quiescence.nodeRootFree(id)
					|| !quiescence.nodeMailboxEmpty(id))
					return ComputerBlockEntity.EpochCloseResult.RUNTIME_NOT_QUIESCENT;

			if (!revalidateProofDomain(proofDomain, caller.getBlockPos(),
				List.of(record, authority.record()), authoritativeEpoch, snapshot, world)
				|| !revalidateResolved(caller.getBlockPos(), snapshot, resolved, caller,
					world, limits, authoritativeEpoch, union))
				return ComputerBlockEntity.EpochCloseResult.IDENTITY_CONFLICT;
			UUID coordinator = snapshot.nodes().getFirst().computerId();
			ComputerStructureRecord updated;
			if (authority.record().coordinatorId().equals(coordinator)
				&& authority.record().snapshot().equals(snapshot)) updated = authority.record();
			else {
				Optional<ComputerStructureRecord> revised = authority.record().revise(coordinator, snapshot);
				if (revised.isEmpty())
					return ComputerBlockEntity.EpochCloseResult.PERSISTENCE_INVALID;
				updated = revised.orElseThrow();
			}
			ComputerAvailabilityReason reason = scan.state()
				== ComputerStructureScanner.State.VALID_NOT_READY
					? ComputerAvailabilityReason.NOT_READY : ComputerAvailabilityReason.NONE;
			boolean publish = reason == ComputerAvailabilityReason.NONE;
			ComputerBlockEntity elected = resolved.byId().get(coordinator);
			for (ComputerBlockEntity computer : resolved.computers())
				if (computer.isCoordinatorMemberPublished()
					&& (computer != elected || !publish)) computer.withdrawCoordinatorMember();
			for (ComputerBlockEntity computer : resolved.computers()) {
				ComputerBlockEntity.TopologyState state = computer.topologyState();
				computer.stageTopologyState(updated, state.binding(), state.bindingValid(), null, Set.of());
			}
			for (ComputerBlockEntity computer : resolved.computers()) {
				computer.setAvailabilityReason(reason);
				computer.publishTopologyChange();
			}
			if (publish && elected != null) {
				elected.publishCoordinatorMember(resolved.computers(), snapshot.computerIds());
				updateFoundationAccess(resolved, elected, elected.bindingState());
			}
			return ComputerBlockEntity.EpochCloseResult.CLOSED;
		} finally {
			synchronized (ACTIVE_CLOSES) { ACTIVE_CLOSES.remove(key); }
		}
	}

	static ComputerBlockEntity.ReformResult stopAllAndReform(ComputerBlockEntity caller,
		UUID expectedEpochId, EpochReformControl control, WorldAccess world,
		ComputerStructureScanner.Limits limits) {
		Objects.requireNonNull(expectedEpochId, "expectedEpochId");
		Objects.requireNonNull(control, "control");
		ComputerBlockEntity.TopologyState callerState = caller.topologyState();
		ClusterEpoch oldEpoch = callerState.epoch();
		if (oldEpoch == null) return ComputerBlockEntity.ReformResult.NO_EPOCH;
		if (!oldEpoch.epochId().equals(expectedEpochId))
			return ComputerBlockEntity.ReformResult.EPOCH_MISMATCH;
		ComputerStructureRecord oldRecord = callerState.record();
		if (!callerState.persistenceAvailable() || oldRecord == null
			|| callerState.computerId() == null)
			return ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT;
		if (!exactLoadedCaller(caller, callerState, oldRecord, world))
			return ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT;
		TransactionKey key = new TransactionKey(oldRecord.computerStructureMemberId(), expectedEpochId);
		synchronized (ACTIVE_REFORMS) {
			if (!ACTIVE_REFORMS.add(key))
				return ComputerBlockEntity.ReformResult.RUNTIME_NOT_QUIESCENT;
		}
		try {
			ProofFailure proof = proveOldDomain(caller.getBlockPos(), oldRecord, oldEpoch, world);
			if (proof == ProofFailure.PARTIAL)
				return ComputerBlockEntity.ReformResult.PARTIAL_UNLOADED;
			if (proof == ProofFailure.SPACE)
				return ComputerBlockEntity.ReformResult.SPACE_UNCERTAIN;
			ComputerStructureScanner.ScanResult scan = world.scan(caller.getBlockPos(), limits);
			ComputerBlockEntity.ReformResult scanFailure = reformScanFailure(scan);
			if (scanFailure != null) return scanFailure;
			ComputerStructureSnapshot snapshot = scan.snapshot();
			if (snapshot == null) return ComputerBlockEntity.ReformResult.STRUCTURE_INVALID;
			if (!oldRecord.computerStructureMemberId().equals(scan.observedStructureMemberId()))
				return ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT;
			ProofFailure currentProof = proveCurrentDomain(caller.getBlockPos(), snapshot, world);
			if (currentProof == ProofFailure.PARTIAL)
				return ComputerBlockEntity.ReformResult.PARTIAL_UNLOADED;
			if (currentProof == ProofFailure.SPACE)
				return ComputerBlockEntity.ReformResult.SPACE_UNCERTAIN;
			ResolvedSnapshot resolved = resolveSnapshot(caller.getBlockPos(), snapshot, world);
			if (resolved.failure() == ResolveFailure.PARTIAL)
				return ComputerBlockEntity.ReformResult.PARTIAL_UNLOADED;
			if (resolved.failure() == ResolveFailure.SPACE)
				return ComputerBlockEntity.ReformResult.SPACE_UNCERTAIN;
			if (resolved.failure() != ResolveFailure.NONE)
				return ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT;
			if (resolved.byId().get(callerState.computerId()) != caller)
				return ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT;
			if (snapshot.nodes().stream().anyMatch(node -> node.profile() == null))
				return ComputerBlockEntity.ReformResult.PROFILE_NOT_READY;
			UUID stagingOwnerId = snapshot.nodes().getFirst().computerId();
			ComputerBlockEntity stagingOwner = resolved.byId().get(stagingOwnerId);
			if (stagingOwner == null)
				return ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT;
			ComputerBlockEntity.TopologyState authorityState = stagingOwner.topologyState();
			ComputerStructureRecord authorityRecord = authorityState.record();
			ClusterEpoch authoritativeEpoch = authorityState.epoch();
			if (!authorityState.persistenceAvailable() || !authorityState.bindingValid()
				|| authorityState.binding() == null || authorityRecord == null
				|| authoritativeEpoch == null
				|| !expectedEpochId.equals(authoritativeEpoch.epochId())
				|| !oldRecord.computerStructureMemberId()
					.equals(authorityRecord.computerStructureMemberId()))
				return ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT;
			if (authorityRecord.revision() == Long.MAX_VALUE)
				return ComputerBlockEntity.ReformResult.PERSISTENCE_INVALID;
			if (!control.stopAllAndClear()) return ComputerBlockEntity.ReformResult.STOP_FAILED;
			if (!control.allRootsStopped()) return ComputerBlockEntity.ReformResult.ROOTS_REMAIN;
			ProofDomain proofDomain = captureProofDomain(caller.getBlockPos(),
				List.of(oldRecord, authorityRecord), authoritativeEpoch, snapshot, world);
			if (proofDomain.failure() == ProofFailure.PARTIAL)
				return ComputerBlockEntity.ReformResult.PARTIAL_UNLOADED;
			if (proofDomain.failure() == ProofFailure.SPACE)
				return ComputerBlockEntity.ReformResult.SPACE_UNCERTAIN;
			List<ComputerBlockEntity.TopologyState> replicaStates = relevantReplicaStates(
				proofDomain, oldRecord.computerStructureMemberId(), expectedEpochId,
				snapshot.computerIds());
			if (!transactionReplicasMatch(replicaStates, authoritativeEpoch, authorityRecord))
				return ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT;
			Set<UUID> current = snapshot.computerIds();
			Set<UUID> relevant = new LinkedHashSet<>(current);
			for (EpochNode node : authoritativeEpoch.nodes()) {
				ComputerStructureNode present = snapshot.node(node.computerId()).orElse(null);
				if (present != null) {
					if (proofComputerCount(proofDomain, node.computerId()) != 1)
						return ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT;
					if (!present.address().equals(node.address()) || present.profile() == null
						|| !present.profile().equals(node.profile()))
						return ComputerBlockEntity.ReformResult.PROFILE_NOT_READY;
					relevant.add(node.computerId());
				} else if (proofComputerCount(proofDomain, node.computerId()) != 0)
					return ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT;
			}
			for (UUID id : relevant)
				if (!control.nodeIdle(id) || !control.nodeRootFree(id)
					|| !control.nodeMailboxEmpty(id))
					return ComputerBlockEntity.ReformResult.RUNTIME_NOT_QUIESCENT;
			EnumSet<EpochFault> union = unionFaults(replicaStates, authoritativeEpoch);
			if (!revalidateProofDomain(proofDomain, caller.getBlockPos(),
				List.of(oldRecord, authorityRecord), authoritativeEpoch, snapshot, world)
				|| !revalidateResolved(caller.getBlockPos(), snapshot, resolved, caller,
					world, limits, authoritativeEpoch, union))
				return ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT;
			ClusterEpoch replacement;
			try {
				replacement = ClusterEpoch.freeze(authoritativeEpoch.clusterId(),
					authorityRecord.computerStructureMemberId(), snapshot);
			} catch (IllegalArgumentException invalid) {
				return ComputerBlockEntity.ReformResult.STRUCTURE_INVALID;
			}
			Optional<ComputerStructureRecord> revised = authorityRecord.revise(
				replacement.coordinatorId(), snapshot);
			if (revised.isEmpty())
				return ComputerBlockEntity.ReformResult.PERSISTENCE_INVALID;
			ComputerStructureRecord updated = revised.orElseThrow();
			ComputerBlockEntity previous = resolved.byId().get(authorityRecord.coordinatorId());
			if (previous != null && previous.isCoordinatorMemberPublished()
				&& previous != stagingOwner) previous.withdrawCoordinatorMember();
			for (ComputerBlockEntity computer : resolved.computers())
				computer.stageTopologyState(updated, authorityState.binding(), true,
					replacement, Set.of());
			for (ComputerBlockEntity computer : resolved.computers()) {
				computer.setAvailabilityReason(ComputerAvailabilityReason.NONE);
				computer.publishTopologyChange();
			}
			stagingOwner.publishCoordinatorMember(resolved.computers(), snapshot.computerIds());
			return ComputerBlockEntity.ReformResult.REFORMED;
		} finally {
			synchronized (ACTIVE_REFORMS) { ACTIVE_REFORMS.remove(key); }
		}
	}

	static List<ComputerNodeView> currentNodes(ComputerBlockEntity computer) {
		ComputerStructureRecord record = computer.currentStructureRecord().orElse(null);
		if (record == null) return List.of();
		Set<UUID> frozen = frozenIds(computer.epoch().orElse(null));
		List<ComputerNodeView> result = new ArrayList<>();
		for (ComputerStructureNode node : record.snapshot().nodes()) {
			boolean pending = computer.epoch().isPresent() && !frozen.contains(node.computerId());
			boolean online = nodeOnlineForReason(computer.availabilityReason())
				&& node.profile() != null && physicallyOnline(computer, node);
			result.add(new ComputerNodeView(node.computerId(), node.profile(), online, pending,
				record.coordinatorId().equals(node.computerId())));
		}
		return List.copyOf(result);
	}

	static List<ComputerNodeView> frozenNodes(ComputerBlockEntity computer) {
		ClusterEpoch epoch = computer.epoch().orElse(null);
		ComputerStructureRecord record = computer.currentStructureRecord().orElse(null);
		if (epoch == null || record == null) return List.of();
		List<ComputerNodeView> result = new ArrayList<>();
		for (EpochNode node : epoch.nodes()) {
			ComputerStructureNode current = record.snapshot().node(node.computerId()).orElse(null);
			boolean exact = current != null && node.address().equals(current.address())
				&& node.profile().equals(current.profile());
			boolean online = nodeOnlineForReason(computer.availabilityReason()) && exact
				&& physicallyOnline(computer, current);
			result.add(new ComputerNodeView(node.computerId(), node.profile(), online, false,
				epoch.coordinatorId().equals(node.computerId())));
		}
		return List.copyOf(result);
	}

	static List<ComputerNodeView> pendingNodes(ComputerBlockEntity computer) {
		return currentNodes(computer).stream().filter(ComputerNodeView::pending).toList();
	}

	private static boolean nodeOnlineForReason(ComputerAvailabilityReason reason) {
		return reason == ComputerAvailabilityReason.NONE
			|| reason == ComputerAvailabilityReason.FROZEN_MEMBER_MISSING
			|| reason == ComputerAvailabilityReason.AUTHORITY_OFFLINE;
	}

	private static boolean physicallyOnline(ComputerBlockEntity owner,
		@Nullable ComputerStructureNode node) {
		if (node == null || node.profile() == null) return false;
		if (!(owner.getLevel() instanceof ServerLevel serverLevel)) return true;
		WorldAccess world = realWorld(serverLevel);
		BlockPos pos = node.address().localPos();
		if (!world.isLoaded(pos) || !world.sameSpace(owner.getBlockPos(), pos)) return false;
		ComputerBlockEntity current = world.loadedComputer(pos);
		return current != null && current.computerId().isPresent()
			&& current.computerId().orElseThrow().equals(node.computerId())
			&& Objects.equals(current.installedProfile().orElse(null), node.profile())
			&& world.address(pos).equals(node.address());
	}

	private static Set<UUID> frozenIds(@Nullable ClusterEpoch epoch) {
		if (epoch == null) return Set.of();
		Set<UUID> result = new LinkedHashSet<>();
		for (EpochNode node : epoch.nodes()) result.add(node.computerId());
		return Set.copyOf(result);
	}

	private static ComputerAvailabilityReason reasonFor(ComputerStructureScanner.State state) {
		return switch (state) {
			case PARTIAL_UNLOADED -> ComputerAvailabilityReason.PARTIAL_UNLOADED;
			case AMBIGUOUS -> ComputerAvailabilityReason.AMBIGUOUS;
			case IDENTITY_INVALID, STRUCTURE_IDENTITY_CONFLICT ->
				ComputerAvailabilityReason.IDENTITY_CONFLICT;
			default -> ComputerAvailabilityReason.STRUCTURE_INVALID;
		};
	}

	private static void setReason(List<ComputerBlockEntity> computers,
		ComputerAvailabilityReason reason) {
		for (ComputerBlockEntity computer : computers) computer.setAvailabilityReason(reason);
	}

	private static void setReasonAndWithdraw(List<ComputerBlockEntity> computers,
		ComputerAvailabilityReason reason) {
		for (ComputerBlockEntity computer : computers) fail(computer, reason);
	}

	private static void fail(ComputerBlockEntity computer, ComputerAvailabilityReason reason) {
		boolean activeCoordinator = computer.epoch().isPresent()
			&& computer.currentStructureRecord().filter(record -> computer.computerId()
				.filter(record.coordinatorId()::equals).isPresent()).isPresent();
		if (!activeCoordinator || reason != ComputerAvailabilityReason.PARTIAL_UNLOADED)
			computer.withdrawCoordinatorMember();
		computer.setAvailabilityReason(reason);
	}

	private static boolean publicationReady(ResolvedSnapshot resolved,
		ComputerStructureRecord record, @Nullable ClusterEpoch epoch, Set<EpochFault> faults,
		ComputerAvailabilityReason reason) {
		if (!faults.isEmpty()) return false;
		ComputerBlockEntity elected = resolved.byId().get(record.coordinatorId());
		if (elected == null || elected.computerId().filter(record.coordinatorId()::equals).isEmpty())
			return false;
		if (epoch == null)
			return reason == ComputerAvailabilityReason.NONE
				&& record.snapshot().nodes().stream().allMatch(node -> node.profile() != null);
		if (reason != ComputerAvailabilityReason.NONE
			&& reason != ComputerAvailabilityReason.FROZEN_MEMBER_MISSING) return false;
		EpochNode frozen = epoch.nodes().stream()
			.filter(node -> node.computerId().equals(record.coordinatorId()))
			.findFirst().orElse(null);
		return frozen != null && elected.installedProfile().filter(frozen.profile()::equals).isPresent();
	}

	private static boolean chunksLoaded(Set<ChunkPos> chunks, WorldAccess world) {
		for (ChunkPos chunk : chunks) if (!world.isChunkLoaded(chunk)) return false;
		return true;
	}

	private static ResolvedSnapshot resolveSnapshot(BlockPos seed,
		ComputerStructureSnapshot snapshot, WorldAccess world) {
		List<ComputerBlockEntity> computers = new ArrayList<>();
		List<ComputerBlockEntity.TopologyState> states = new ArrayList<>();
		Map<UUID, ComputerBlockEntity> byId = new LinkedHashMap<>();
		if (!chunksLoaded(snapshot.containingChunks(), world))
			return ResolvedSnapshot.failure(world, ResolveFailure.PARTIAL);
		for (ComputerStructureNode node : snapshot.nodes()) {
			BlockPos pos = node.address().localPos();
			if (!world.isLoaded(pos)) return ResolvedSnapshot.failure(world, ResolveFailure.PARTIAL);
			if (!world.sameSpace(seed, pos)) return ResolvedSnapshot.failure(world, ResolveFailure.SPACE);
			ComputerBlockEntity computer = world.loadedComputer(pos);
			if (computer == null || computer.computerId().isEmpty()
				|| !computer.computerId().orElseThrow().equals(node.computerId())
				|| !world.address(pos).equals(node.address())
				|| !computer.persistenceAvailable()
				|| !Objects.equals(computer.installedProfile().orElse(null), node.profile())
				|| byId.put(node.computerId(), computer) != null)
				return ResolvedSnapshot.failure(world, ResolveFailure.IDENTITY);
			computers.add(computer);
			states.add(computer.topologyState());
		}
		return new ResolvedSnapshot(List.copyOf(computers), Map.copyOf(byId),
			List.copyOf(states), world, ResolveFailure.NONE);
	}

	private static List<ComputerBlockEntity> loadedEpochReplicas(ComputerBlockEntity caller,
		ClusterEpoch epoch, ComputerStructureRecord record, WorldAccess world) {
		Map<UUID, BlockPos> positions = new LinkedHashMap<>();
		for (ComputerStructureNode node : record.snapshot().nodes())
			positions.put(node.computerId(), node.address().localPos());
		for (EpochNode node : epoch.nodes()) positions.putIfAbsent(node.computerId(), node.address().localPos());
		List<ComputerBlockEntity> result = new ArrayList<>();
		for (Map.Entry<UUID, BlockPos> entry : positions.entrySet()) {
			BlockPos pos = entry.getValue();
			if (!world.isLoaded(pos) || !world.sameSpace(caller.getBlockPos(), pos)) continue;
			ComputerBlockEntity computer = world.loadedComputer(pos);
			if (computer == null || computer.computerId().isEmpty()
				|| !computer.computerId().orElseThrow().equals(entry.getKey())
				|| computer.epoch().isEmpty()
				|| !computer.epoch().orElseThrow().epochId().equals(epoch.epochId())
				|| !computer.computerStructureMemberId().orElse(new UUID(0, 0))
					.equals(epoch.computerStructureMemberId())) continue;
			result.add(computer);
		}
		if (!result.contains(caller)) result.add(caller);
		return List.copyOf(result);
	}

	private static ProofFailure proveOldDomain(BlockPos seed, ComputerStructureRecord record,
		ClusterEpoch epoch, WorldAccess world) {
		Set<ChunkPos> chunks = new LinkedHashSet<>(record.snapshot().containingChunks());
		addBoundsChunks(chunks, epoch.bounds());
		if (!chunksLoaded(chunks, world)) return ProofFailure.PARTIAL;
		ProofFailure recordProof = proveBounds(seed, record.snapshot().bounds(), world);
		if (recordProof != ProofFailure.NONE) return recordProof;
		ProofFailure epochProof = proveBounds(seed, epoch.bounds(), world);
		if (epochProof != ProofFailure.NONE) return epochProof;
		for (EpochNode node : epoch.nodes()) {
			BlockPos pos = node.address().localPos();
			if (!world.isLoaded(pos)) return ProofFailure.PARTIAL;
			if (!world.sameSpace(seed, pos)) return ProofFailure.SPACE;
			SpaceAddress current = world.address(pos);
			if (!current.dimension().equals(node.address().dimension())
				|| !Objects.equals(current.subLevelId(), node.address().subLevelId()))
				return ProofFailure.SPACE;
		}
		return ProofFailure.NONE;
	}

	private static ProofFailure proveInactiveReelectionDomain(BlockPos seed,
		ComputerStructureRecord record, ComputerStructureSnapshot current, WorldAccess world) {
		Set<ChunkPos> chunks = new LinkedHashSet<>(record.snapshot().containingChunks());
		chunks.addAll(current.containingChunks());
		if (!chunksLoaded(chunks, world)) return ProofFailure.PARTIAL;
		ProofFailure oldProof = proveBounds(seed, record.snapshot().bounds(), world);
		if (oldProof != ProofFailure.NONE) return oldProof;
		return proveBounds(seed, current.bounds(), world);
	}

	private static List<BlockPos> computerIdPositionsInBounds(UUID computerId,
		List<BoundingBox> bounds, WorldAccess world) {
		List<BlockPos> matches = new ArrayList<>();
		for (BlockPos pos : positions(bounds, List.of())) {
			ComputerBlockEntity computer = world.loadedComputer(pos);
			if (computer != null && computer.computerId().filter(computerId::equals).isPresent())
				matches.add(pos);
		}
		return List.copyOf(matches);
	}

	private static boolean exactLoadedCaller(ComputerBlockEntity caller,
		ComputerBlockEntity.TopologyState state, ComputerStructureRecord record,
		WorldAccess world) {
		UUID computerId = state.computerId();
		if (computerId == null) return false;
		ComputerStructureNode persisted = record.snapshot().node(computerId).orElse(null);
		if (persisted == null || !persisted.address().localPos().equals(caller.getBlockPos()))
			return false;
		BlockPos pos = caller.getBlockPos();
		return world.isLoaded(pos) && world.sameSpace(pos, pos)
			&& world.loadedComputer(pos) == caller
			&& world.address(pos).equals(persisted.address())
			&& caller.computerId().filter(computerId::equals).isPresent();
	}

	private static ProofDomain captureProofDomain(BlockPos seed,
		List<ComputerStructureRecord> records, ClusterEpoch epoch,
		ComputerStructureSnapshot current, WorldAccess world) {
		Set<ChunkPos> chunks = new LinkedHashSet<>(current.containingChunks());
		List<BoundingBox> bounds = new ArrayList<>();
		for (ComputerStructureRecord record : records) {
			chunks.addAll(record.snapshot().containingChunks());
			bounds.add(record.snapshot().bounds());
		}
		addBoundsChunks(chunks, epoch.bounds());
		bounds.add(epoch.bounds());
		bounds.add(current.bounds());
		if (!chunksLoaded(chunks, world)) return ProofDomain.failure(ProofFailure.PARTIAL);
		for (BoundingBox box : bounds) {
			ProofFailure proof = proveBounds(seed, box, world);
			if (proof != ProofFailure.NONE) return ProofDomain.failure(proof);
		}
		List<SpaceAddress> addresses = epoch.nodes().stream().map(EpochNode::address).toList();
		for (SpaceAddress address : addresses) {
			BlockPos pos = address.localPos();
			if (!world.isLoaded(pos)) return ProofDomain.failure(ProofFailure.PARTIAL);
			if (!world.sameSpace(seed, pos)) return ProofDomain.failure(ProofFailure.SPACE);
			SpaceAddress actual = world.address(pos);
			if (!actual.dimension().equals(address.dimension())
				|| !Objects.equals(actual.subLevelId(), address.subLevelId()))
				return ProofDomain.failure(ProofFailure.SPACE);
		}
		List<ProofCell> cells = new ArrayList<>();
		for (BlockPos pos : positions(bounds, addresses)) {
			ComputerBlockEntity computer = world.loadedComputer(pos);
			cells.add(new ProofCell(pos, computer,
				computer == null ? null : computer.topologyState()));
		}
		return ProofDomain.success(cells);
	}

	private static List<BlockPos> positions(List<BoundingBox> bounds,
		List<SpaceAddress> addresses) {
		Set<BlockPos> positions = new LinkedHashSet<>();
		for (BoundingBox box : bounds)
			for (int x = box.minX(); x <= box.maxX(); x++)
				for (int y = box.minY(); y <= box.maxY(); y++)
					for (int z = box.minZ(); z <= box.maxZ(); z++)
						positions.add(new BlockPos(x, y, z));
		for (SpaceAddress address : addresses) positions.add(address.localPos().immutable());
		return List.copyOf(positions);
	}

	private static List<ComputerBlockEntity.TopologyState> relevantReplicaStates(
		ProofDomain domain, UUID structureId, UUID epochId, Set<UUID> currentIds) {
		List<ComputerBlockEntity.TopologyState> result = new ArrayList<>();
		for (ProofCell cell : domain.cells()) {
			ComputerBlockEntity.TopologyState state = cell.state();
			if (state == null) continue;
			boolean structureReplica = state.record() != null && state.record()
				.computerStructureMemberId().equals(structureId);
			boolean epochReplica = state.epoch() != null
				&& (state.epoch().epochId().equals(epochId)
					|| state.epoch().computerStructureMemberId().equals(structureId));
			boolean currentReplica = state.computerId() != null
				&& currentIds.contains(state.computerId());
			if (structureReplica || epochReplica || currentReplica) result.add(state);
		}
		return List.copyOf(result);
	}

	private static int proofComputerCount(ProofDomain domain, UUID computerId) {
		int count = 0;
		for (ProofCell cell : domain.cells())
			if (cell.computer() != null
				&& cell.computer().computerId().filter(computerId::equals).isPresent()) count++;
		return count;
	}

	private static boolean revalidateProofDomain(ProofDomain original, BlockPos seed,
		List<ComputerStructureRecord> records, ClusterEpoch epoch,
		ComputerStructureSnapshot current, WorldAccess world) {
		ProofDomain now = captureProofDomain(seed, records, epoch, current, world);
		if (now.failure() != ProofFailure.NONE
			|| now.cells().size() != original.cells().size()) return false;
		for (int i = 0; i < original.cells().size(); i++) {
			ProofCell before = original.cells().get(i);
			ProofCell after = now.cells().get(i);
			if (!before.pos().equals(after.pos()) || before.computer() != after.computer()) return false;
			if (before.computer() != null && (before.state() == null
				|| !before.computer().topologyMatches(before.state()))) return false;
		}
		return true;
	}

	private static ProofFailure proveBounds(BlockPos seed, BoundingBox bounds, WorldAccess world) {
		for (int x = bounds.minX(); x <= bounds.maxX(); x++)
			for (int y = bounds.minY(); y <= bounds.maxY(); y++)
				for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!world.isLoaded(pos)) return ProofFailure.PARTIAL;
					if (!world.sameSpace(seed, pos)) return ProofFailure.SPACE;
				}
		return ProofFailure.NONE;
	}

	private static ProofFailure proveCurrentDomain(BlockPos seed,
		ComputerStructureSnapshot snapshot, WorldAccess world) {
		if (!chunksLoaded(snapshot.containingChunks(), world)) return ProofFailure.PARTIAL;
		return proveBounds(seed, snapshot.bounds(), world);
	}

	private static void addBoundsChunks(Set<ChunkPos> chunks, BoundingBox bounds) {
		for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++)
			for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++)
				chunks.add(new ChunkPos(x, z));
	}

	private static @Nullable ComputerBlockEntity.EpochCloseResult closeScanFailure(
		ComputerStructureScanner.ScanResult scan) {
		return switch (scan.state()) {
			case VALID, VALID_NOT_READY -> null;
			case PARTIAL_UNLOADED -> ComputerBlockEntity.EpochCloseResult.PARTIAL_UNLOADED;
			case SPACE -> ComputerBlockEntity.EpochCloseResult.SPACE_UNCERTAIN;
			case IDENTITY_INVALID, STRUCTURE_IDENTITY_CONFLICT, AMBIGUOUS ->
				ComputerBlockEntity.EpochCloseResult.IDENTITY_CONFLICT;
			default -> ComputerBlockEntity.EpochCloseResult.STRUCTURE_INVALID;
		};
	}

	private static @Nullable ComputerBlockEntity.ReformResult reformScanFailure(
		ComputerStructureScanner.ScanResult scan) {
		return switch (scan.state()) {
			case VALID -> null;
			case VALID_NOT_READY -> ComputerBlockEntity.ReformResult.PROFILE_NOT_READY;
			case PARTIAL_UNLOADED -> ComputerBlockEntity.ReformResult.PARTIAL_UNLOADED;
			case SPACE -> ComputerBlockEntity.ReformResult.SPACE_UNCERTAIN;
			case IDENTITY_INVALID, STRUCTURE_IDENTITY_CONFLICT, AMBIGUOUS ->
				ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT;
			default -> ComputerBlockEntity.ReformResult.STRUCTURE_INVALID;
		};
	}

	private static EnumSet<EpochFault> unionFaults(ResolvedSnapshot resolved, UUID epochId) {
		EnumSet<EpochFault> union = EnumSet.noneOf(EpochFault.class);
		for (ComputerBlockEntity computer : resolved.computers())
			if (computer.epoch().isPresent()
				&& computer.epoch().orElseThrow().epochId().equals(epochId))
				union.addAll(computer.latchedEpochFaults());
		return union;
	}

	private static EnumSet<EpochFault> unionFaults(
		List<ComputerBlockEntity.TopologyState> states, ClusterEpoch epoch) {
		EnumSet<EpochFault> union = EnumSet.noneOf(EpochFault.class);
		for (ComputerBlockEntity.TopologyState state : states)
			if (epoch.equals(state.epoch())) union.addAll(state.faults());
		return union;
	}

	private static @Nullable TransactionAuthority closeAuthority(
		ResolvedSnapshot resolved, ComputerStructureSnapshot snapshot, UUID structureId,
		UUID expectedEpochId) {
		ComputerBlockEntity minimum = resolved.byId().get(snapshot.nodes().getFirst().computerId());
		if (minimum == null) return null;
		ClusterEpoch epoch = minimum.epoch().orElse(null);
		if (epoch == null || !epoch.epochId().equals(expectedEpochId)) return null;
		ComputerBlockEntity coordinator = resolved.byId().get(epoch.coordinatorId());
		if (coordinator == null) return null;
		ComputerBlockEntity.TopologyState state = coordinator.topologyState();
		ComputerStructureRecord record = state.record();
		if (!state.persistenceAvailable() || !state.bindingValid() || state.binding() == null
			|| record == null || !epoch.equals(state.epoch())
			|| !record.computerStructureMemberId().equals(structureId)
			|| !record.coordinatorId().equals(epoch.coordinatorId())
			|| !coordinator.computerId().filter(epoch.coordinatorId()::equals).isPresent()) return null;
		return new TransactionAuthority(record, epoch);
	}

	private static boolean transactionReplicasMatch(
		List<ComputerBlockEntity.TopologyState> states,
		ClusterEpoch epoch, ComputerStructureRecord authority) {
		if (states.isEmpty()
			|| !recordsCompatible(states, authority, authority.computerStructureMemberId()))
			return false;
		ClusterBinding binding = null;
		for (ComputerBlockEntity.TopologyState state : states) {
			if (!state.persistenceAvailable() || !state.bindingValid() || state.binding() == null
				|| !state.binding().clusterId().equals(epoch.clusterId())
				|| !epoch.equals(state.epoch())
				|| state.record() == null || !state.record().computerStructureMemberId()
					.equals(authority.computerStructureMemberId())) return false;
			if (binding == null) binding = state.binding();
			else if (!binding.equals(state.binding())) return false;
		}
		return binding != null;
	}

	private static List<UUID> sortedUnion(Set<UUID> first, Set<UUID> second) {
		TreeSet<UUID> union = new TreeSet<>(UUID_ORDER);
		union.addAll(first);
		union.addAll(second);
		return List.copyOf(union);
	}

	private static boolean revalidateResolved(BlockPos seed, ComputerStructureSnapshot snapshot,
		ResolvedSnapshot original, ComputerBlockEntity caller, WorldAccess world,
		ComputerStructureScanner.Limits limits, ClusterEpoch expectedEpoch,
		Set<EpochFault> faultUnion) {
		ComputerStructureScanner.ScanResult rescanned = world.scan(seed, limits);
		UUID structureId = original.states().stream()
			.map(ComputerBlockEntity.TopologyState::record).filter(Objects::nonNull)
			.map(ComputerStructureRecord::computerStructureMemberId).findFirst().orElse(null);
		if ((rescanned.state() != ComputerStructureScanner.State.VALID
			&& rescanned.state() != ComputerStructureScanner.State.VALID_NOT_READY)
			|| rescanned.snapshot() == null || !snapshot.equals(rescanned.snapshot())
			|| structureId == null || !structureId.equals(rescanned.observedStructureMemberId()))
			return false;
		ResolvedSnapshot current = resolveSnapshot(seed, snapshot, world);
		if (current.failure() != ResolveFailure.NONE
			|| current.computers().size() != original.computers().size()) return false;
		UUID callerId = caller.computerId().orElse(null);
		if (callerId == null || current.byId().get(callerId) != caller) return false;
		for (int i = 0; i < original.computers().size(); i++) {
			ComputerBlockEntity before = original.computers().get(i);
			ComputerBlockEntity now = current.computers().get(i);
			if (before != now || !before.topologyMatches(original.states().get(i))) return false;
			ClusterEpoch epoch = before.epoch().orElse(null);
			if (!expectedEpoch.equals(epoch)) return false;
			if (!faultUnion.containsAll(before.latchedEpochFaults())) return false;
		}
		return unionFaults(current, expectedEpoch.epochId()).equals(faultUnion);
	}

	private record Reconciliation(ComputerStructureRecord record, @Nullable ClusterBinding binding,
		@Nullable ClusterEpoch epoch, Set<EpochFault> faults, ComputerAvailabilityReason reason,
		@Nullable ComputerAvailabilityReason failureReason) {
		static Reconciliation failure(ComputerAvailabilityReason reason) {
			return new Reconciliation(null, null, null, Set.of(), reason, reason);
		}
	}

	private record ResolvedSnapshot(List<ComputerBlockEntity> computers,
		Map<UUID, ComputerBlockEntity> byId, List<ComputerBlockEntity.TopologyState> states,
		WorldAccess world, ResolveFailure failure) {
		static ResolvedSnapshot failure(WorldAccess world, ResolveFailure failure) {
			return new ResolvedSnapshot(List.of(), Map.of(), List.of(), world, failure);
		}
	}

	private record RecordSelection(@Nullable ComputerStructureRecord record, boolean valid) {
		static RecordSelection valid(@Nullable ComputerStructureRecord record) {
			return new RecordSelection(record, true);
		}
		static RecordSelection invalid() { return new RecordSelection(null, false); }
	}
	private record TransactionAuthority(ComputerStructureRecord record, ClusterEpoch epoch) {}
	private record ProofCell(BlockPos pos, @Nullable ComputerBlockEntity computer,
		@Nullable ComputerBlockEntity.TopologyState state) {}
	private record ProofDomain(ProofFailure failure, List<ProofCell> cells) {
		static ProofDomain success(List<ProofCell> cells) {
			return new ProofDomain(ProofFailure.NONE, List.copyOf(cells));
		}
		static ProofDomain failure(ProofFailure failure) {
			return new ProofDomain(failure, List.of());
		}
	}

	private enum ResolveFailure { NONE, PARTIAL, SPACE, IDENTITY }
	private enum ProofFailure { NONE, PARTIAL, SPACE }
	private record TransactionKey(UUID structureId, UUID epochId) {}

	private static final class RealWorldAccess implements WorldAccess {
		private final ServerLevel level;
		private RealWorldAccess(ServerLevel level) { this.level = level; }

		@Override
		public ComputerStructureScanner.ScanResult scan(BlockPos seed,
			ComputerStructureScanner.Limits limits) {
			return ComputerStructureScanner.scan(new ComputerWorldView(level, seed), seed, limits);
		}
		@Override public boolean isLoaded(BlockPos pos) {
			return CBMultiBlockLifecycle.isLoaded(level, pos);
		}
		@Override public boolean isChunkLoaded(ChunkPos chunk) {
			return level.hasChunk(chunk.x, chunk.z);
		}
		@Override public boolean sameSpace(BlockPos origin, BlockPos pos) {
			return SubLevelCompat.sameSpace(level, origin, pos);
		}
		@Override public @Nullable ComputerBlockEntity loadedComputer(BlockPos pos) {
			BlockEntity blockEntity = SubLevelCompat.getLoadedBlockEntity(level, pos);
			return blockEntity instanceof ComputerBlockEntity computer ? computer : null;
		}
		@Override public SpaceAddress address(BlockPos pos) { return SpaceAddress.capture(level, pos); }
		@Override public UUID newStructureMemberId() { return UUID.randomUUID(); }
		@Override public ClusterBindingService.BindingAccess bindingAccess(
			ComputerCoordinatorMember member) {
			return ClusterBindingService.bindingAccess(level.getServer(), member);
		}
		@Override public boolean enforceFoundationPublication() { return true; }
		@Override public @Nullable ComputerBlockEntity resolveComputer(SpaceAddress address) {
			BlockEntity blockEntity = address.resolveBlockEntity(level.getServer());
			return blockEntity instanceof ComputerBlockEntity computer ? computer : null;
		}
	}
}
