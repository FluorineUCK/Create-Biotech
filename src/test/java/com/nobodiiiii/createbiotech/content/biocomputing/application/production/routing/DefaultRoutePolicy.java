package com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


/** Deterministic AE-like overflow with a durable per-priority round-robin cursor. */
public final class DefaultRoutePolicy {
	private static final Comparator<RouteCandidate> ORDER = Comparator
		.comparingInt(RouteCandidate::effectivePriority).reversed()
		.thenComparingInt(candidate -> candidate.availability().order())
		.thenComparing(RouteCandidate.STABLE_IDENTITY);

	private final Map<Integer, RouteIdentity> lastSelectedByPriority;

	public DefaultRoutePolicy() {
		this(new CursorState(Map.of()));
	}

	public DefaultRoutePolicy(CursorState cursorState) {
		Objects.requireNonNull(cursorState, "cursorState");
		lastSelectedByPriority = new HashMap<>(cursorState.lastSelectedByPriority());
	}

	public Decision select(List<RouteCandidate> candidates) {
		List<RouteCandidate> ordered = ordered(candidates);
		if (ordered.isEmpty())
			return Decision.none(RouteCandidate.Blocker.NONE);

		int highestPriority = ordered.getFirst().effectivePriority();
		for (int start = 0; start < ordered.size();) {
			int priority = ordered.get(start).effectivePriority();
			int end = start + 1;
			while (end < ordered.size() && ordered.get(end).effectivePriority() == priority)
				end++;
			List<RouteCandidate> priorityGroup = ordered.subList(start, end);
			List<RouteCandidate> available = priorityGroup.stream()
				.filter(candidate -> candidate.availability() == RouteCandidate.Availability.AVAILABLE)
				.sorted(RouteCandidate.STABLE_IDENTITY).toList();
			if (!available.isEmpty()) {
				RouteCandidate winner = nextAfterCursor(priority, available);
				if (!lastSelectedByPriority.containsKey(priority)
					&& lastSelectedByPriority.size() >= CursorState.MAX_PRIORITIES)
					throw new IllegalStateException("Route cursor state exceeds bound");
				lastSelectedByPriority.put(priority, RouteIdentity.of(winner));
				return new Decision(Optional.of(winner), RouteCandidate.Blocker.NONE,
					priority < highestPriority);
			}
			Optional<RouteCandidate.Blocker> hardBlocker = priorityGroup.stream()
				.map(RouteCandidate::blocker)
				.filter(blocker -> blocker != RouteCandidate.Blocker.NONE)
				.sorted().findFirst();
			if (hardBlocker.isPresent())
				return Decision.none(hardBlocker.orElseThrow());
			if (priorityGroup.stream().anyMatch(candidate ->
				candidate.availability() != RouteCandidate.Availability.BUSY))
				return Decision.none(RouteCandidate.Blocker.NONE);
			start = end;
		}
		return Decision.none(RouteCandidate.Blocker.NONE);
	}

	private RouteCandidate nextAfterCursor(int priority, List<RouteCandidate> available) {
		RouteIdentity cursor = lastSelectedByPriority.get(priority);
		if (cursor == null)
			return available.getFirst();
		for (RouteCandidate candidate : available)
			if (RouteIdentity.ORDER.compare(RouteIdentity.of(candidate), cursor) > 0)
				return candidate;
		return available.getFirst();
	}

	public CursorState cursorState() {
		return new CursorState(lastSelectedByPriority);
	}

	public static List<RouteCandidate> ordered(List<RouteCandidate> candidates) {
		List<RouteCandidate> copy = new ArrayList<>(List.copyOf(
			Objects.requireNonNull(candidates, "candidates")));
		copy.sort(ORDER);
		return List.copyOf(copy);
	}

	public record RouteIdentity(java.util.UUID libraryId, java.util.UUID patternId,
		String providerKey) {
		public static final int MAX_PROVIDER_KEY_LENGTH = 4096;
		private static final Comparator<RouteIdentity> ORDER = Comparator
			.comparing(RouteIdentity::libraryId)
			.thenComparing(RouteIdentity::patternId)
			.thenComparing(RouteIdentity::providerKey);
		public RouteIdentity {
			Objects.requireNonNull(libraryId); Objects.requireNonNull(patternId);
			providerKey = Objects.requireNonNull(providerKey).trim();
			if (providerKey.isEmpty() || providerKey.length() > MAX_PROVIDER_KEY_LENGTH)
				throw new IllegalArgumentException("Invalid provider key");
		}
		static RouteIdentity of(RouteCandidate candidate) {
			return new RouteIdentity(candidate.libraryId(), candidate.patternId(), candidate.providerKey());
		}
	}

	public record CursorState(Map<Integer, RouteIdentity> lastSelectedByPriority) {
		public static final int MAX_PRIORITIES = 256;
		public CursorState {
			lastSelectedByPriority = Map.copyOf(Objects.requireNonNull(lastSelectedByPriority,
				"lastSelectedByPriority"));
			if (lastSelectedByPriority.size() > MAX_PRIORITIES)
				throw new IllegalArgumentException("Route cursor state exceeds bound");
			lastSelectedByPriority.values().forEach(value -> Objects.requireNonNull(value,
				"route cursor"));
		}
	}

	public record Decision(Optional<RouteCandidate> winner, RouteCandidate.Blocker blocker,
		boolean overflowed) {
		public Decision {
			winner = Objects.requireNonNull(winner, "winner");
			blocker = Objects.requireNonNull(blocker, "blocker");
			if (winner.isPresent() && blocker != RouteCandidate.Blocker.NONE)
				throw new IllegalArgumentException("A selected route cannot be blocked");
		}

		private static Decision none(RouteCandidate.Blocker blocker) {
			return new Decision(Optional.empty(), blocker, false);
		}
	}
}
