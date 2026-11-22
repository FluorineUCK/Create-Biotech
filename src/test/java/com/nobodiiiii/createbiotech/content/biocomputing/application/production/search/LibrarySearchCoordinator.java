package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.DefaultRoutePolicy;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.PatternPriorityPolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Coordinates priority waves while each selected Library owns its own traversal. */
public final class LibrarySearchCoordinator {
	private final LibraryDirectory directory;
	private final Map<UUID, Match> matches = new LinkedHashMap<>();
	private final Map<UUID, Match> speculativeBranches = new LinkedHashMap<>();
	private final Map<UUID, Long> queriedGenerations = new HashMap<>();
	private final Map<UUID, Long> requiredCatalogGenerations = new HashMap<>();
	private final Map<UUID, Long> requiredCatalogFormations = new HashMap<>();
	private final Map<UUID, Long> requiredDirectoryRevisions = new HashMap<>();
	private final Map<UUID, UUID> attempts = new HashMap<>();
	private final Map<UUID, IssuedFence> issuedFences = new HashMap<>();
	private final Map<UUID, ReservationState> reservations = new LinkedHashMap<>();
	private long nextAttemptSequence;
	private final Set<UUID> issuedLibraries = new LinkedHashSet<>();
	private List<List<LibraryRegistration>> priorityGroups = List.of();
	private int groupIndex = -1;
	private MutableWave wave;
	private SearchRequest request;
	private Status status = Status.NOT_FOUND;

	public LibrarySearchCoordinator(LibraryDirectory directory) {
		this.directory = Objects.requireNonNull(directory, "directory");
	}

	public Update begin(SearchRequest nextRequest) {
		request = Objects.requireNonNull(nextRequest, "request");
		matches.clear();
		speculativeBranches.clear();
		queriedGenerations.clear();
		requiredCatalogGenerations.clear();
		requiredCatalogFormations.clear();
		requiredDirectoryRevisions.clear();
		attempts.clear();
		issuedFences.clear();
		reservations.clear();
		nextAttemptSequence = 0;
		issuedLibraries.clear();
		priorityGroups = group(directory.knownPeers());
		groupIndex = -1;
		wave = null;
		return openNextGroup(request.residualAlchemistCapacity());
	}

	public Update refresh(int residualAlchemistCapacity) {
		requireActiveRequest();
		if (wave == null || status == Status.COMPLETE || status == Status.NOT_FOUND)
			return emptyUpdate(false);
		Reconcile reconciled = reconcileIssuedLibraries();
		List<PatternQuery> queries = new ArrayList<>(reconciled.queries());
		queries.addAll(issueNewlyAvailableLibraries());
		status = statusForWave();
		return new Update(status, queries, List.of(), Optional.empty(),
			Optional.empty(), reconciled.cancelled(), false);
	}

	public Update accept(PatternReply reply, int residualAlchemistCapacity) {
		requireActiveRequest();
		reply = Objects.requireNonNull(reply, "reply");
		// A completed search cannot reopen or reconcile into a new attempt on late delivery.
		if (status == Status.COMPLETE || status == Status.NOT_FOUND)
			return emptyUpdate(true);
		Reconcile reconciled = reconcileIssuedLibraries();
		IssuedFence issuedFence = issuedFences.get(reply.libraryId());
		if (!reply.queryId().equals(request.queryId()) || reply.searchGeneration() != request.generation()
			|| wave == null
			|| !wave.libraryIds.contains(reply.libraryId()) || issuedFence == null)
			return staleUpdate(reconciled);
		Long expectedGeneration = queriedGenerations.get(reply.libraryId());
		if (expectedGeneration == null || !issuedFence.accepts(reply))
			return staleUpdate(reconciled);
		if (reply.status() == PatternReply.Status.RESTART_REQUIRED) {
			if (reply.generation() <= expectedGeneration)
				return emptyUpdate(true);
			Set<UUID> cancelled = invalidateAttempt(reply.libraryId());
			waitForLibraryRestart(reply.libraryId(), reply.formationGeneration(), reply.generation());
			status = Status.WAITING_LIBRARY;
			return new Update(status, List.of(), List.of(),
				Optional.empty(), Optional.empty(), cancelled, false);
		}
		if (reply.generation() != expectedGeneration)
			return emptyUpdate(true);

		List<SpeculativeAllocation> allocations = new ArrayList<>();
		switch (reply.status()) {
			case MATCH_BATCH -> {
				for (PatternRecord pattern : reply.matches()) {
					Match match = new Match(reply.libraryId(), pattern);
					matches.putIfAbsent(pattern.patternId(), match);
					int availableCapacity = Math.max(0, Math.min(residualAlchemistCapacity,
						request.residualAlchemistCapacity()) - reservations.size());
					if (availableCapacity > 0
						&& speculativeBranches.values().stream()
							.noneMatch(existing -> existing.pattern.patternId().equals(pattern.patternId()))) {
						UUID branchId = speculationBranchId(request.queryId(), reply.libraryId(),
							pattern.patternId(), reply.attemptId());
						speculativeBranches.put(branchId, match);
						reservations.put(branchId, ReservationState.PENDING);
						allocations.add(new SpeculativeAllocation(branchId, reply.libraryId(),
							pattern.patternId()));
					}
				}
			}
			case GROUP_END -> wave.awaitingGroupEnd.remove(reply.libraryId());
			case RESTART_REQUIRED -> throw new IllegalStateException("handled before generation check");
			case UNAVAILABLE -> {
				Set<UUID> cancelled = invalidateAttempt(reply.libraryId());
				wave.awaitingGroupEnd.remove(reply.libraryId());
				wave.waitingLibraries.add(reply.libraryId());
				issuedLibraries.remove(reply.libraryId());
				queriedGenerations.remove(reply.libraryId());
				requiredDirectoryRevisions.put(reply.libraryId(),
					directory.publicationRevision(reply.libraryId()) + 1);
				status = Status.WAITING_LIBRARY;
				return new Update(status, List.of(), allocations, Optional.empty(),
					Optional.empty(), cancelled, false);
			}
		}

		if (!wave.awaitingGroupEnd.isEmpty() || !wave.waitingLibraries.isEmpty()) {
			status = statusForWave();
			return new Update(status, List.of(), allocations, Optional.empty(),
				Optional.empty(), Set.of(), false);
		}
		if (matches.isEmpty()) {
			Update next = openNextGroup(residualAlchemistCapacity);
			return next.withAllocations(allocations);
		}
		return closeMatchedGroup(allocations);
	}

	public Optional<LibrarySearchWave> currentWave() {
		return wave == null ? Optional.empty() : Optional.of(wave.snapshot());
	}

	public Optional<UUID> patternForBranch(UUID branchId) {
		Match match = speculativeBranches.get(Objects.requireNonNull(branchId, "branchId"));
		return match == null ? Optional.empty() : Optional.of(match.pattern.patternId());
	}

	public void acknowledgeSpeculation(UUID branchId) {
		if (reservations.replace(Objects.requireNonNull(branchId), ReservationState.ACKNOWLEDGED) == null)
			throw new IllegalArgumentException("unknown speculation branch");
	}

	/** Cancels a provisional allocation that the authoritative runtime could not place in a slot. */
	public void rejectSpeculation(UUID branchId) {
		branchId = Objects.requireNonNull(branchId, "branchId");
		if (reservations.remove(branchId) == null)
			throw new IllegalArgumentException("unknown speculation branch");
		speculativeBranches.remove(branchId);
	}

	public int outstandingSpeculation() { return reservations.size(); }

	private Update openNextGroup(int residualAlchemistCapacity) {
		groupIndex++;
		if (groupIndex >= priorityGroups.size()) {
			wave = null;
			status = Status.NOT_FOUND;
			return emptyUpdate(false);
		}
		List<LibraryRegistration> registrations = priorityGroups.get(groupIndex);
		wave = new MutableWave(registrations.getFirst().priority(), registrations.stream()
			.map(LibraryRegistration::libraryId).toList());
		matches.clear();
		speculativeBranches.clear();
		queriedGenerations.clear();
		requiredCatalogGenerations.clear();
		requiredCatalogFormations.clear();
		requiredDirectoryRevisions.clear();
		attempts.clear();
		issuedFences.clear();
		reservations.clear();
		issuedLibraries.clear();
		List<PatternQuery> queries = issueNewlyAvailableLibraries();
		status = statusForWave();
		return new Update(status, queries, List.of(), Optional.empty(), Optional.empty(),
			Set.of(), false);
	}

	private List<PatternQuery> issueNewlyAvailableLibraries() {
		List<PatternQuery> queries = new ArrayList<>();
		wave.waitingLibraries.clear();
		for (UUID libraryId : wave.libraryIds) {
			if (issuedLibraries.contains(libraryId))
				continue;
			LibraryRegistration registration = directory.find(libraryId).orElse(null);
			if (registration == null || !registration.queryableBy(request.requesterClusterId(),
				request.frequency())) {
				wave.waitingLibraries.add(libraryId);
				continue;
			}
			Long requiredGeneration = requiredCatalogGenerations.get(libraryId);
			Long requiredFormation = requiredCatalogFormations.get(libraryId);
			if (requiredFormation != null && requiredFormation != registration.formationGeneration()) {
				requiredCatalogFormations.remove(libraryId);
				requiredCatalogGenerations.remove(libraryId);
				requiredGeneration = null;
			}
			if (requiredGeneration != null
				&& registration.publicCatalogGeneration() < requiredGeneration) {
				wave.waitingLibraries.add(libraryId);
				continue;
			}
			Long requiredRevision = requiredDirectoryRevisions.get(libraryId);
			if (requiredRevision != null && directory.publicationRevision(libraryId) < requiredRevision) {
				wave.waitingLibraries.add(libraryId);
				continue;
			}
			UUID attemptId = branchId(request.queryId(), libraryId,
				new UUID(request.generation(), nextAttemptSequence++));
			PatternQuery query = new PatternQuery(request.queryId(), request.requesterComputerId(),
				request.logisticsId(), libraryId, request.requestedStackKey(),
				registration.publicCatalogGeneration(), 0, request.maximumReplyBatch(),
				registration.formationGeneration(), request.generation(), attemptId);
			queries.add(query);
			issuedLibraries.add(libraryId);
			wave.awaitingGroupEnd.add(libraryId);
			queriedGenerations.put(libraryId, registration.publicCatalogGeneration());
			attempts.put(libraryId, attemptId);
			issuedFences.put(libraryId, new IssuedFence(libraryId,
				registration.formationGeneration(), registration.publicCatalogGeneration(),
				request.generation(), attemptId, registration.loadState()));
			requiredCatalogGenerations.remove(libraryId);
			requiredCatalogFormations.remove(libraryId);
			requiredDirectoryRevisions.remove(libraryId);
		}
		return List.copyOf(queries);
	}

	private void waitForLibraryRestart(UUID libraryId, long requiredFormation, long requiredGeneration) {
		wave.awaitingGroupEnd.remove(libraryId);
		wave.waitingLibraries.add(libraryId);
		issuedLibraries.remove(libraryId);
		queriedGenerations.remove(libraryId);
		requiredCatalogGenerations.merge(libraryId, requiredGeneration, Math::max);
		requiredCatalogFormations.put(libraryId, requiredFormation);
		requiredDirectoryRevisions.put(libraryId, directory.publicationRevision(libraryId) + 1);
	}

	private Set<UUID> invalidateAttempt(UUID libraryId) {
		matches.entrySet().removeIf(entry -> entry.getValue().libraryId.equals(libraryId));
		Set<UUID> cancelled = new LinkedHashSet<>();
		for (Map.Entry<UUID, Match> entry : List.copyOf(speculativeBranches.entrySet())) {
			if (!entry.getValue().libraryId.equals(libraryId)) continue;
			cancelled.add(entry.getKey());
			speculativeBranches.remove(entry.getKey());
			reservations.remove(entry.getKey());
		}
		attempts.remove(libraryId);
		issuedFences.remove(libraryId);
		return Set.copyOf(cancelled);
	}

	private Reconcile reconcileIssuedLibraries() {
		if (wave == null) return Reconcile.EMPTY;
		Set<UUID> cancelled = new LinkedHashSet<>();
		boolean changed = false;
		for (IssuedFence fence : List.copyOf(issuedFences.values())) {
			LibraryRegistration current = directory.find(fence.libraryId()).orElse(null);
			boolean same = current != null && fence.sameDirectoryState(current)
				&& current.queryableBy(request.requesterClusterId(), request.frequency());
			if (same) continue;
			changed = true;
			cancelled.addAll(invalidateAttempt(fence.libraryId()));
			wave.awaitingGroupEnd.remove(fence.libraryId());
			issuedLibraries.remove(fence.libraryId());
			queriedGenerations.remove(fence.libraryId());
			requiredCatalogGenerations.remove(fence.libraryId());
			requiredCatalogFormations.remove(fence.libraryId());
			if (current == null || !current.queryableBy(request.requesterClusterId(),
				request.frequency())) {
				wave.waitingLibraries.add(fence.libraryId());
				requiredDirectoryRevisions.put(fence.libraryId(),
					directory.publicationRevision(fence.libraryId()) + 1);
			} else {
				wave.waitingLibraries.remove(fence.libraryId());
				requiredDirectoryRevisions.remove(fence.libraryId());
			}
		}
		List<PatternQuery> queries = changed ? issueNewlyAvailableLibraries() : List.of();
		return new Reconcile(queries, Set.copyOf(cancelled), changed);
	}

	private Update staleUpdate(Reconcile reconciled) {
		status = wave == null ? status : statusForWave();
		return new Update(status, reconciled.queries(), List.of(), Optional.empty(), Optional.empty(),
			reconciled.cancelled(), true);
	}

	private Update closeMatchedGroup(List<SpeculativeAllocation> allocations) {
		List<ProductionRouteTable.ProviderSource> sources = matches.values().stream()
			.map(this::routeSource).toList();
		status = Status.SELECTING_ROUTE;
		return new Update(status, List.of(), allocations, Optional.empty(), Optional.empty(),
			Set.of(), false, sources);
	}

	public Update completeRouteSelection(DefaultRoutePolicy.Decision decision) {
		requireActiveRequest(); Objects.requireNonNull(decision, "decision");
		if (status != Status.SELECTING_ROUTE)
			throw new IllegalStateException("No route selection is pending");
		if (decision.winner().isEmpty()) {
			Set<UUID> cancelled = Set.copyOf(speculativeBranches.keySet());
			speculativeBranches.clear();
			reservations.clear();
			matches.clear();
			wave = null;
			status = Status.NOT_FOUND;
			return new Update(status, List.of(), List.of(), Optional.empty(), Optional.empty(),
				cancelled, false, List.of());
		}
		RouteCandidate winner = decision.winner().orElseThrow();
		Optional<UUID> retained = speculativeBranches.entrySet().stream()
			.filter(entry -> entry.getValue().pattern.patternId().equals(winner.patternId()))
			.map(Map.Entry::getKey).findFirst();
		Set<UUID> cancelled = new LinkedHashSet<>(speculativeBranches.keySet());
		retained.ifPresent(cancelled::remove);
		for (UUID branch : cancelled) reservations.remove(branch);
		status = Status.COMPLETE;
		return new Update(status, List.of(), List.of(), Optional.of(winner), retained,
			Set.copyOf(cancelled), false, List.of());
	}

	private RouteCandidate routeCandidate(Match match) {
		LibraryRegistration library = directory.find(match.libraryId).orElseThrow();
		PatternPriorityPolicy.Resolution priority = PatternPriorityPolicy.resolve(
			match.pattern.priorityOverride(), library.priority(),
			request.coordination().authorizes(library.clusterId(), library.libraryId(),
				match.pattern.patternId(), library.publicCatalogGeneration()));
		return new RouteCandidate(match.libraryId, match.pattern.patternId(),
			match.pattern.patternId().toString(), RouteCandidate.ProviderKind.PATTERN,
			priority.effectivePriority(), RouteCandidate.Availability.AVAILABLE,
			RouteCandidate.Blocker.NONE);
	}

	private ProductionRouteTable.ProviderSource routeSource(Match match) {
		RouteCandidate candidate = routeCandidate(match);
		RouteSelectionScope scope = new RouteSelectionScope(request.epochId(), request.queryId(),
			request.requestedStackKey(), request.generation(), request.selectionAttemptSequence(),
			request.selectionAttemptId());
		return new ProductionRouteTable.ProviderSource(scope, candidate.libraryId(), candidate.patternId(),
			candidate.providerKey(), candidate.providerKind(), candidate.effectivePriority(),
			candidate.availability(), candidate.blocker());
	}

	private Status statusForWave() {
		return wave != null && !wave.waitingLibraries.isEmpty()
			? Status.WAITING_LIBRARY : Status.SEARCHING;
	}

	private Update emptyUpdate(boolean stale) {
		return new Update(status, List.of(), List.of(), Optional.empty(), Optional.empty(),
			Set.of(), stale);
	}

	private void requireActiveRequest() {
		if (request == null)
			throw new IllegalStateException("No Library search has begun");
	}

	private static List<List<LibraryRegistration>> group(
		List<LibraryRegistration> registrations) {
		List<List<LibraryRegistration>> groups = new ArrayList<>();
		for (LibraryRegistration registration : registrations) {
			if (groups.isEmpty() || groups.getLast().getFirst().priority() != registration.priority())
				groups.add(new ArrayList<>());
			groups.getLast().add(registration);
		}
		return groups.stream().map(List::copyOf).toList();
	}

	private static UUID branchId(UUID queryId, UUID libraryId, UUID patternId) {
		return UUID.nameUUIDFromBytes((queryId + "/" + libraryId + "/" + patternId)
			.getBytes(StandardCharsets.UTF_8));
	}

	private static UUID speculationBranchId(UUID queryId, UUID libraryId, UUID patternId,
		UUID attemptId) {
		return UUID.nameUUIDFromBytes((queryId + "/" + libraryId + "/" + patternId + "/" + attemptId)
			.getBytes(StandardCharsets.UTF_8));
	}

	public enum Status { SEARCHING, WAITING_LIBRARY, SELECTING_ROUTE, COMPLETE, NOT_FOUND }

	public record SearchRequest(UUID queryId, UUID requesterComputerId,
		UUID requesterClusterId, UUID epochId, UUID logisticsId, CommunicationFrequency frequency,
		StackKey requestedStackKey, long generation, long selectionAttemptSequence,
		UUID selectionAttemptId, int maximumReplyBatch,
		int residualAlchemistCapacity, PatternPriorityAccess coordination) {
		public SearchRequest {
			queryId = Objects.requireNonNull(queryId, "queryId");
			requesterComputerId = Objects.requireNonNull(requesterComputerId, "requesterComputerId");
			requesterClusterId = Objects.requireNonNull(requesterClusterId, "requesterClusterId");
			frequency = Objects.requireNonNull(frequency, "frequency");
			epochId = Objects.requireNonNull(epochId, "epochId");
			logisticsId = Objects.requireNonNull(logisticsId, "logisticsId");
			requestedStackKey = Objects.requireNonNull(requestedStackKey, "requestedStackKey");
			selectionAttemptId = Objects.requireNonNull(selectionAttemptId, "selectionAttemptId");
			coordination = Objects.requireNonNull(coordination, "coordination");
			if (generation < 0 || selectionAttemptSequence < 0
				|| maximumReplyBatch <= 0 || maximumReplyBatch > 256
				|| residualAlchemistCapacity < 0)
				throw new IllegalArgumentException("Invalid bounded Library search request");
		}
	}

	/** A provisional, data-only expansion request; the host must reserve real production resources. */
	public record SpeculativeAllocation(UUID branchId, UUID libraryId, UUID patternId) {
		public SpeculativeAllocation {
			Objects.requireNonNull(branchId, "branchId");
			Objects.requireNonNull(libraryId, "libraryId");
			Objects.requireNonNull(patternId, "patternId");
		}
	}

	public record Update(Status status, List<PatternQuery> queries,
		List<SpeculativeAllocation> speculativeAllocations, Optional<RouteCandidate> winner,
		Optional<UUID> retainedSpeculativeBranch, Set<UUID> cancelledSpeculativeBranches,
		boolean staleReplyRejected,
		List<ProductionRouteTable.ProviderSource> providerSources) {
		public Update {
			status = Objects.requireNonNull(status, "status");
			queries = List.copyOf(queries);
			speculativeAllocations = List.copyOf(speculativeAllocations);
			winner = Objects.requireNonNull(winner, "winner");
			retainedSpeculativeBranch = Objects.requireNonNull(retainedSpeculativeBranch,
				"retainedSpeculativeBranch");
			cancelledSpeculativeBranches = Set.copyOf(cancelledSpeculativeBranches);
			providerSources = List.copyOf(providerSources);
		}

		public Update(Status status, List<PatternQuery> queries,
			List<SpeculativeAllocation> speculativeAllocations, Optional<RouteCandidate> winner,
			Optional<UUID> retainedSpeculativeBranch, Set<UUID> cancelledSpeculativeBranches,
			boolean staleReplyRejected) {
			this(status, queries, speculativeAllocations, winner, retainedSpeculativeBranch,
				cancelledSpeculativeBranches, staleReplyRejected, List.of());
		}

		private Update withAllocations(List<SpeculativeAllocation> prior) {
			if (prior.isEmpty())
				return this;
			List<SpeculativeAllocation> all = new ArrayList<>(prior);
			all.addAll(speculativeAllocations);
			return new Update(status, queries, all, winner, retainedSpeculativeBranch,
				cancelledSpeculativeBranches, staleReplyRejected, providerSources);
		}
	}

	private record Match(UUID libraryId, PatternRecord pattern) {}
	private record IssuedFence(UUID libraryId, long formationGeneration, long catalogGeneration,
		long searchGeneration, UUID attemptId, LibraryRegistration.LoadState loadState) {
		boolean sameDirectoryState(LibraryRegistration registration) {
			return libraryId.equals(registration.libraryId())
				&& formationGeneration == registration.formationGeneration()
				&& catalogGeneration == registration.publicCatalogGeneration()
				&& loadState == registration.loadState();
		}
		boolean accepts(PatternReply reply) {
			return libraryId.equals(reply.libraryId())
				&& formationGeneration == reply.formationGeneration()
				&& searchGeneration == reply.searchGeneration()
				&& attemptId.equals(reply.attemptId());
		}
	}
	private record Reconcile(List<PatternQuery> queries, Set<UUID> cancelled, boolean changed) {
		private static final Reconcile EMPTY = new Reconcile(List.of(), Set.of(), false);
		private Reconcile { queries = List.copyOf(queries); cancelled = Set.copyOf(cancelled); }
	}
	private enum ReservationState { PENDING, ACKNOWLEDGED }

	private static final class MutableWave {
		private final int priority;
		private final List<UUID> libraryIds;
		private final Set<UUID> awaitingGroupEnd = new LinkedHashSet<>();
		private final Set<UUID> waitingLibraries = new LinkedHashSet<>();

		private MutableWave(int priority, List<UUID> libraryIds) {
			this.priority = priority;
			this.libraryIds = List.copyOf(libraryIds);
		}

		private LibrarySearchWave snapshot() {
			return LibrarySearchWave.snapshot(priority, libraryIds, awaitingGroupEnd,
				waitingLibraries);
		}
	}
}
