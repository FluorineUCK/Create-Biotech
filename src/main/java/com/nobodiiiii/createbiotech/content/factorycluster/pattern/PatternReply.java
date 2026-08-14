package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

public record PatternReply(UUID queryId, UUID requesterComputerId, UUID logisticsId,
	long generation, PatternReplyStatus status, @Nullable PatternRecord pattern) {
	public PatternReply {
		queryId = Objects.requireNonNull(queryId, "queryId");
		requesterComputerId = Objects.requireNonNull(requesterComputerId, "requesterComputerId");
		logisticsId = Objects.requireNonNull(logisticsId, "logisticsId");
		status = Objects.requireNonNull(status, "status");
		if ((status == PatternReplyStatus.MATCH) != (pattern != null))
			throw new IllegalArgumentException("Only match replies carry a pattern");
	}
}
