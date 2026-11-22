package com.nobodiiiii.createbiotech.content.biocomputing.application.production.process;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;


import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

/** Production-only reservation and child-acknowledgement state, never generic Computer execution/RAM. */
public record ProcessRecord(UUID processId, UUID goalId, UUID owningComputerId, int owningSlot,
	ProductionResourceProfile owningComputer, ProcessKind kind, ProcessState state,
	WaitReason waitReason, long generation,
	Map<UUID, ChildRevisionStatus> childRevisionStates) {
	/** Existing persistent child-list bound; every accepted process must remain saveable. */
	public static final int MAX_CHILD_REVISIONS = 256;

	public ProcessRecord {
		processId = Objects.requireNonNull(processId, "processId");
		goalId = Objects.requireNonNull(goalId, "goalId");
		owningComputerId = Objects.requireNonNull(owningComputerId, "owningComputerId");
		owningComputer = Objects.requireNonNull(owningComputer, "owningComputer");
		if (owningSlot < 0 || owningSlot >= owningComputer.processSlots())
			throw new IllegalArgumentException("Process must own exactly one valid Computer slot");
		kind = Objects.requireNonNull(kind, "kind");
		if (kind.requiresAlchemist() && !owningComputer.supports(ProductionCapability.ALCHEMIST))
			throw new IllegalArgumentException("Process requires an Alchemist-capable Computer");
		state = Objects.requireNonNull(state, "state");
		if ((state == ProcessState.WAITING) != (waitReason != null))
			throw new IllegalArgumentException("Only waiting processes have a wait reason");
		if (generation < 0)
			throw new IllegalArgumentException("Process generation must be non-negative");
		childRevisionStates = Map.copyOf(Objects.requireNonNull(childRevisionStates,
			"childRevisionStates"));
		if (childRevisionStates.size() > MAX_CHILD_REVISIONS)
			throw new IllegalArgumentException("Child revision table exceeds persistent bound");
		for (Map.Entry<UUID, ChildRevisionStatus> entry : childRevisionStates.entrySet()) {
			if (!entry.getKey().equals(entry.getValue().revision().childProcessId()))
				throw new IllegalArgumentException("Child revision map key must match child identity");
		}
	}

	/** Matches the current production parent, including a live parent waiting for its child ACK.
	 * This predicate alone is not material authority: the owner must check Goal/worker lifecycle
	 * and the exact durable child acknowledgement against its current snapshot. */
	public boolean matchesLiveGoalParent(UUID expectedGoalId, long expectedGeneration) {
		return goalId.equals(expectedGoalId) && kind == ProcessKind.GOAL
			&& state != ProcessState.TERMINAL && generation == expectedGeneration;
	}

	public Map<UUID, ChildExecutionRevision> childRevisions() {
		Map<UUID, ChildExecutionRevision> revisions = new HashMap<>();
		childRevisionStates.forEach((childId, status) -> revisions.put(childId, status.revision()));
		return Map.copyOf(revisions);
	}

	public RevisionAcceptance acceptChildRevision(ChildExecutionRevision candidate) {
		candidate = Objects.requireNonNull(candidate, "candidate");
		ChildRevisionStatus prior = childRevisionStates.get(candidate.childProcessId());
		if (prior != null) validateNextRevision(prior.revision(), candidate);
		Map<UUID, ChildRevisionStatus> nextStates = new HashMap<>(childRevisionStates);
		nextStates.put(candidate.childProcessId(), new ChildRevisionStatus(candidate, false));
		ProcessRecord next = new ProcessRecord(processId, goalId, owningComputerId, owningSlot,
			owningComputer, kind, state, waitReason, generation, nextStates);
		return new RevisionAcceptance(next, candidate.acknowledgement());
	}

	public ProcessRecord markChildAcknowledgementDurable(ChildExecutionRevision.Acknowledgement acknowledgement) {
		acknowledgement = Objects.requireNonNull(acknowledgement, "acknowledgement");
		ChildRevisionStatus status = childRevisionStates.get(acknowledgement.childProcessId());
		if (status == null || !status.revision().acknowledgement().equals(acknowledgement))
			throw new IllegalArgumentException("Acknowledgement does not match the latest child revision");
		Map<UUID, ChildRevisionStatus> nextStates = new HashMap<>(childRevisionStates);
		nextStates.put(acknowledgement.childProcessId(), new ChildRevisionStatus(status.revision(), true));
		return new ProcessRecord(processId, goalId, owningComputerId, owningSlot, owningComputer, kind,
			state, waitReason, generation, nextStates);
	}

	public boolean isChildAcknowledgedDurably(UUID childProcessId, long childGeneration,
		long revision) {
		ChildRevisionStatus status = childRevisionStates.get(childProcessId);
		return status != null && status.acknowledgementDurable()
			&& status.revision().childGeneration() == childGeneration
			&& status.revision().revision() == revision;
	}

	/** Returns the only permit that an irreversible child-boundary command may consume. */
	public IrreversibleBoundaryPermit authorizeIrreversibleChildBoundary(
		ChildExecutionRevision.Acknowledgement acknowledgement) {
		acknowledgement = Objects.requireNonNull(acknowledgement, "acknowledgement");
		ChildRevisionStatus status = childRevisionStates.get(acknowledgement.childProcessId());
		if (status == null || !status.revision().acknowledgement().equals(acknowledgement))
			throw new IllegalArgumentException("Acknowledgement does not match the latest child revision");
		if (!status.acknowledgementDurable())
			throw new IllegalStateException("Child acknowledgement is not durable");
		return new IrreversibleBoundaryPermit(processId, acknowledgement);
	}

	private static void validateNextRevision(ChildExecutionRevision prior,
		ChildExecutionRevision candidate) {
		if (candidate.childGeneration() < prior.childGeneration()
			|| (candidate.childGeneration() == prior.childGeneration()
				&& candidate.revision() <= prior.revision()))
			throw new IllegalArgumentException("Child revision is stale");
		if (candidate.totalPromise() != prior.totalPromise())
			throw new IllegalArgumentException("Child revision cannot change its total promise");
	}

	public static final class IrreversibleBoundaryPermit {
		private final UUID parentProcessId;
		private final ChildExecutionRevision.Acknowledgement acknowledgement;

		private IrreversibleBoundaryPermit(UUID parentProcessId,
			ChildExecutionRevision.Acknowledgement acknowledgement) {
			this.parentProcessId = parentProcessId;
			this.acknowledgement = acknowledgement;
		}

		public UUID parentProcessId() {
			return parentProcessId;
		}

		public ChildExecutionRevision.Acknowledgement acknowledgement() {
			return acknowledgement;
		}
	}

	public record ChildRevisionStatus(ChildExecutionRevision revision,
		boolean acknowledgementDurable) {
		public ChildRevisionStatus {
			revision = Objects.requireNonNull(revision, "revision");
		}
	}

	public record RevisionAcceptance(ProcessRecord parent,
		ChildExecutionRevision.Acknowledgement acknowledgement) {
		public RevisionAcceptance {
			parent = Objects.requireNonNull(parent, "parent");
			acknowledgement = Objects.requireNonNull(acknowledgement, "acknowledgement");
		}
	}
}
