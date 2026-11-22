package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

/** Immutable pure-computation lease. Goal and child lineage remain at {@code sourceClusterId}. */
public record RemoteProcessLease(UUID leaseId, UUID sourceGoalId, UUID sourceProcessId,
	UUID sourceClusterId, UUID receiverClusterId, long sourceGeneration,
	long receiverGeneration, ProductionCapability requiredCapability,
	RemoteExpansionRequest expansionRequest, long revisionFence) {

	public RemoteProcessLease {
		leaseId = Objects.requireNonNull(leaseId, "leaseId");
		sourceGoalId = Objects.requireNonNull(sourceGoalId, "sourceGoalId");
		sourceProcessId = Objects.requireNonNull(sourceProcessId, "sourceProcessId");
		sourceClusterId = Objects.requireNonNull(sourceClusterId, "sourceClusterId");
		receiverClusterId = Objects.requireNonNull(receiverClusterId, "receiverClusterId");
		if (sourceGeneration < 0 || receiverGeneration < 0 || revisionFence < 0)
			throw new IllegalArgumentException("lease fences cannot be negative");
		requiredCapability = Objects.requireNonNull(requiredCapability, "requiredCapability");
		expansionRequest = Objects.requireNonNull(expansionRequest, "expansionRequest");
		if (!sourceGoalId.equals(expansionRequest.sourceGoalId())
			|| !sourceProcessId.equals(expansionRequest.parentProcessId())
			|| revisionFence != expansionRequest.parentStateGeneration())
			throw new IllegalArgumentException("lease does not fence its expansion request");
	}

	public UUID goalOwnerClusterId() {
		return sourceClusterId;
	}

	String fingerprintMaterial() {
		return leaseId + "|" + sourceGoalId + "|" + sourceProcessId + "|"
			+ sourceClusterId + "|" + receiverClusterId + "|" + sourceGeneration + "|"
			+ receiverGeneration + "|" + requiredCapability + "|" + revisionFence + "|"
			+ expansionRequest.fingerprint();
	}
}
