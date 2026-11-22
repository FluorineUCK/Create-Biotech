package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import java.util.Objects;
import java.util.UUID;

/** Production search request for exactly one Library; not the generic Computer communication protocol. */
public record PatternQuery(UUID queryId, UUID requesterComputerId, UUID logisticsId,
	UUID libraryId, StackKey requestedStackKey, long queryGeneration, int pageCursor,
	int maxReplyBatch, long formationGeneration, long searchGeneration, UUID attemptId) {
	public static final int MAX_REPLY_BATCH = 256;
	private static final UUID LEGACY_UNADDRESSED_LIBRARY = new UUID(0, 0);

	public PatternQuery {
		queryId = Objects.requireNonNull(queryId, "queryId");
		requesterComputerId = Objects.requireNonNull(requesterComputerId, "requesterComputerId");
		logisticsId = Objects.requireNonNull(logisticsId, "logisticsId");
		libraryId = Objects.requireNonNull(libraryId, "libraryId");
		requestedStackKey = Objects.requireNonNull(requestedStackKey, "requestedStackKey");
		attemptId = Objects.requireNonNull(attemptId, "attemptId");
		if (queryGeneration < 0)
			throw new IllegalArgumentException("Query generation cannot be negative");
		if (formationGeneration < 0 || searchGeneration < 0 || pageCursor < 0)
			throw new IllegalArgumentException("Page cursor cannot be negative");
		if (maxReplyBatch <= 0 || maxReplyBatch > MAX_REPLY_BATCH)
			throw new IllegalArgumentException("Maximum reply batch must be in [1, "
				+ MAX_REPLY_BATCH + "]");
	}

	public PatternQuery(UUID queryId, UUID requesterComputerId, UUID logisticsId,
		UUID libraryId, StackKey requestedStackKey, long queryGeneration, int pageCursor,
		int maxReplyBatch) {
		this(queryId, requesterComputerId, logisticsId, libraryId, requestedStackKey,
			queryGeneration, pageCursor, maxReplyBatch, 0, 0, queryId);
	}

	/** Compatibility for persisted pre-directory local requests. */
	public PatternQuery(UUID queryId, UUID requesterComputerId, UUID logisticsId,
		StackKey requestedOutput, long passGeneration, int cursor) {
		this(queryId, requesterComputerId, logisticsId, LEGACY_UNADDRESSED_LIBRARY,
			requestedOutput, Math.max(0, passGeneration), cursor, 1, 0, 0, queryId);
	}

	public PatternQuery restart(long generation) {
		return new PatternQuery(queryId, requesterComputerId, logisticsId, libraryId,
			requestedStackKey, generation, 0, maxReplyBatch, formationGeneration,
			searchGeneration, attemptId);
	}

	public PatternQuery continueAt(int nextCursor) {
		return new PatternQuery(queryId, requesterComputerId, logisticsId, libraryId,
			requestedStackKey, queryGeneration, nextCursor, maxReplyBatch,
			formationGeneration, searchGeneration, attemptId);
	}

	/** Transitional accessors used by the Pattern index codec. */
	public StackKey requestedOutput() { return requestedStackKey; }
	public long passGeneration() { return queryGeneration; }
	public int cursor() { return pageCursor; }
	public boolean targetsLibrary() { return !LEGACY_UNADDRESSED_LIBRARY.equals(libraryId); }
}
