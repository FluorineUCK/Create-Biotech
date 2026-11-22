package com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing;

import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/**
 * Exact immutable identity of one route-selection attempt. The sequence is
 * Cluster-global within the epoch and is carried by every collector feed.
 */
public record RouteSelectionScope(UUID epochId, UUID queryId, StackKey requestedStackKey,
	long searchGeneration, long attemptSequence, UUID attemptId) {
	public RouteSelectionScope {
		epochId = Objects.requireNonNull(epochId, "epochId");
		queryId = Objects.requireNonNull(queryId, "queryId");
		requestedStackKey = Objects.requireNonNull(requestedStackKey, "requestedStackKey");
		attemptId = Objects.requireNonNull(attemptId, "attemptId");
		if (searchGeneration < 0 || attemptSequence < 0)
			throw new IllegalArgumentException("negative route-selection generation");
	}
}
