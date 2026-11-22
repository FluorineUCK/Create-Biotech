package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.util.Objects;
import java.util.UUID;

/** The complete immutable idempotency and fencing key for one remote material request. */
public record RemoteRequestKey(UUID requestId, UUID runId, long fence,
	long sourceGeneration, long agentGeneration) {
	public RemoteRequestKey {
		requestId = Objects.requireNonNull(requestId, "requestId");
		runId = Objects.requireNonNull(runId, "runId");
		if (fence < 0 || sourceGeneration < 0 || agentGeneration < 0)
			throw new IllegalArgumentException("Remote request fences cannot be negative");
	}
}
