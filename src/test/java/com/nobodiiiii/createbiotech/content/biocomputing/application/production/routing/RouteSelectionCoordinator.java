package com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Collector lifecycle for one production context. The host supplies its persisted epoch,
 * sequence and cursor; this application never sees engine processes or world objects.
 * Calls are serialized by the owning host. Active feeds are transient, not durable jobs.
 */
public final class RouteSelectionCoordinator {
	public static final int MAX_SCOPE_CAPACITY = 4096;
	private static final int MAX_ACTIVE_SELECTIONS = 256;
	private final UUID epochId;
	private final int capacity;
	private final ProductionRouteTable table;
	private final Map<UUID, ActiveSelection> activeByQuery = new LinkedHashMap<>();
	private long nextAttemptSequence;

	public RouteSelectionCoordinator(UUID epochId, long nextAttemptSequence,
		DefaultRoutePolicy.CursorState cursor, int scopeCapacity) {
		this.epochId = Objects.requireNonNull(epochId, "epochId");
		if (nextAttemptSequence < 0 || scopeCapacity <= 0 || scopeCapacity > MAX_SCOPE_CAPACITY)
			throw new IllegalArgumentException("invalid route-selection context");
		this.nextAttemptSequence = nextAttemptSequence;
		capacity = Math.min(scopeCapacity, MAX_ACTIVE_SELECTIONS);
		table = new ProductionRouteTable(Objects.requireNonNull(cursor, "cursor"));
	}

	public boolean begin(RouteSelectionRequest request) {
		request = Objects.requireNonNull(request, "request");
		RouteSelectionScope scope = request.scope();
		if (!epochId.equals(scope.epochId())) return false;
		ActiveSelection active = activeByQuery.get(scope.queryId());
		if (active != null) return active.request.equals(request);
		if (!nextAttempt(scope) || activeByQuery.size() >= capacity) return false;
		activeByQuery.put(scope.queryId(), new ActiveSelection(request));
		nextAttemptSequence++;
		return true;
	}

	/** Explicit compare-and-set replacement; never lets an old collector reverse a newer attempt. */
	public boolean supersede(RouteSelectionScope expectedPrior, RouteSelectionRequest nextRequest) {
		Objects.requireNonNull(expectedPrior, "expectedPrior");
		nextRequest = Objects.requireNonNull(nextRequest, "nextRequest");
		RouteSelectionScope next = nextRequest.scope();
		if (active(expectedPrior) == null || !epochId.equals(next.epochId())
			|| !expectedPrior.queryId().equals(next.queryId()) || expectedPrior.equals(next)
			|| next.searchGeneration() < expectedPrior.searchGeneration()
			|| next.searchGeneration() == expectedPrior.searchGeneration()
				&& !next.requestedStackKey().equals(expectedPrior.requestedStackKey())
			|| !nextAttempt(next)) return false;
		table.removeScope(expectedPrior);
		activeByQuery.put(next.queryId(), new ActiveSelection(nextRequest));
		nextAttemptSequence++;
		return true;
	}

	public boolean replaceSources(RouteSelectionScope scope, RouteCandidate.ProviderKind kind,
		List<ProductionRouteTable.ProviderSource> feed) {
		Objects.requireNonNull(kind, "kind");
		feed = List.copyOf(Objects.requireNonNull(feed, "feed"));
		ActiveSelection active = active(scope);
		if (active == null) return false;
		if (active.completed.contains(kind))
			return table.snapshot(scope).stream().filter(source -> source.providerKind() == kind).toList().equals(feed);
		table.replaceProviderKind(scope, kind, feed);
		return true;
	}

	public boolean complete(RouteSelectionScope scope, RouteCandidate.ProviderKind kind) {
		Objects.requireNonNull(kind, "kind");
		ActiveSelection active = active(scope);
		if (active == null) return false;
		if (active.completed.contains(kind)) return true;
		if (active.request.requiredCollectors().contains(kind)
			&& table.snapshot(scope).stream().noneMatch(source -> source.providerKind() == kind)) return false;
		active.completed.add(kind);
		return true;
	}

	/** A failed bounded selection leaves the active barrier and all source facts available for CAS retirement. */
	public Optional<DefaultRoutePolicy.Decision> select(RouteSelectionScope scope) {
		ActiveSelection active = active(scope);
		if (active == null || active.completed.size() != RouteCandidate.ProviderKind.values().length)
			return Optional.empty();
		DefaultRoutePolicy.Decision decision = table.select(scope);
		table.removeScope(scope);
		activeByQuery.remove(scope.queryId());
		return Optional.of(decision);
	}

	public List<ProductionRouteTable.ProviderSource> sourceSnapshot(RouteSelectionScope scope) {
		return table.snapshot(Objects.requireNonNull(scope, "scope"));
	}

	/** Cancels transient collection without rewinding the persistent stale-attempt fence or fairness cursor. */
	public void retireQuery(UUID queryId) {
		ActiveSelection removed = activeByQuery.remove(Objects.requireNonNull(queryId, "queryId"));
		if (removed != null) table.removeScope(removed.request.scope());
	}

	public long nextAttemptSequence() { return nextAttemptSequence; }
	public DefaultRoutePolicy.CursorState cursorState() { return table.cursorState(); }

	private boolean nextAttempt(RouteSelectionScope scope) {
		return nextAttemptSequence != Long.MAX_VALUE && scope.attemptSequence() == nextAttemptSequence;
	}
	private ActiveSelection active(RouteSelectionScope scope) {
		Objects.requireNonNull(scope, "scope");
		ActiveSelection active = activeByQuery.get(scope.queryId());
		return active != null && active.request.scope().equals(scope) ? active : null;
	}
	private static final class ActiveSelection {
		private final RouteSelectionRequest request;
		private final EnumSet<RouteCandidate.ProviderKind> completed = EnumSet.noneOf(RouteCandidate.ProviderKind.class);
		private ActiveSelection(RouteSelectionRequest request) { this.request = request; }
	}
}
