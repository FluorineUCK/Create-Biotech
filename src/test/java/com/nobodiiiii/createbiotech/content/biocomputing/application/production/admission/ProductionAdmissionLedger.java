package com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;

/**
 * Bounded current-schema journal for production admissions.
 *
 * <p>Only live admissions participate in scheduling and admission pressure. Failed admissions
 * are compact diagnostic tombstones; they can therefore neither consume a live slot nor steal a
 * per-tick scheduling visit after a reload.</p>
 */
public final class ProductionAdmissionLedger {
	public record Failure(UUID admissionId, long sequence, String diagnostic) {
		public Failure {
			admissionId = Objects.requireNonNull(admissionId, "admissionId");
			diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
			if (sequence < 0 || diagnostic.isBlank()
				|| diagnostic.length() > ProductionEffect.MAX_TEXT_LENGTH)
				throw new IllegalArgumentException("Invalid production admission failure");
		}
	}

	public record State(
		Map<UUID, PendingProductionAdmission> active,
		Map<UUID, Failure> failures, long nextSequence,
		Optional<UUID> schedulingCursor) {
		public State {
			active = Map.copyOf(Objects.requireNonNull(active, "active"));
			failures = Map.copyOf(Objects.requireNonNull(failures, "failures"));
			schedulingCursor = Objects.requireNonNull(schedulingCursor, "schedulingCursor");
			Map<UUID, PendingProductionAdmission> exactActive = active;
			if (nextSequence < 0)
				throw new IllegalArgumentException("Negative production admission sequence");
			LinkedHashMap<UUID, UUID> searchClaimants = new LinkedHashMap<>();
			active.forEach((id, admission) -> {
				if (!id.equals(admission.admissionId())
					|| admission.phase() == ProductionAdmissionPhase.FAILED)
					throw new IllegalArgumentException("Invalid live production admission");
				if (claimsSearch(admission)) {
					UUID searchId = admission.searchId().orElseThrow();
					UUID prior = searchClaimants.putIfAbsent(searchId, id);
					if (prior != null && !prior.equals(id))
						throw new IllegalArgumentException("Duplicate production search claim");
				}
			});
			failures.forEach((id, failure) -> {
				if (!id.equals(failure.admissionId()) || exactActive.containsKey(id)
					|| failure.sequence() >= nextSequence)
					throw new IllegalArgumentException("Invalid production admission tombstone");
			});
		}
	}

	private final int capacity;
	private final LinkedHashMap<UUID, PendingProductionAdmission> active =
		new LinkedHashMap<>();
	private final LinkedHashMap<UUID, Failure> failures = new LinkedHashMap<>();
	private long nextSequence;
	private Optional<UUID> schedulingCursor = Optional.empty();

	private ProductionAdmissionLedger(int capacity) {
		if (capacity <= 0) throw new IllegalArgumentException("Admission capacity must be positive");
		if (capacity > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
			throw new IllegalArgumentException(
				"Admission capacity exceeds the durable collection representation");
		this.capacity = capacity;
	}

	public static ProductionAdmissionLedger empty(int capacity) {
		return new ProductionAdmissionLedger(capacity);
	}

	public static ProductionAdmissionLedger restore(State state, int capacity) {
		ProductionAdmissionLedger ledger = new ProductionAdmissionLedger(capacity);
		ledger.install(Objects.requireNonNull(state, "state"));
		return ledger;
	}

	public synchronized boolean admit(
		PendingProductionAdmission admission) {
		Objects.requireNonNull(admission, "admission");
		if (admission.phase() == ProductionAdmissionPhase.FAILED
			|| active.size() >= capacity || active.containsKey(admission.admissionId())
			|| failures.containsKey(admission.admissionId())
			|| conflictsWithSearchClaim(admission)) return false;
		active.put(admission.admissionId(), admission);
		return true;
	}

	/** Replaces an existing live admission without changing occupancy. */
	public synchronized boolean update(
		PendingProductionAdmission admission) {
		Objects.requireNonNull(admission, "admission");
		if (admission.phase() == ProductionAdmissionPhase.FAILED
			|| !active.containsKey(admission.admissionId())
			|| conflictsWithSearchClaim(admission)) return false;
		active.put(admission.admissionId(), admission);
		return true;
	}

	public synchronized boolean fail(UUID admissionId, String diagnostic) {
		UUID exactId = Objects.requireNonNull(admissionId, "admissionId");
		PendingProductionAdmission removed = active.remove(exactId);
		if (removed == null) return false;
		Failure failure = new Failure(exactId, nextSequence, diagnostic);
		nextSequence = Math.incrementExact(nextSequence);
		failures.put(exactId, failure);
		compactFailures();
		return true;
	}

	public synchronized boolean remove(UUID admissionId) {
		return active.remove(Objects.requireNonNull(admissionId, "admissionId")) != null;
	}

	public synchronized Optional<PendingProductionAdmission> get(
		UUID admissionId) {
		return Optional.ofNullable(active.get(Objects.requireNonNull(admissionId, "admissionId")));
	}

	public synchronized List<PendingProductionAdmission> values() {
		return List.copyOf(active.values());
	}

	public synchronized boolean searchClaimedByOther(UUID searchId, UUID admissionId) {
		UUID exactSearchId = Objects.requireNonNull(searchId, "searchId");
		UUID exactAdmissionId = Objects.requireNonNull(admissionId, "admissionId");
		return active.values().stream().anyMatch(candidate ->
			!candidate.admissionId().equals(exactAdmissionId) && claimsSearch(candidate)
				&& candidate.searchId().orElseThrow().equals(exactSearchId));
	}

	/**
	 * Creates the durable cleanup fence without allowing a SEARCHING peer to retire a cursor that a
	 * different admission has already claimed.  The caller persists the returned value before any
	 * destructive cleanup is attempted.
	 */
	public synchronized PendingProductionAdmission cleanupFenceForFailure(
		PendingProductionAdmission admission, String diagnostic) {
		PendingProductionAdmission exact = Objects.requireNonNull(admission,
			"admission");
		Objects.requireNonNull(diagnostic, "diagnostic");
		PendingProductionAdmission current = active.get(exact.admissionId());
		if (!exact.equals(current))
			throw new IllegalStateException("Cleanup fence requires the current live admission");
		boolean retainSearch = exact.searchId().isEmpty()
			|| !searchClaimedByOther(exact.searchId().orElseThrow(), exact.admissionId());
		return exact.cleanupPending(diagnostic, retainSearch);
	}

	public synchronized int liveSize() { return active.size(); }

	public synchronized List<PendingProductionAdmission> schedulingBatch(
		int limit) {
		if (limit <= 0 || active.isEmpty()) return List.of();
		List<PendingProductionAdmission> ordered = active.values().stream()
			.sorted(Comparator.comparing(
				PendingProductionAdmission::admissionId))
			.toList();
		int start = 0;
		if (schedulingCursor.isPresent()) {
			UUID cursor = schedulingCursor.orElseThrow();
			for (int index = 0; index < ordered.size(); index++) {
				if (ordered.get(index).admissionId().compareTo(cursor) > 0) {
					start = index;
					break;
				}
			}
		}
		int count = Math.min(limit, ordered.size());
		java.util.ArrayList<PendingProductionAdmission> selected =
			new java.util.ArrayList<>(count);
		for (int offset = 0; offset < count; offset++)
			selected.add(ordered.get((start + offset) % ordered.size()));
		schedulingCursor = Optional.of(selected.getLast().admissionId());
		return List.copyOf(selected);
	}

	public synchronized State snapshot() {
		return new State(active, failures, nextSequence, schedulingCursor);
	}

	/** Installs a state only after its enclosing journal transaction has committed. */
	public synchronized void install(State state) {
		State exact = Objects.requireNonNull(state, "state");
		if (exact.active().size() > capacity)
			throw new IllegalArgumentException("Live production admissions exceed capacity");
		active.clear();
		active.putAll(exact.active());
		failures.clear();
		failures.putAll(exact.failures());
		nextSequence = exact.nextSequence();
		schedulingCursor = exact.schedulingCursor();
		compactFailures();
	}

	private void compactFailures() {
		while (failures.size() > capacity) {
			UUID oldest = failures.values().stream()
				.min(Comparator.comparingLong(Failure::sequence)
					.thenComparing(Failure::admissionId))
				.orElseThrow().admissionId();
			failures.remove(oldest);
		}
	}

	private boolean conflictsWithSearchClaim(
		PendingProductionAdmission admission) {
		return claimsSearch(admission) && searchClaimedByOther(admission.searchId().orElseThrow(),
			admission.admissionId());
	}

	private static boolean claimsSearch(
		PendingProductionAdmission admission) {
		return admission.searchId().isPresent()
			&& admission.phase() != ProductionAdmissionPhase.SEARCHING;
	}
}
