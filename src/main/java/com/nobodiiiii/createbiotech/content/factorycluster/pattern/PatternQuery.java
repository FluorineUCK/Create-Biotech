package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.Objects;
import java.util.UUID;

public record PatternQuery(UUID queryId, UUID requesterComputerId, UUID logisticsId,
	StackKey requestedOutput, long passGeneration, int cursor) {
	public PatternQuery {
		queryId = Objects.requireNonNull(queryId, "queryId");
		requesterComputerId = Objects.requireNonNull(requesterComputerId, "requesterComputerId");
		logisticsId = Objects.requireNonNull(logisticsId, "logisticsId");
		requestedOutput = Objects.requireNonNull(requestedOutput, "requestedOutput");
		if (cursor < 0)
			throw new IllegalArgumentException("Cursor cannot be negative");
	}

	public PatternQuery restart(long generation) {
		return new PatternQuery(queryId, requesterComputerId, logisticsId,
			requestedOutput, generation, 0);
	}
}
