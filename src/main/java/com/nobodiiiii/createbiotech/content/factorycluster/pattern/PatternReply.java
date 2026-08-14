package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

enum PatternReplyStatus {
	MATCH, NOT_FOUND, INVALID_LIBRARY
}

public record PatternReply(UUID queryId, long generation, PatternReplyStatus status,
	@Nullable PatternRecord pattern) {
	public PatternReply {
		queryId = Objects.requireNonNull(queryId, "queryId");
		status = Objects.requireNonNull(status, "status");
		if ((status == PatternReplyStatus.MATCH) != (pattern != null))
			throw new IllegalArgumentException("Only match replies carry a pattern");
	}
}
