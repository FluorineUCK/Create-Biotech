package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;

import java.util.Objects;
import java.util.UUID;

import java.util.List;

/** One bounded Library-owned traversal reply; queried Libraries terminate with GROUP_END. */
public record PatternReply(UUID queryId, UUID requesterComputerId, UUID logisticsId,
	UUID libraryId, long generation, Status status, List<PatternRecord> matches,
	int nextPageCursor, long formationGeneration, long searchGeneration, UUID attemptId) {
	private static final UUID LEGACY_UNADDRESSED_LIBRARY = new UUID(0, 0);
	public PatternReply {
		queryId = Objects.requireNonNull(queryId, "queryId");
		requesterComputerId = Objects.requireNonNull(requesterComputerId, "requesterComputerId");
		logisticsId = Objects.requireNonNull(logisticsId, "logisticsId");
		libraryId = Objects.requireNonNull(libraryId, "libraryId");
		attemptId = Objects.requireNonNull(attemptId, "attemptId");
		if (generation < 0 || formationGeneration < 0 || searchGeneration < 0)
			throw new IllegalArgumentException("Reply generation cannot be negative");
		status = Objects.requireNonNull(status, "status");
		matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
		if ((status == Status.MATCH_BATCH) != !matches.isEmpty())
			throw new IllegalArgumentException("Only non-empty match batches carry Patterns");
		if (matches.size() > PatternQuery.MAX_REPLY_BATCH)
			throw new IllegalArgumentException("Reply batch exceeds bound");
		if (nextPageCursor < 0)
			throw new IllegalArgumentException("Next page cursor cannot be negative");
	}

	public PatternReply(UUID queryId, UUID requesterComputerId, UUID logisticsId,
		UUID libraryId, long generation, Status status, List<PatternRecord> matches,
		int nextPageCursor) {
		this(queryId, requesterComputerId, logisticsId, libraryId, generation, status,
			matches, nextPageCursor, 0, 0, queryId);
	}

	/** Compatibility constructor for persisted pre-directory local queues. */
	public PatternReply(UUID queryId, UUID requesterComputerId, UUID logisticsId,
		long generation, Status status, PatternRecord pattern) {
		this(queryId, requesterComputerId, logisticsId, LEGACY_UNADDRESSED_LIBRARY,
			generation, status,
			pattern == null ? List.of() : List.of(pattern), 0, 0, 0, queryId);
	}

	/** Transitional single-match view for older diagnostics. */
	public PatternRecord pattern() {
		return matches.isEmpty() ? null : matches.getFirst();
	}

	public static PatternReply matchBatch(PatternQuery query, List<PatternRecord> matches,
		int nextPageCursor) {
		Objects.requireNonNull(query, "query");
		if (Objects.requireNonNull(matches, "matches").size() > query.maxReplyBatch())
			throw new IllegalArgumentException("Match batch exceeds query maximum");
		return from(query, Status.MATCH_BATCH, matches, nextPageCursor);
	}

	public static PatternReply groupEnd(PatternQuery query, int nextPageCursor) {
		return from(query, Status.GROUP_END, List.of(), nextPageCursor);
	}

	public static PatternReply restartRequired(PatternQuery query, long currentGeneration) {
		return new PatternReply(query.queryId(), query.requesterComputerId(), query.logisticsId(),
			query.libraryId(), currentGeneration, Status.RESTART_REQUIRED, List.of(), 0,
			query.formationGeneration(), query.searchGeneration(), query.attemptId());
	}

	public static PatternReply unavailable(PatternQuery query) {
		return from(query, Status.UNAVAILABLE, List.of(), query.pageCursor());
	}

	private static PatternReply from(PatternQuery query, Status status,
		List<PatternRecord> matches, int cursor) {
		Objects.requireNonNull(query, "query");
		return new PatternReply(query.queryId(), query.requesterComputerId(), query.logisticsId(),
			query.libraryId(), query.queryGeneration(), status, matches, cursor,
			query.formationGeneration(), query.searchGeneration(), query.attemptId());
	}

	public enum Status { MATCH_BATCH, GROUP_END, RESTART_REQUIRED, UNAVAILABLE }
}
