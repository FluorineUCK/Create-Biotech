package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

/** Durable exact request fingerprint for one ordinary priority-lease process. */
public record PriorityLeaseAssociation(UUID processId, UUID requestId, UUID runId, long fence,
	UUID sourceClusterId, UUID agentId, UUID agentOwnerClusterId, CreateRouteKey targetRoute,
	UUID patternId, InputMode mode, long multiplier, long enqueueSequence, int requestedPriority,
	long sourceMaterialSnapshotGeneration) {
	public PriorityLeaseAssociation {
		processId = Objects.requireNonNull(processId, "processId");
		requestId = Objects.requireNonNull(requestId, "requestId");
		runId = Objects.requireNonNull(runId, "runId");
		sourceClusterId = Objects.requireNonNull(sourceClusterId, "sourceClusterId");
		agentId = Objects.requireNonNull(agentId, "agentId");
		agentOwnerClusterId = Objects.requireNonNull(agentOwnerClusterId, "agentOwnerClusterId");
		targetRoute = Objects.requireNonNull(targetRoute, "targetRoute");
		patternId = Objects.requireNonNull(patternId, "patternId");
		mode = Objects.requireNonNull(mode, "mode");
		if (fence < 0 || multiplier <= 0 || enqueueSequence < 0
			|| sourceMaterialSnapshotGeneration < 0)
			throw new IllegalArgumentException("invalid priority lease fingerprint");
	}

	public static PriorityLeaseAssociation from(UUID processId, ProductionRequestTicket ticket,
		UUID agentOwnerClusterId) {
		return new PriorityLeaseAssociation(processId, ticket.requestId(), ticket.runId(),
			ticket.fence(), ticket.sourceClusterId(), ticket.agentId(), agentOwnerClusterId,
			ticket.targetRoute(), ticket.patternId(), ticket.mode(), ticket.multiplier(),
			ticket.enqueueSequence(), ticket.requestedQueuePriority(),
			ticket.sourceMaterialSnapshotGeneration());
	}

	public boolean matches(ProductionRequestTicket ticket, UUID agentOwnerClusterId) {
		return requestId.equals(ticket.requestId()) && runId.equals(ticket.runId())
			&& fence == ticket.fence() && sourceClusterId.equals(ticket.sourceClusterId())
			&& agentId.equals(ticket.agentId())
			&& this.agentOwnerClusterId.equals(agentOwnerClusterId)
			&& targetRoute.equals(ticket.targetRoute()) && patternId.equals(ticket.patternId())
			&& mode == ticket.mode() && multiplier == ticket.multiplier()
			&& enqueueSequence == ticket.enqueueSequence()
			&& requestedPriority == ticket.requestedQueuePriority()
			&& sourceMaterialSnapshotGeneration == ticket.sourceMaterialSnapshotGeneration();
	}
}
