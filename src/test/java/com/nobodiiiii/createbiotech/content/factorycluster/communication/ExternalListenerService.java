package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryBinding;

/**
 * Server-side owner of Wandering Trader listener configuration and ordinary-process claims.
 * Every active entry owns an extended endpoint and its exact subnet binding; a process reservation
 * alone is never projected as active.
 */
public final class ExternalListenerService {
	private final Scheduler scheduler;
	private final RadioRouter router;
	private final PublicationFactory publications;
	private final IngressAuthorizer ingressAuthorizer;
	private final RetirementStore retirementStore;
	private final Map<ListenerOwner, List<ActiveListener>> active = new LinkedHashMap<>();
	/* A failed replacement retains exact router ownership, but deliberately has no process slot. */
	private final Map<ListenerOwner, List<PendingListener>> pending = new LinkedHashMap<>();
	/* Logical retirement remains quarantined until its exact aggregate tombstone commits. */
	private final Map<RetirementKey, RetirementObligation> pendingRetirements = new LinkedHashMap<>();
	private final Set<RetirementKey> committedRetirements = new LinkedHashSet<>();
	private final Set<ListenerOwner> unloadedOwners = new LinkedHashSet<>();
	private final Map<ListenerOwner, List<ListenerProjection>> projections = new LinkedHashMap<>();
	/* Route failures are retried after a publication input, explicit refresh, or Router authority change. */
	private final Map<ListenerOwner, Map<CommunicationFrequency, PublicationFailure>> publicationFailures =
		new LinkedHashMap<>();
	private final Map<UUID, ListenerOwner> currentOwners = new LinkedHashMap<>();
	private final Set<ListenerOwner> refreshRequested = new LinkedHashSet<>();
	/* Runtime consumes only committed authority transitions; rejected candidates emit no transition. */
	private final Map<UUID, List<ListenerCommit>> committedTransitions = new LinkedHashMap<>();

	/**
	 * Composition-root constructor. A publication always supplies the exact endpoint and boundary
	 * authority owned by an active listener; callers never pass gateway/relay booleans.
	 */
	public ExternalListenerService(Scheduler scheduler, RadioRouter router,
		PublicationFactory publications, IngressAuthorizer ingressAuthorizer) {
		this(scheduler, router, publications, ingressAuthorizer, RetirementStore.NONE);
	}

	public ExternalListenerService(Scheduler scheduler, RadioRouter router,
		PublicationFactory publications, IngressAuthorizer ingressAuthorizer,
		RetirementStore retirementStore) {
		this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
		this.router = Objects.requireNonNull(router, "router");
		this.publications = Objects.requireNonNull(publications, "publications");
		this.ingressAuthorizer = Objects.requireNonNull(ingressAuthorizer, "ingress authorizer");
		this.retirementStore = Objects.requireNonNull(retirementStore, "retirement store");
		RetirementState restored = retirementStore.load().orElseGet(RetirementState::empty);
		for (RetirementObligation retirement : restored.retirements()) {
			RetirementKey key = new RetirementKey(retirement.owner(), retirement.publication());
			if (pendingRetirements.putIfAbsent(key, retirement) != null)
				throw new IllegalArgumentException("duplicate listener retirement obligation");
		}
	}

	public EditResult replace(ListenerComputer computer, long expectedRevision,
		List<CommunicationFrequency> requested) {
		computer = Objects.requireNonNull(computer, "computer");
		EditResult result = replaceConfiguration(computer, expectedRevision, requested);
		if (result.applied()) reconcile(computer);
		return result;
	}

	/** Shared durable CAS used by Computer editing and later authoritative editors. */
	public static EditResult replaceConfiguration(ConfigurationStore computer,
		long expectedRevision, List<CommunicationFrequency> requested) {
		computer = Objects.requireNonNull(computer, "computer");
		requested = List.copyOf(Objects.requireNonNull(requested, "requested"));
		ProductionResourceProfile profile = computer.profile();
		if (profile == null || profile.profession() != ProductionProfession.WANDERING_TRADER)
			return EditResult.ORDINARY_COMPUTER;
		if (!computer.listenerPersistenceAuthorized()) return EditResult.NOT_FORMED;
		ExternalListenerConfiguration current = Objects.requireNonNull(computer.configuration(),
			"listener configuration");
		if (expectedRevision != current.revision()) return EditResult.STALE;
		if (requested.size() > profile.processSlots()) return EditResult.TOO_MANY;
		if (requested.stream().anyMatch(frequency -> frequency == null || !frequency.configured()))
			return EditResult.INVALID_FREQUENCY;
		if (new LinkedHashSet<>(requested).size() != requested.size()) return EditResult.DUPLICATE;
		if (current.frequencies().equals(requested)) return EditResult.NO_CHANGE;
		if (current.revision() == Long.MAX_VALUE) return EditResult.REVISION_EXHAUSTED;
		ExternalListenerConfiguration next = new ExternalListenerConfiguration(current.revision() + 1,
			requested);
		return computer.compareAndSet(current, next) ? EditResult.APPLIED : EditResult.STALE;
	}

	/** Re-attempts configured entries after normal-process capacity or topology changes. */
	public void reconcile(ListenerComputer computer) {
		computer = Objects.requireNonNull(computer, "computer");
		retryPendingRetirements();
		ListenerOwner owner = computer.listenerOwner();
		unloadedOwners.remove(owner);
		ListenerOwner priorOwner = currentOwners.put(computer.id(), owner);
		if (priorOwner != null && !priorOwner.equals(owner)) retireOwner(priorOwner);
		if (!computer.listenerPersistenceAuthorized()) {
			retireOwner(owner);
			return;
		}
		ExternalListenerConfiguration configuration = Objects.requireNonNull(computer.configuration(),
			"listener configuration");
		boolean refresh = refreshRequested.remove(owner);
		if (refresh) publicationFailures.remove(owner);
		List<ActiveListener> prior = active.getOrDefault(owner, List.of());
		List<PendingListener> priorPending = pending.getOrDefault(owner, List.of());
		Map<CommunicationFrequency, ActiveListener> retained = new LinkedHashMap<>();
		for (ActiveListener listener : prior) {
			if (configuration.frequencies().contains(listener.frequency())) retained.put(listener.frequency(), listener);
			else release(owner, listener);
		}
		Map<CommunicationFrequency, PendingListener> retainedPending = new LinkedHashMap<>();
		for (PendingListener listener : priorPending) {
			if (configuration.frequencies().contains(listener.frequency()))
				retainedPending.put(listener.frequency(), listener);
			else retirePending(owner, listener);
		}
		publicationFailures.computeIfPresent(owner, (ignored, failures) -> {
			failures.keySet().retainAll(configuration.frequencies());
			return failures.isEmpty() ? null : failures;
		});
		List<ActiveListener> nextActive = new ArrayList<>();
		List<PendingListener> nextPending = new ArrayList<>();
		List<ListenerProjection> nextProjection = new ArrayList<>();
		for (CommunicationFrequency frequency : configuration.frequencies()) {
			if (hasPendingRetirement(owner, frequency)) continue;
			ActiveListener listener = retained.get(frequency);
			if (listener != null) {
				if (refresh || listener.configurationRevision() != configuration.revision()
					|| listener.publicationGeneration() != computer.publicationGeneration()) {
					Optional<Publication> replacement = replacePublication(computer, owner, frequency,
						listener.reservation(), listener.publication());
					if (replacement.isEmpty()) {
						releaseReservation(listener);
						nextPending.add(new PendingListener(frequency, listener.publication()));
						rememberPublicationFailure(owner, frequency, configuration, computer);
						nextProjection.add(ListenerProjection.waitingForRoute(frequency));
						continue;
					}
					listener = new ActiveListener(frequency, listener.reservation(),
						configuration.revision(), computer.publicationGeneration(),
						replacement.orElseThrow());
				}
				clearPublicationFailure(owner, frequency);
				nextActive.add(listener);
				nextProjection.add(ListenerProjection.active(frequency, listener.reservation().slot()));
				continue;
			}
			PendingListener pendingListener = retainedPending.get(frequency);
			if (pendingListener != null) {
				if (publicationFailed(owner, frequency, configuration, computer)) {
					nextPending.add(pendingListener);
					nextProjection.add(ListenerProjection.waitingForRoute(frequency));
					continue;
				}
				Optional<Reservation> reservation = scheduler.reserve(computer.id(), frequency,
					computer.profile().processSlots());
				if (reservation.isEmpty()) {
					nextPending.add(pendingListener);
					nextProjection.add(ListenerProjection.waitingForProcess(frequency));
					continue;
				}
				Optional<Publication> replacement = replacePublication(computer, owner, frequency,
					reservation.orElseThrow(), pendingListener.predecessor());
				if (replacement.isEmpty()) {
					scheduler.release(reservation.orElseThrow());
					nextPending.add(pendingListener);
					rememberPublicationFailure(owner, frequency, configuration, computer);
					nextProjection.add(ListenerProjection.waitingForRoute(frequency));
					continue;
				}
				clearPublicationFailure(owner, frequency);
				nextActive.add(new ActiveListener(frequency, reservation.orElseThrow(),
					configuration.revision(), computer.publicationGeneration(), replacement.orElseThrow()));
				nextProjection.add(ListenerProjection.active(frequency,
					reservation.orElseThrow().slot()));
				continue;
			}
			if (publicationFailed(owner, frequency, configuration, computer)) {
				nextProjection.add(ListenerProjection.waitingForRoute(frequency));
				continue;
			}
			Optional<Reservation> reservation = scheduler.reserve(computer.id(), frequency,
				computer.profile().processSlots());
			if (reservation.isEmpty()) {
				nextProjection.add(ListenerProjection.waitingForProcess(frequency));
				continue;
			}
			ActiveListener reserved = activate(computer, frequency, reservation.orElseThrow(),
				configuration.revision());
			if (reserved == null) {
				rememberPublicationFailure(owner, frequency, configuration, computer);
				nextProjection.add(ListenerProjection.waitingForRoute(frequency));
			}
			else {
				clearPublicationFailure(owner, frequency);
				nextActive.add(reserved);
				nextProjection.add(ListenerProjection.active(frequency, reserved.reservation().slot()));
			}
		}
		if (nextActive.isEmpty()) active.remove(owner);
		else active.put(owner, List.copyOf(nextActive));
		if (nextPending.isEmpty()) pending.remove(owner);
		else pending.put(owner, List.copyOf(nextPending));
		projections.put(owner, List.copyOf(nextProjection));
	}

	/** Re-publishes endpoint-bound authority after an exact topology revision changes. */
	public void refresh(ListenerComputer computer) {
		computer = Objects.requireNonNull(computer, "computer");
		refreshRequested.add(computer.listenerOwner());
		reconcile(computer);
	}

	/** Releases only the exact owner instance that invoked the lifecycle callback. */
	public void unload(ListenerComputer computer) {
		computer = Objects.requireNonNull(computer, "computer");
		unload(computer.listenerOwner());
	}

	/** Exact owner-token unload used after an unform has already cleared the live epoch object. */
	public void unload(ListenerOwner owner) {
		owner = Objects.requireNonNull(owner, "owner");
		if (!owner.equals(currentOwners.get(owner.computerId()))) return;
		unloadedOwners.add(owner);
		retireOwner(owner);
		if (!hasPendingRetirement(owner)) {
			unloadedOwners.remove(owner);
			currentOwners.remove(owner.computerId(), owner);
		}
	}

	public List<ListenerProjection> projection(UUID computerId) {
		ListenerOwner owner = currentOwners.get(Objects.requireNonNull(computerId, "computer id"));
		return owner == null ? List.of() : projection(owner);
	}

	/** Exact-owner projection used while an unloaded predecessor remains quarantined. */
	public List<ListenerProjection> projection(ListenerOwner owner) {
		owner = Objects.requireNonNull(owner, "owner");
		ArrayList<ListenerProjection> result = new ArrayList<>(projections.getOrDefault(owner, List.of()));
		for (RetirementObligation retirement : pendingRetirements.values()) {
			if (!retirement.owner().equals(owner) || result.stream().anyMatch(projection ->
				projection.frequency().equals(retirement.frequency()))) continue;
			result.add(ListenerProjection.waitingForRoute(retirement.frequency()));
		}
		return List.copyOf(result);
	}

	/** Exact active publications, exposed only to the server composition root's topology bridge. */
	public List<Publication> publications(UUID computerId) {
		ListenerOwner owner = currentOwners.get(Objects.requireNonNull(computerId, "computer id"));
		return owner == null ? List.of() : active.getOrDefault(owner, List.of()).stream()
			.map(ActiveListener::publication).toList();
	}

	/** Returns and clears only authority transitions that completed their aggregate CAS. */
	public List<ListenerCommit> drainCommittedTransitions(UUID computerId) {
		List<ListenerCommit> transitions = committedTransitions.remove(Objects.requireNonNull(computerId,
			"computer id"));
		return transitions == null ? List.of() : List.copyOf(transitions);
	}

	/** Returns and clears all committed transitions, including obligations recovered for unloaded owners. */
	public List<ListenerCommit> drainCommittedTransitions() {
		ArrayList<ListenerCommit> transitions = new ArrayList<>();
		for (UUID computerId : List.copyOf(committedTransitions.keySet()))
			transitions.addAll(drainCommittedTransitions(computerId));
		return List.copyOf(transitions);
	}

	/**
	 * The only listener ingress seam. It accepts a pre-authorized local Goal or a pre-existing
	 * revision-fenced lease, then asks the router to mint the exact registered boundary transition.
	 */
	public Optional<RadioEnvelope> forwardIngress(ListenerComputer computer,
		CommunicationFrequency frequency, RadioEnvelope envelope, IngressAuthority authority) {
		computer = Objects.requireNonNull(computer, "computer");
		CommunicationFrequency selectedFrequency = Objects.requireNonNull(frequency, "frequency");
		RadioEnvelope incoming = Objects.requireNonNull(envelope, "envelope");
		authority = Objects.requireNonNull(authority, "authority");
		ListenerOwner owner = computer.listenerOwner();
		if (!owner.equals(currentOwners.get(computer.id()))
			|| !selectedFrequency.equals(incoming.frequency())
			|| active.getOrDefault(owner, List.of()).stream().noneMatch(listener ->
				listener.frequency().equals(selectedFrequency)
					&& listener.publication().endpoint().hop().equals(incoming.sourceHop()))
			|| !ingressAuthorizer.authorized(computer, selectedFrequency, authority)) return Optional.empty();
		return router.forwardBoundary(incoming);
	}

	private ActiveListener activate(ListenerComputer computer, CommunicationFrequency frequency,
		Reservation reservation, long configurationRevision) {
		Publication publication;
		try {
			publication = Objects.requireNonNull(publications.publish(computer, frequency, reservation),
				"listener publication");
		} catch (RuntimeException publicationFailure) {
			scheduler.release(reservation);
			return null;
		}
		if (collidesWithPendingRetirement(publication)) {
			scheduler.release(reservation);
			return null;
		}
		Optional<RadioRouter.ListenerAuthorityTransaction> transaction = router
			.prepareListenerPublication(Optional.empty(), authority(publication));
		if (transaction.isEmpty() || !router.compareAndSetListenerAuthority(transaction.orElseThrow())) {
			scheduler.release(reservation);
			return null;
		}
		recordCommittedTransition(computer.listenerOwner(), Optional.empty(), Optional.of(publication));
		return new ActiveListener(frequency, reservation, configurationRevision,
			computer.publicationGeneration(), publication);
	}

	/** Replaces source, relay and boundary in one persist-before-live authority transaction. */
	private Optional<Publication> replacePublication(ListenerComputer computer, ListenerOwner owner,
		CommunicationFrequency frequency, Reservation reservation, Publication current) {
		Publication replacement;
		try {
			replacement = Objects.requireNonNull(publications.publish(computer,
				frequency, reservation), "listener publication");
		} catch (RuntimeException publicationFailure) {
			return Optional.empty();
		}
		Optional<RadioRouter.ListenerAuthorityTransaction> transaction = router
			.prepareListenerPublication(Optional.of(authority(current)), authority(replacement));
		if (transaction.isEmpty() || !router.compareAndSetListenerAuthority(transaction.orElseThrow()))
			return Optional.empty();
		recordCommittedTransition(owner, Optional.of(current), Optional.of(replacement));
		return Optional.of(replacement);
	}

	private void retireOwner(ListenerOwner owner) {
		List<ActiveListener> released = active.remove(owner);
		if (released != null) for (ActiveListener listener : released) release(owner, listener);
		List<PendingListener> pendingRetirements = pending.remove(owner);
		if (pendingRetirements != null)
			for (PendingListener listener : pendingRetirements) retirePending(owner, listener);
		projections.remove(owner);
		refreshRequested.remove(owner);
		publicationFailures.remove(owner);
	}

	private void release(ListenerOwner owner, ActiveListener listener) {
		retire(owner, listener.frequency(), listener.publication());
		releaseReservation(listener);
	}

	/** An unreserved failed replacement still owns its exact predecessor until retry or retirement. */
	private void retirePending(ListenerOwner owner, PendingListener listener) {
		retire(owner, listener.frequency(), listener.predecessor());
	}

	private void retire(ListenerOwner owner, CommunicationFrequency frequency,
		Publication publication) {
		RetirementKey key = new RetirementKey(owner, publication);
		if (pendingRetirements.putIfAbsent(key,
			new RetirementObligation(owner, frequency, publication)) == null)
			persistRetirementsBestEffort();
		tryPendingRetirement(key);
	}

	/** Retries every exact quarantined retirement after a tick, observation, or authority change. */
	public void retryPendingRetirements() {
		for (RetirementKey key : List.copyOf(pendingRetirements.keySet()))
			tryPendingRetirement(key);
		for (ListenerOwner owner : List.copyOf(unloadedOwners)) {
			if (hasPendingRetirement(owner)) continue;
			unloadedOwners.remove(owner);
			currentOwners.remove(owner.computerId(), owner);
		}
	}

	private void tryPendingRetirement(RetirementKey key) {
		RetirementObligation retirement = pendingRetirements.get(key);
		if (retirement == null) return;
		RadioRouter.ListenerAuthority retiring = authority(retirement.publication());
		if (router.listenerAuthorityRetired(retiring)) {
			completeRetirement(key, retirement);
			return;
		}
		Optional<RadioRouter.ListenerAuthorityTransaction> transaction = router
			.prepareListenerRetirement(retiring);
		if (transaction.isEmpty()
			|| !router.compareAndSetListenerAuthority(transaction.orElseThrow())) return;
		completeRetirement(key, retirement);
	}

	private void completeRetirement(RetirementKey key, RetirementObligation retirement) {
		if (committedRetirements.add(key))
			recordCommittedTransition(retirement.owner(), Optional.of(retirement.publication()),
				Optional.empty());
		pendingRetirements.remove(key, retirement);
		try {
			retirementStore.persist(new RetirementState(List.copyOf(pendingRetirements.values())));
			committedRetirements.remove(key);
		} catch (RuntimeException persistenceFailure) {
			pendingRetirements.putIfAbsent(key, retirement);
		}
	}

	private void persistRetirementsBestEffort() {
		try {
			retirementStore.persist(new RetirementState(List.copyOf(pendingRetirements.values())));
		} catch (RuntimeException persistenceFailure) {
			/* The exact in-memory quarantine still owns the obligation until persistence recovers. */
		}
	}

	private boolean hasPendingRetirement(ListenerOwner owner) {
		return pendingRetirements.values().stream().anyMatch(retirement ->
			retirement.owner().equals(owner));
	}

	private boolean hasPendingRetirement(ListenerOwner owner, CommunicationFrequency frequency) {
		return pendingRetirements.values().stream().anyMatch(retirement ->
			retirement.owner().equals(owner) && retirement.frequency().equals(frequency));
	}

	private boolean collidesWithPendingRetirement(Publication candidate) {
		return pendingRetirements.values().stream().map(RetirementObligation::publication)
			.anyMatch(retiring -> retiring.endpoint().key().equals(candidate.endpoint().key())
				|| retiring.relayEndpoint().key().equals(candidate.relayEndpoint().key())
				|| retiring.boundaryBinding().sourceClusterId().equals(
					candidate.boundaryBinding().sourceClusterId()));
	}

	private void releaseReservation(ActiveListener listener) {
		scheduler.release(listener.reservation());
	}

	private static RadioRouter.ListenerAuthority authority(Publication publication) {
		return new RadioRouter.ListenerAuthority(publication.endpoint(), publication.relayEndpoint(),
			publication.boundaryBinding());
	}

	private boolean publicationFailed(ListenerOwner owner, CommunicationFrequency frequency,
		ExternalListenerConfiguration configuration, ListenerComputer computer) {
		PublicationFailure failure = publicationFailures.getOrDefault(owner, Map.of()).get(frequency);
		return failure != null && failure.configurationRevision() == configuration.revision()
			&& failure.publicationGeneration() == computer.publicationGeneration()
			&& failure.routerAuthorityRevision() == router.authorityRevision();
	}
	private void rememberPublicationFailure(ListenerOwner owner, CommunicationFrequency frequency,
		ExternalListenerConfiguration configuration, ListenerComputer computer) {
		publicationFailures.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).put(frequency,
			new PublicationFailure(configuration.revision(), computer.publicationGeneration(),
				router.authorityRevision()));
	}
	private void clearPublicationFailure(ListenerOwner owner, CommunicationFrequency frequency) {
		publicationFailures.computeIfPresent(owner, (ignored, failures) -> {
			failures.remove(frequency);
			return failures.isEmpty() ? null : failures;
		});
	}
	private void recordCommittedTransition(ListenerOwner owner, Optional<Publication> predecessor,
		Optional<Publication> replacement) {
		committedTransitions.computeIfAbsent(owner.computerId(), ignored -> new ArrayList<>()).add(
			new ListenerCommit(owner, predecessor, replacement));
	}
	private record PublicationFailure(long configurationRevision, long publicationGeneration,
		long routerAuthorityRevision) {}

	public interface ConfigurationStore {
		ProductionResourceProfile profile();
		ExternalListenerConfiguration configuration();
		/** Enforces formed, online and durable storage authority at the shared CAS boundary. */
		default boolean listenerPersistenceAuthorized() { return false; }
		boolean compareAndSet(ExternalListenerConfiguration expected,
			ExternalListenerConfiguration next);
	}

	public interface ListenerComputer extends ConfigurationStore {
		UUID id();
		/** Per loaded Computer instance identity; stale lifecycle calls must not affect a successor. */
		default ListenerOwner listenerOwner() { return new ListenerOwner(id(), new UUID(0, 0), id()); }
		/** Topology/endpoint generation that forces a new exact publication. */
		default long publicationGeneration() { return 0; }
	}

	public record ListenerOwner(UUID computerId, UUID epochId, UUID ownerToken) {
		public ListenerOwner {
			computerId = Objects.requireNonNull(computerId, "computer id");
			epochId = Objects.requireNonNull(epochId, "epoch id");
			ownerToken = Objects.requireNonNull(ownerToken, "owner token");
		}
	}

	/** A process scheduler makes every reservation from the host's ordinary slot pool. */
	public interface Scheduler {
		Optional<Reservation> reserve(UUID computerId, CommunicationFrequency frequency, int slotLimit);
		void release(Reservation reservation);
		int occupancy(UUID computerId, ProcessKind kind);
	}

	public record Reservation(UUID leaseId, UUID computerId, int slot) {
		public Reservation {
			leaseId = Objects.requireNonNull(leaseId, "lease id");
			computerId = Objects.requireNonNull(computerId, "computer id");
			if (slot < 0) throw new IllegalArgumentException("negative listener slot");
		}
	}

	public enum EditResult {
		APPLIED, NO_CHANGE, STALE, TOO_MANY, DUPLICATE, INVALID_FREQUENCY,
		ORDINARY_COMPUTER, NOT_FORMED, REVISION_EXHAUSTED, STOPPING;

		public boolean applied() { return this == APPLIED || this == NO_CHANGE; }
	}

	public enum ListenerState { ACTIVE, WAITING_PROCESS, WAITING_ROUTE }

	public record ListenerProjection(CommunicationFrequency frequency, ListenerState state,
		Optional<WaitReason> waitReason, Optional<Integer> slot) {
		public ListenerProjection {
			frequency = Objects.requireNonNull(frequency, "frequency");
			state = Objects.requireNonNull(state, "state");
			waitReason = Objects.requireNonNull(waitReason, "wait reason");
			slot = Objects.requireNonNull(slot, "slot");
			if (state == ListenerState.ACTIVE && (!slot.isPresent() || waitReason.isPresent())
				|| state == ListenerState.WAITING_PROCESS
					&& (slot.isPresent() || !waitReason.equals(Optional.of(WaitReason.PROCESS)))
				|| state == ListenerState.WAITING_ROUTE
					&& (slot.isPresent() || !waitReason.equals(Optional.of(WaitReason.ROUTE))))
				throw new IllegalArgumentException("invalid listener projection");
		}
		static ListenerProjection active(CommunicationFrequency frequency, int slot) {
			return new ListenerProjection(frequency, ListenerState.ACTIVE, Optional.empty(),
				Optional.of(slot));
		}
		static ListenerProjection waitingForProcess(CommunicationFrequency frequency) {
			return new ListenerProjection(frequency, ListenerState.WAITING_PROCESS,
				Optional.of(WaitReason.PROCESS), Optional.empty());
		}
		static ListenerProjection waitingForRoute(CommunicationFrequency frequency) {
			return new ListenerProjection(frequency, ListenerState.WAITING_ROUTE,
				Optional.of(WaitReason.ROUTE), Optional.empty());
		}
	}

	@FunctionalInterface
	public interface PublicationFactory {
		Publication publish(ListenerComputer computer, CommunicationFrequency frequency,
			Reservation reservation);
	}

	@FunctionalInterface
	public interface IngressAuthorizer {
		boolean authorized(ListenerComputer computer, CommunicationFrequency frequency,
			IngressAuthority authority);
	}

	/** Active listeners are always extended-range radio endpoints plus exact gateway authorities. */
	public record Publication(RadioEndpoint endpoint, RadioEndpoint relayEndpoint,
		SubnetBoundaryBinding boundaryBinding) {
		public Publication {
			endpoint = Objects.requireNonNull(endpoint, "endpoint");
			relayEndpoint = Objects.requireNonNull(relayEndpoint, "relay endpoint");
			boundaryBinding = Objects.requireNonNull(boundaryBinding, "boundary binding");
			if (!endpoint.hop().equals(boundaryBinding.sourceHop())
				|| !relayEndpoint.hop().equals(boundaryBinding.targetHop())
				|| !endpoint.frequency().equals(boundaryBinding.listenerFrequency())
				|| !relayEndpoint.frequency().equals(boundaryBinding.listenerFrequency()))
				throw new IllegalArgumentException("listener endpoint, relay and gateway binding differ");
			if (!endpoint.supports(ProductionCapability.EXTENDED_RADIO)
				|| !relayEndpoint.supports(ProductionCapability.EXTENDED_RADIO))
				throw new IllegalArgumentException("external listener relay requires extended radio");
		}
	}
	/** No raw external command can become work: it must identify a local Goal or fenced lease. */
	public sealed interface IngressAuthority permits IngressAuthority.LocalGoal,
		IngressAuthority.ExistingLease {
		record LocalGoal(UUID goalId) implements IngressAuthority {
			public LocalGoal { goalId = Objects.requireNonNull(goalId, "goal id"); }
		}
		record ExistingLease(UUID leaseId, long revision) implements IngressAuthority {
			public ExistingLease {
				leaseId = Objects.requireNonNull(leaseId, "lease id");
				if (revision < 0) throw new IllegalArgumentException("negative lease revision");
			}
		}
	}

	/** An aggregate authority CAS that completed; Runtime projects only these transitions. */
	public record ListenerCommit(ListenerOwner owner, Optional<Publication> predecessor,
		Optional<Publication> replacement) {
		public ListenerCommit {
			owner = Objects.requireNonNull(owner, "owner");
			predecessor = Objects.requireNonNull(predecessor, "predecessor");
			replacement = Objects.requireNonNull(replacement, "replacement");
			if (predecessor.isEmpty() && replacement.isEmpty())
				throw new IllegalArgumentException("listener commit needs a predecessor or replacement");
		}
	}

	private record ActiveListener(CommunicationFrequency frequency, Reservation reservation,
		long configurationRevision, long publicationGeneration, Publication publication) {}
	private record PendingListener(CommunicationFrequency frequency, Publication predecessor) {}
	private record RetirementKey(ListenerOwner owner, Publication publication) {}

	public record RetirementObligation(ListenerOwner owner, CommunicationFrequency frequency,
		Publication publication) {
		public RetirementObligation {
			owner = Objects.requireNonNull(owner, "owner");
			frequency = Objects.requireNonNull(frequency, "frequency");
			publication = Objects.requireNonNull(publication, "publication");
			if (!frequency.equals(publication.boundaryBinding().listenerFrequency()))
				throw new IllegalArgumentException("retirement frequency differs from publication");
		}
	}

	public record RetirementState(List<RetirementObligation> retirements) {
		public RetirementState {
			retirements = List.copyOf(Objects.requireNonNull(retirements, "retirements"));
			if (retirements.size() > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
				throw new IllegalArgumentException("listener retirement obligations exceed capacity");
			Set<RetirementKey> keys = new LinkedHashSet<>();
			for (RetirementObligation retirement : retirements)
				if (!keys.add(new RetirementKey(retirement.owner(), retirement.publication())))
					throw new IllegalArgumentException("duplicate listener retirement obligation");
		}
		public static RetirementState empty() { return new RetirementState(List.of()); }
	}

	public interface RetirementStore {
		RetirementStore NONE = new RetirementStore() {
			@Override public Optional<RetirementState> load() { return Optional.empty(); }
			@Override public void persist(RetirementState state) {}
		};
		Optional<RetirementState> load();
		void persist(RetirementState state);
	}
}
