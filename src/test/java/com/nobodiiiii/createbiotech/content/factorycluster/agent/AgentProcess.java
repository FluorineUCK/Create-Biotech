package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;

/** The one Alchemist-backed resident process shared by every endpoint of one Agent. */
public final class AgentProcess implements AutoCloseable {
	private final SlotAllocator allocator;
	private AgentFormation formation;
	@Nullable private ResidentSlot residentSlot;
	@Nullable private ActiveContract activeContract;
	private boolean available;
	private boolean closed;

	private AgentProcess(AgentFormation formation, SlotAllocator allocator) {
		this(formation, allocator, true);
	}

	private AgentProcess(AgentFormation formation, SlotAllocator allocator, boolean acquireSlot) {
		this.formation = Objects.requireNonNull(formation, "formation");
		this.allocator = Objects.requireNonNull(allocator, "allocator");
		if (acquireSlot) acquireResidentSlot();
	}

	public static AgentProcess form(AgentFormation formation, SlotAllocator allocator) {
		return new AgentProcess(formation, allocator);
	}

	static AgentProcess restore(AgentFormation observed, SlotAllocator allocator,
		AgentCoordinator.PersistedState state) {
		AgentFormation persisted = persistedFormation(observed, state);
		AgentProcess restored = new AgentProcess(persisted, allocator, false);
		restored.restoreInPlace(observed, state);
		return restored;
	}

	void restoreInPlace(AgentFormation observed, AgentCoordinator.PersistedState state) {
		AgentFormation persisted = persistedFormation(observed, state);
		activeContract = state.activeContractState().orElse(null);
		ResidentSlot savedSlot = state.residentSlot().orElse(null);
		boolean membershipMatches = observed.members().equals(persisted.members());
		if (!membershipMatches) {
			releaseResidentSlot();
			formation = persisted;
			available = false;
			return;
		}
		if (savedSlot != null && savedSlot.equals(residentSlot)
			&& allocator.stillValid(savedSlot)) {
			formation = persisted;
			available = state.available();
			return;
		}
		releaseResidentSlot();
		formation = persisted;
		if (savedSlot != null && allocator.stillValid(savedSlot)) {
			residentSlot = savedSlot;
			available = state.available();
		}
	}

	private static AgentFormation persistedFormation(AgentFormation observed,
		AgentCoordinator.PersistedState state) {
		Objects.requireNonNull(observed, "observed");
		Objects.requireNonNull(state, "state");
		AgentFormation persisted = AgentFormation.form(state.endpointSnapshots());
		if (!persisted.agentId().equals(state.agentId())
			|| !persisted.ownerClusterId().equals(state.ownerClusterId())
			|| persisted.agentGeneration() != state.agentGeneration()
			|| !persisted.coordinatorAddress().equals(state.coordinatorAddress()))
			throw new IllegalArgumentException("Persisted Agent formation identity is inconsistent");
		return persisted;
	}

	public boolean available() { return available && !closed; }
	AgentFormation formation() { return formation; }
	public Optional<UUID> activeContract() {
		return Optional.ofNullable(activeContract).map(ActiveContract::contractId);
	}
	public Optional<ActiveContract> activeContractState() {
		return Optional.ofNullable(activeContract);
	}
	public List<AgentEndpointState> managedEndpoints() { return formation.members(); }
	public Optional<ResidentSlot> residentSlot() { return Optional.ofNullable(residentSlot); }

	public boolean startContract(UUID contractId) {
		return startContract(contractId, 0);
	}

	public boolean startContract(UUID contractId, long fence) {
		contractId = Objects.requireNonNull(contractId, "contractId");
		if (fence < 0) throw new IllegalArgumentException("contract fence cannot be negative");
		if (!available() || activeContract != null) return false;
		activeContract = new ActiveContract(contractId, fence);
		return true;
	}

	public boolean finishContract(UUID contractId) {
		contractId = Objects.requireNonNull(contractId, "contractId");
		if (activeContract == null || !contractId.equals(activeContract.contractId())) return false;
		activeContract = null;
		return true;
	}

	boolean advanceResetGeneration(AgentFormation next) {
		AgentFormation candidate = Objects.requireNonNull(next, "next");
		if (closed || activeContract != null
			|| !formation.agentId().equals(candidate.agentId())
			|| !formation.ownerClusterId().equals(candidate.ownerClusterId())
			|| formation.agentGeneration() == Long.MAX_VALUE
			|| candidate.agentGeneration() != formation.agentGeneration() + 1
			|| !formation.members().stream().map(member -> member.withAgentIdentity(candidate.agentId(),
				candidate.ownerClusterId(), candidate.agentGeneration())).toList()
				.equals(candidate.members()))
			return false;
		formation = candidate;
		available = residentSlot != null && allocator.stillValid(residentSlot);
		return true;
	}

	boolean canInstallForceReset(UUID contractId, AgentFormation next) {
		contractId = Objects.requireNonNull(contractId, "contractId");
		AgentFormation candidate = Objects.requireNonNull(next, "next");
		return !closed && activeContract != null && contractId.equals(activeContract.contractId())
			&& formation.agentId().equals(candidate.agentId())
			&& formation.ownerClusterId().equals(candidate.ownerClusterId())
			&& formation.agentGeneration() != Long.MAX_VALUE
			&& candidate.agentGeneration() == formation.agentGeneration() + 1
			&& formation.members().stream().map(member -> member.withAgentIdentity(candidate.agentId(),
				candidate.ownerClusterId(), candidate.agentGeneration())).toList()
				.equals(candidate.members());
	}

	boolean availableAfterForceReset() {
		return !closed && residentSlot != null && allocator.stillValid(residentSlot);
	}

	boolean installCommittedForceReset(UUID contractId, AgentFormation next) {
		if (!canInstallForceReset(contractId, next)) return false;
		activeContract = null;
		formation = next;
		available = residentSlot != null && allocator.stillValid(residentSlot);
		return true;
	}

	boolean canInstallMembershipChange(AgentFormation next) {
		next = Objects.requireNonNull(next, "next");
		return !closed && activeContract == null
			&& formation.agentId().equals(next.agentId())
			&& formation.ownerClusterId().equals(next.ownerClusterId())
			&& formation.agentGeneration() != Long.MAX_VALUE
			&& next.agentGeneration() == formation.agentGeneration() + 1;
	}

	boolean availableAfterMembershipChange() {
		return !closed && residentSlot != null && allocator.stillValid(residentSlot);
	}

	boolean installCommittedMembershipChange(AgentFormation next) {
		if (!canInstallMembershipChange(next)) return false;
		formation = next;
		available = availableAfterMembershipChange();
		return true;
	}

	/** Membership changes during a contract preserve recovery state but make dispatch unavailable. */
	public boolean refreshFormation(AgentFormation next) {
		next = Objects.requireNonNull(next, "next");
		if (!sameIdentity(next) || activeContract != null
			&& !formation.members().equals(next.members())) {
			available = false;
			return false;
		}
		formation = next;
		available = residentSlot != null && allocator.stillValid(residentSlot);
		return available;
	}

	public void reconcileResidentSlot() {
		if (residentSlot != null && !allocator.stillValid(residentSlot)) {
			residentSlot = null;
			available = false;
		}
	}

	/** Reacquires the resident slot after an epoch loss without dropping an active contract. */
	public boolean recover(AgentFormation recovered) {
		recovered = Objects.requireNonNull(recovered, "recovered");
		if (closed) return false;
		if (!sameIdentity(recovered) || !formation.members().equals(recovered.members()))
			return false;
		formation = recovered;
		reconcileResidentSlot();
		if (residentSlot == null) acquireResidentSlot();
		else available = true;
		return available();
	}

	private boolean sameIdentity(AgentFormation other) {
		return formation.agentId().equals(other.agentId())
			&& formation.ownerClusterId().equals(other.ownerClusterId())
			&& formation.agentGeneration() == other.agentGeneration();
	}

	private void acquireResidentSlot() {
		Optional<ResidentSlot> allocated = allocator.acquire(formation.agentId(),
			formation.ownerClusterId(), ProcessKind.AGENT);
		if (allocated.isEmpty()) {
			residentSlot = null;
			available = false;
			return;
		}
		ResidentSlot slot = allocated.orElseThrow();
		if (slot.kind() != ProcessKind.AGENT || !slot.alchemistCapable()) {
			allocator.release(slot);
			throw new IllegalArgumentException("Agent residency requires one Alchemist AGENT slot");
		}
		residentSlot = slot;
		available = true;
	}

	private void releaseResidentSlot() {
		if (residentSlot == null) return;
		allocator.release(residentSlot);
		residentSlot = null;
	}

	@Override public void close() {
		if (closed) return;
		closed = true;
		available = false;
		releaseResidentSlot();
	}

	public interface SlotAllocator {
		Optional<ResidentSlot> acquire(UUID agentId, UUID ownerClusterId, ProcessKind kind);
		boolean stillValid(ResidentSlot slot);
		void release(ResidentSlot slot);
	}

	public record ActiveContract(UUID contractId, long fence) {
		public ActiveContract {
			contractId = Objects.requireNonNull(contractId, "contractId");
			if (fence < 0) throw new IllegalArgumentException("contract fence cannot be negative");
		}
	}

	public record ResidentSlot(UUID processId, UUID computerId, int slotIndex, UUID epochId,
		long slotGeneration, ProcessKind kind, boolean alchemistCapable) {
		public ResidentSlot(UUID processId, UUID computerId, int slotIndex, UUID epochId,
			ProcessKind kind, boolean alchemistCapable) {
			this(processId, computerId, slotIndex, epochId, 0, kind, alchemistCapable);
		}
		public ResidentSlot {
			processId = Objects.requireNonNull(processId, "processId");
			computerId = Objects.requireNonNull(computerId, "computerId");
			epochId = Objects.requireNonNull(epochId, "epochId");
			kind = Objects.requireNonNull(kind, "kind");
			if (slotIndex < 0 || slotGeneration < 0)
				throw new IllegalArgumentException("slot identity values cannot be negative");
		}
	}
}
