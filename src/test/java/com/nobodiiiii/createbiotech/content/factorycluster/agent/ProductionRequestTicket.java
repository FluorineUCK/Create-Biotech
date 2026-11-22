package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

/** Source-owned request/run lineage indexed by the Agent's shared pending queue. */
public record ProductionRequestTicket(UUID requestId, UUID runId, long fence,
	UUID sourceClusterId, UUID agentId, CreateRouteKey targetRoute, UUID patternId,
	InputMode mode, long multiplier, long enqueueSequence, int requestedQueuePriority,
	long sourceMaterialSnapshotGeneration) {
	public ProductionRequestTicket {
		requestId = Objects.requireNonNull(requestId, "requestId");
		runId = Objects.requireNonNull(runId, "runId");
		sourceClusterId = Objects.requireNonNull(sourceClusterId, "sourceClusterId");
		agentId = Objects.requireNonNull(agentId, "agentId");
		targetRoute = Objects.requireNonNull(targetRoute, "targetRoute");
		patternId = Objects.requireNonNull(patternId, "patternId");
		mode = Objects.requireNonNull(mode, "mode");
		if (fence < 0 || enqueueSequence < 0 || sourceMaterialSnapshotGeneration < 0)
			throw new IllegalArgumentException("Ticket fences and generations cannot be negative");
		if (multiplier <= 0)
			throw new IllegalArgumentException("Ticket multiplier must be positive");
	}

	public ProductionRequestTicket withPriority(int priority) {
		return new ProductionRequestTicket(requestId, runId, fence, sourceClusterId, agentId,
			targetRoute, patternId, mode, multiplier, enqueueSequence, priority,
			sourceMaterialSnapshotGeneration);
	}

	public ProductionRequestTicket withEnqueueSequence(long sequence) {
		return new ProductionRequestTicket(requestId, runId, fence, sourceClusterId, agentId,
			targetRoute, patternId, mode, multiplier, sequence, requestedQueuePriority,
			sourceMaterialSnapshotGeneration);
	}

	public ProductionRequestTicket withMaterialSnapshotGeneration(long generation) {
		return new ProductionRequestTicket(requestId, runId, fence, sourceClusterId, agentId,
			targetRoute, patternId, mode, multiplier, enqueueSequence, requestedQueuePriority,
			generation);
	}
}
