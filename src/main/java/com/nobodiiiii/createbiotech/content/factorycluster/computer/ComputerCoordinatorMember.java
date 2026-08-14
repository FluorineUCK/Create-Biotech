package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;

import net.minecraft.server.MinecraftServer;

/** The one Foundation-facing identity of an accepted Computer structure. */
public final class ComputerCoordinatorMember implements ClusterMember {
	private final ComputerBlockEntity owner;
	private ReplicaSet replicas = ReplicaSet.empty();
	@Nullable private PreparedBinding preparedBinding;
	private ClusterBindingService.BindingAccess bindingAccess =
		ClusterBindingService.BindingAccess.CONFLICT;

	ComputerCoordinatorMember(ComputerBlockEntity owner) {
		this.owner = Objects.requireNonNull(owner, "owner");
	}

	@Override
	public UUID memberId() {
		return owner.computerStructureMemberId().orElseThrow();
	}

	public UUID coordinatorComputerId() {
		return owner.computerId().orElseThrow();
	}

	@Override public @Nullable ClusterBinding bindingState() { return owner.bindingState(); }
	@Override public boolean hasValidBindingState() { return owner.bindingStateValid(); }
	@Override public ClusterMemberType memberType() {
		return ClusterMemberType.COMPUTER_COORDINATOR;
	}
	@Override public SpaceAddress memberAddress() { return owner.coordinatorMemberAddress(); }

	@Override
	public boolean canRebind() {
		return owner.isCoordinatorMemberPublished() && owner.epoch().isEmpty()
			&& owner.latchedEpochFaults().isEmpty() && replicas.validFor(owner)
			&& (bindingState() == null
				|| bindingAccess == ClusterBindingService.BindingAccess.READY);
	}

	@Override
	public ClusterBindingPreparation prepareClusterBinding(ClusterBinding proposed) {
		preparedBinding = null;
		if (proposed == null || proposed.authority() == null || !replicas.validFor(owner))
			return ClusterBindingPreparation.IDENTITY;
		if (!owner.isCoordinatorMemberPublished())
			return ClusterBindingPreparation.IDENTITY;
		if (owner.epoch().isPresent() || !owner.latchedEpochFaults().isEmpty()
			|| bindingState() != null
				&& bindingAccess != ClusterBindingService.BindingAccess.READY)
			return ClusterBindingPreparation.ACTIVE;
		if (proposed.logisticsBindings().size() > ClusterBinding.MAX_BINDINGS)
			return ClusterBindingPreparation.CAPACITY;
		SpaceAddress address;
		try {
			address = memberAddress();
		} catch (RuntimeException unavailable) {
			return ClusterBindingPreparation.IDENTITY;
		}
		List<ComputerBlockEntity> exact = replicas.exactReplicas();
		for (ComputerBlockEntity replica : exact) {
			ComputerBlockEntity.TopologyState state = replica.topologyState();
			ComputerStructureRecord record = state.record();
			if (!state.persistenceAvailable() || !state.bindingValid()
				|| state.computerId() == null || state.profile() == null || record == null
				|| !record.computerStructureMemberId().equals(memberId())
				|| !record.coordinatorId().equals(coordinatorComputerId())
				|| !record.snapshot().computerIds().equals(replicas.requiredIds())
				|| record.snapshot().node(state.computerId()).isEmpty()
				|| !address.dimension().equals(record.snapshot().node(state.computerId())
					.orElseThrow().address().dimension())
				|| state.epoch() != null || !state.faults().isEmpty())
				return ClusterBindingPreparation.IDENTITY;
			ClusterBinding current = state.binding();
			if (current != null && (proposed.revision() < current.revision()
				|| proposed.revision() == current.revision() && !proposed.equals(current)))
				return ClusterBindingPreparation.REVISION;
		}
		preparedBinding = new PreparedBinding(proposed, exact);
		return ClusterBindingPreparation.READY;
	}

	@Override
	public void commitClusterBinding(ClusterBinding prepared) {
		PreparedBinding accepted = preparedBinding;
		Runnable mutation = () -> accepted.exactReplicas().forEach(replica ->
			replica.stagePreparedBinding(accepted.binding()));
		MinecraftServer server = owner.coordinatorServer();
		if (server == null) mutation.run();
		else ClusterMemberIndex.rebind(server, this, mutation);
		accepted.exactReplicas().forEach(ComputerBlockEntity::publishPreparedBinding);
		bindingAccess = ClusterBindingService.BindingAccess.READY;
		preparedBinding = null;
	}

	void acceptReplicaSet(List<ComputerBlockEntity> exactReplicas, Set<UUID> requiredIds) {
		Objects.requireNonNull(exactReplicas, "exactReplicas");
		Objects.requireNonNull(requiredIds, "requiredIds");
		List<ComputerBlockEntity> copy = List.copyOf(exactReplicas);
		Set<ComputerBlockEntity> retained = java.util.Collections.newSetFromMap(
			new java.util.IdentityHashMap<>());
		retained.addAll(copy);
		for (ComputerBlockEntity old : replicas.exactReplicas())
			if (!retained.contains(old)) old.detachCoordinatorPublication(this);
		copy.forEach(replica -> replica.attachCoordinatorPublication(this));
		replicas = new ReplicaSet(copy, Set.copyOf(requiredIds), true);
		preparedBinding = null;
	}

	void clearReplicaSet() {
		for (ComputerBlockEntity replica : replicas.exactReplicas())
			replica.detachCoordinatorPublication(this);
		replicas = ReplicaSet.empty();
		preparedBinding = null;
		bindingAccess = ClusterBindingService.BindingAccess.CONFLICT;
	}

	void replicaInvalidated(ComputerBlockEntity replica) {
		if (!replicas.containsIdentity(replica)) return;
		replicas = replicas.invalidated();
		preparedBinding = null;
		if (replica == owner || owner.epoch().isEmpty())
			owner.withdrawCoordinatorMember();
	}

	boolean updateBindingAccess(ClusterBindingService.BindingAccess access) {
		Objects.requireNonNull(access, "access");
		if (access != ClusterBindingService.BindingAccess.READY) {
			bindingAccess = access;
			return false;
		}
		ClusterBinding authoritative = bindingState();
		if (authoritative == null || !replicas.validFor(owner)) {
			bindingAccess = ClusterBindingService.BindingAccess.CONFLICT;
			return false;
		}
		List<ComputerBlockEntity> lower = new ArrayList<>();
		for (ComputerBlockEntity replica : replicas.exactReplicas()) {
			ClusterBinding state = replica.bindingState();
			if (state == null || !state.clusterId().equals(authoritative.clusterId())
				|| state.revision() > authoritative.revision()
				|| state.revision() == authoritative.revision()
					&& !state.equals(authoritative)) {
				bindingAccess = ClusterBindingService.BindingAccess.CONFLICT;
				return false;
			}
			if (state.revision() < authoritative.revision()) lower.add(replica);
		}
		lower.forEach(replica -> replica.stagePreparedBinding(authoritative));
		lower.forEach(ComputerBlockEntity::publishPreparedBinding);
		bindingAccess = ClusterBindingService.BindingAccess.READY;
		return true;
	}

	private record PreparedBinding(ClusterBinding binding,
		List<ComputerBlockEntity> exactReplicas) {
		private PreparedBinding {
			Objects.requireNonNull(binding, "binding");
			exactReplicas = List.copyOf(exactReplicas);
		}
	}

	private record ReplicaSet(List<ComputerBlockEntity> exactReplicas,
		Set<UUID> requiredIds, boolean complete) {
		private ReplicaSet {
			exactReplicas = List.copyOf(exactReplicas);
			requiredIds = Set.copyOf(requiredIds);
		}
		private static ReplicaSet empty() { return new ReplicaSet(List.of(), Set.of(), false); }
		private ReplicaSet invalidated() {
			return new ReplicaSet(exactReplicas, requiredIds, false);
		}
		private boolean containsIdentity(ComputerBlockEntity candidate) {
			return exactReplicas.stream().anyMatch(replica -> replica == candidate);
		}
		private boolean validFor(ComputerBlockEntity owner) {
			if (!complete || exactReplicas.isEmpty()) return false;
			Set<UUID> actual = new LinkedHashSet<>();
			for (ComputerBlockEntity replica : exactReplicas) {
				if (!replica.isAttachedTo(owner.coordinatorMember)
					|| replica.computerId().isEmpty()
					|| !actual.add(replica.computerId().orElseThrow())) return false;
			}
			return actual.equals(requiredIds);
		}
	}
}
