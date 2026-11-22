package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;

/**
 * Server-tick router over caller-selected exact hops. It never discovers a global channel,
 * substitutes a target, changes a frozen relay path, or advances a message twice in one tick.
 */
public final class RadioRouter {
	private final int normalRange;
	private final int extendedRange;
	private final RadioLinkPolicy linkPolicy;
	private final int endpointGenerationFenceCapacity;
	private final int agentProtocolEvidenceCapacity;
	private final int leaseProtocolEvidenceCapacity;
	private final Map<RadioEndpoint.Key, RadioEndpoint> endpoints = new LinkedHashMap<>();
	private final Map<RadioEndpoint.Key, Long> highestEndpointGenerations = new LinkedHashMap<>();
	private final Map<RadioEndpoint.Key, EndpointAuthority> endpointAuthorities =
		new LinkedHashMap<>();
	private final Map<UUID, SubnetBoundaryBinding> boundaryBindings = new LinkedHashMap<>();
	private final Map<UUID, BoundaryAuthority> boundaryAuthorities = new LinkedHashMap<>();
	private final Map<RadioEndpoint.Key, Integer> listenerOccupancy = new HashMap<>();
	private final Map<UUID, PendingRoute> routes = new LinkedHashMap<>();
	private final Map<UUID, LeaseState> leases = new HashMap<>();
	private final Map<RemoteLeaseLineage, RetiredLeaseState> retiredLeaseLineages =
		new LinkedHashMap<>();
	private final Map<UUID, MaterialIntentPermit> issuedMaterialPermits = new HashMap<>();
	private final AgentProtocolStateStore agentProtocolStore;
	private final LeaseProtocolStateStore leaseProtocolStore;
	private final AuthorityStateStore authorityStore;
	private final Map<RemoteRequestKey, AgentPeerState> agentPeers = new LinkedHashMap<>();
	/* Changes whenever endpoint/boundary authority really changes; used by dependent retry stamps. */
	private long authorityRevision;
	private long durableAuthorityRevision;
	private long lastTick = Long.MIN_VALUE;

	public RadioRouter(int normalRange, int extendedRange,
		int endpointGenerationFenceCapacity) {
		this(normalRange, extendedRange, endpointGenerationFenceCapacity,
			endpointGenerationFenceCapacity,
			new AgentProtocolStateStore() {
				@Override public void persist(AgentProtocolState state) {}
			}, new LeaseProtocolStateStore() {
				@Override public void persist(LeaseProtocolState state) {}
			});
	}

	public RadioRouter(int normalRange, int extendedRange,
		int endpointGenerationFenceCapacity, AgentProtocolStateStore agentProtocolStore) {
		this(normalRange, extendedRange, endpointGenerationFenceCapacity,
			endpointGenerationFenceCapacity, agentProtocolStore,
			new LeaseProtocolStateStore() {
				@Override public void persist(LeaseProtocolState state) {}
			});
	}

	public RadioRouter(int normalRange, int extendedRange,
		int endpointGenerationFenceCapacity, int agentProtocolEvidenceCapacity,
		AgentProtocolStateStore agentProtocolStore) {
		this(normalRange, extendedRange, endpointGenerationFenceCapacity,
			agentProtocolEvidenceCapacity, agentProtocolStore,
			new LeaseProtocolStateStore() {
				@Override public void persist(LeaseProtocolState state) {}
			});
	}

	public RadioRouter(int normalRange, int extendedRange,
		int endpointGenerationFenceCapacity, int protocolEvidenceCapacity,
		AgentProtocolStateStore agentProtocolStore,
		LeaseProtocolStateStore leaseProtocolStore) {
		this(normalRange, extendedRange, endpointGenerationFenceCapacity,
			protocolEvidenceCapacity, agentProtocolStore, leaseProtocolStore,
			new AuthorityStateStore() {});
	}

	public RadioRouter(int normalRange, int extendedRange,
		int endpointGenerationFenceCapacity, int protocolEvidenceCapacity,
		AgentProtocolStateStore agentProtocolStore,
		LeaseProtocolStateStore leaseProtocolStore, AuthorityStateStore authorityStore) {
		if (normalRange <= 0 || extendedRange < normalRange)
			throw new IllegalArgumentException("radio ranges are invalid");
		if (endpointGenerationFenceCapacity <= 0
			|| endpointGenerationFenceCapacity
				> FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
			throw new IllegalArgumentException(
				"endpoint generation fence capacity exceeds its durable representation");
		if (protocolEvidenceCapacity <= 0
			|| protocolEvidenceCapacity > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
			throw new IllegalArgumentException(
				"protocol evidence capacity exceeds its durable representation");
		this.normalRange = normalRange;
		this.extendedRange = extendedRange;
		this.linkPolicy = new RadioLinkPolicy(normalRange, extendedRange);
		this.endpointGenerationFenceCapacity = endpointGenerationFenceCapacity;
		this.agentProtocolEvidenceCapacity = protocolEvidenceCapacity;
		this.leaseProtocolEvidenceCapacity = protocolEvidenceCapacity;
		this.agentProtocolStore = Objects.requireNonNull(agentProtocolStore, "agentProtocolStore");
		this.leaseProtocolStore = Objects.requireNonNull(leaseProtocolStore, "leaseProtocolStore");
		this.authorityStore = Objects.requireNonNull(authorityStore, "authorityStore");
		AuthorityState restoredAuthority = authorityStore.load().orElseGet(AuthorityState::empty);
		if (restoredAuthority.endpoints().size() > endpointGenerationFenceCapacity)
			throw new IllegalArgumentException("Durable endpoint authority exceeds capacity");
		installAuthorityState(restoredAuthority);
		agentProtocolStore.load().ifPresent(state -> {
			if (state.peers().size() > protocolEvidenceCapacity
				|| state.peers().values().stream().anyMatch(peer ->
					peer.immutableMessages().size() > protocolEvidenceCapacity))
				throw new IllegalArgumentException("Durable Agent protocol evidence exceeds capacity");
			agentPeers.putAll(state.peers());
		});
		leaseProtocolStore.load().ifPresent(state -> {
			if ((long) state.leases().size() + state.retiredLineages().size()
				> protocolEvidenceCapacity)
				throw new IllegalArgumentException("Durable lease protocol evidence exceeds capacity");
			state.leases().forEach((leaseId, durable) -> {
				validateDurableLease(leaseId, durable, protocolEvidenceCapacity);
				leases.put(leaseId, LeaseState.restore(durable));
			});
			state.retiredLineages().forEach((lineage, retired) -> {
				if (!lineage.equals(retired.lineage()))
					throw new IllegalArgumentException("Durable retired lease lineage key mismatch");
				retiredLeaseLineages.put(lineage, retired);
			});
		});
	}

	public boolean register(RadioEndpoint endpoint) {
		endpoint = Objects.requireNonNull(endpoint, "endpoint");
		if (endpoint.generation() == Long.MAX_VALUE) return false;
		RadioEndpoint previous = endpoints.get(endpoint.key());
		if (previous != null && endpoint.generation() == previous.generation())
			return endpoint.equals(previous);
		EndpointAuthority authority = endpointAuthorities.get(endpoint.key());
		if (previous == null && authority != null && authority.current().filter(endpoint::equals).isPresent()) {
			if (authorityRevision >= Long.MAX_VALUE - 1) return false;
			endpoints.put(endpoint.key(), endpoint);
			authorityRevision++;
			return true;
		}
		Long highestGeneration = highestEndpointGenerations.get(endpoint.key());
		if (highestGeneration != null && endpoint.generation() <= highestGeneration)
			return false;
		if (highestGeneration == null
			&& highestEndpointGenerations.size() >= endpointGenerationFenceCapacity)
			return false;
		if (authorityRevision >= Long.MAX_VALUE - 1 || durableAuthorityRevision >= Long.MAX_VALUE - 1)
			return false;
		LinkedHashMap<RadioEndpoint.Key, EndpointAuthority> next =
			new LinkedHashMap<>(endpointAuthorities);
		next.put(endpoint.key(), new EndpointAuthority(endpoint.generation(), Optional.of(endpoint)));
		if (!persistAuthority(next, boundaryAuthorities)) return false;
		endpoints.put(endpoint.key(), endpoint);
		return true;
	}

	/**
	 * Returns the next generation which can cross this exact endpoint key's current/tombstoned
	 * generation. Router mutations are server-thread confined, so a caller must register the
	 * returned exact endpoint in the same authoritative publication transaction.
	 */
	public OptionalLong nextEndpointGeneration(RadioEndpoint.Key key) {
		key = Objects.requireNonNull(key, "endpoint key");
		Long highest = highestEndpointGenerations.get(key);
		if (highest == null && highestEndpointGenerations.size() >= endpointGenerationFenceCapacity)
			return OptionalLong.empty();
		long floor = highest == null ? -1 : highest;
		return floor >= Long.MAX_VALUE - 1 ? OptionalLong.empty() : OptionalLong.of(floor + 1);
	}

	/**
	 * Reuses an exact durably-current publication only while it is not live in this process;
	 * otherwise allocates strictly beyond the persisted high-watermark.
	 */
	public OptionalLong endpointGenerationForPublication(RadioEndpoint prototype) {
		RadioEndpoint requested = Objects.requireNonNull(prototype, "endpoint prototype");
		EndpointAuthority authority = endpointAuthorities.get(requested.key());
		if (!endpoints.containsKey(requested.key()) && authority != null
			&& authority.current().filter(current -> samePublicationIgnoringGeneration(
				current, requested)).isPresent())
			return OptionalLong.of(authority.highestGeneration());
		return nextEndpointGeneration(requested.key());
	}

	private static boolean samePublicationIgnoringGeneration(RadioEndpoint current,
		RadioEndpoint prototype) {
		return current.key().equals(prototype.key())
			&& current.address().equals(prototype.address())
			&& current.frequency().equals(prototype.frequency())
			&& current.listenerCapacity() == prototype.listenerCapacity()
			&& current.capabilities().equals(prototype.capabilities());
	}

	/** Monotonic revision for endpoint/binding authority, unchanged by failed or idempotent calls. */
	public long authorityRevision() { return authorityRevision; }

	/** Read-only exact endpoint lookup used by lifecycle owners to avoid revoking a collision. */
	public Optional<RadioEndpoint> registeredEndpoint(RadioEndpoint.Key key) {
		return Optional.ofNullable(endpoints.get(Objects.requireNonNull(key, "key")));
	}

	/** Read-only exact boundary lookup used by lifecycle owners to establish ownership. */
	public Optional<SubnetBoundaryBinding> boundaryBinding(UUID sourceClusterId) {
		return Optional.ofNullable(boundaryBindings.get(Objects.requireNonNull(sourceClusterId,
			"source cluster id")));
	}

	/**
	 * Opaque latest router authority publication. Consumers may inspect the frozen values, but
	 * only this router can establish that the endpoint and boundary generations are still current.
	 */
	public AuthorityPublication captureAuthorityPublication() {
		LinkedHashMap<UUID, Long> boundaryGenerations = new LinkedHashMap<>();
		for (Map.Entry<UUID, BoundaryAuthority> entry : boundaryAuthorities.entrySet())
			boundaryGenerations.put(entry.getKey(), entry.getValue().revision());
		return new AuthorityPublication(this, authorityRevision, endpoints, boundaryBindings,
			boundaryGenerations);
	}

	private boolean isCurrent(AuthorityPublication publication) {
		return publication.owner == this && publication.authorityRevision == authorityRevision
			&& publication.endpoints.equals(endpoints)
			&& publication.boundaryBindings.equals(boundaryBindings)
			&& publication.boundaryGenerations.entrySet().stream().allMatch(entry -> {
				BoundaryAuthority current = boundaryAuthorities.get(entry.getKey());
				return current != null && current.revision() == entry.getValue();
			})
			&& publication.boundaryGenerations.size() == boundaryAuthorities.size();
	}

	public static final class AuthorityPublication {
		private final RadioRouter owner;
		private final long authorityRevision;
		private final Map<RadioEndpoint.Key, RadioEndpoint> endpoints;
		private final Map<UUID, SubnetBoundaryBinding> boundaryBindings;
		private final Map<UUID, Long> boundaryGenerations;

		private AuthorityPublication(RadioRouter owner, long authorityRevision,
			Map<RadioEndpoint.Key, RadioEndpoint> endpoints,
			Map<UUID, SubnetBoundaryBinding> boundaryBindings,
			Map<UUID, Long> boundaryGenerations) {
			this.owner = owner;
			this.authorityRevision = authorityRevision;
			this.endpoints = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(endpoints));
			this.boundaryBindings = java.util.Collections.unmodifiableMap(
				new LinkedHashMap<>(boundaryBindings));
			this.boundaryGenerations = java.util.Collections.unmodifiableMap(
				new LinkedHashMap<>(boundaryGenerations));
		}

		public long authorityRevision() { return authorityRevision; }
		public List<RadioEndpoint> endpoints() { return List.copyOf(endpoints.values()); }
		public List<SubnetBoundaryBinding> boundaryBindings() {
			return List.copyOf(boundaryBindings.values());
		}
		public long boundaryGeneration(UUID sourceClusterId) {
			Long generation = boundaryGenerations.get(Objects.requireNonNull(sourceClusterId,
				"source cluster id"));
			if (generation == null) throw new IllegalArgumentException("boundary is not published");
			return generation;
		}
		public boolean isCurrent() { return owner.isCurrent(this); }
	}

	/** Immutable durable snapshot exposed to transaction tests and persistence adapters. */
	public AuthorityState authorityStateForTest() { return authorityState(); }

	/**
	 * Prepares one listener's source endpoint, relay endpoint and boundary as a single durable
	 * authority replacement. Preparation is pure: an absent result cannot alter live or durable
	 * state, and callers must apply the returned transaction exactly once.
	 */
	public Optional<ListenerAuthorityTransaction> prepareListenerPublication(
		Optional<ListenerAuthority> predecessor, ListenerAuthority replacement) {
		predecessor = Objects.requireNonNull(predecessor, "listener predecessor");
		replacement = Objects.requireNonNull(replacement, "listener replacement");
		if (replacement.sourceEndpoint().generation() == Long.MAX_VALUE
			|| replacement.relayEndpoint().generation() == Long.MAX_VALUE) return Optional.empty();
		AuthorityState expected = authorityState();
		return predecessor.isEmpty()
			? prepareListenerActivation(expected, replacement)
			: prepareListenerReplacement(expected, predecessor.orElseThrow(), replacement);
	}

	/** Prepares the exact active listener's endpoint/boundary tombstones as one CAS. */
	public Optional<ListenerAuthorityTransaction> prepareListenerRetirement(
		ListenerAuthority retiring) {
		retiring = Objects.requireNonNull(retiring, "retiring listener authority");
		AuthorityState expected = authorityState();
		if (!matchesDurableListener(expected, retiring)
			|| !matchesLiveListener(retiring) && !hasNoLiveListenerAuthority(retiring))
			return Optional.empty();
		BoundaryAuthority boundary = expected.boundaries().get(retiring.boundaryBinding()
			.sourceClusterId());
		if (boundary.revision() >= Long.MAX_VALUE - 1) return Optional.empty();
		OptionalLong nextRevision = nextAuthorityRevision(expected.revision());
		if (nextRevision.isEmpty()) return Optional.empty();
		LinkedHashMap<RadioEndpoint.Key, EndpointAuthority> nextEndpoints =
			new LinkedHashMap<>(expected.endpoints());
		nextEndpoints.put(retiring.sourceEndpoint().key(), new EndpointAuthority(
			expected.endpoints().get(retiring.sourceEndpoint().key()).highestGeneration(),
			Optional.empty()));
		nextEndpoints.put(retiring.relayEndpoint().key(), new EndpointAuthority(
			expected.endpoints().get(retiring.relayEndpoint().key()).highestGeneration(),
			Optional.empty()));
		LinkedHashMap<UUID, BoundaryAuthority> nextBoundaries = new LinkedHashMap<>(
			expected.boundaries());
		nextBoundaries.put(retiring.boundaryBinding().sourceClusterId(), new BoundaryAuthority(
			boundary.revision() + 1, retiring.boundaryBinding(), false));
		return Optional.of(listenerTransaction(expected, new AuthorityState(nextRevision.getAsLong(),
			nextEndpoints, nextBoundaries), retiring.sourceEndpoint().key(), retiring.relayEndpoint().key(),
			retiring.boundaryBinding().sourceClusterId(), Optional.empty()));
	}

	/** Exact durable tombstone proof used to finish a recovered retirement obligation. */
	public boolean listenerAuthorityRetired(ListenerAuthority retired) {
		retired = Objects.requireNonNull(retired, "retired listener authority");
		EndpointAuthority source = endpointAuthorities.get(retired.sourceEndpoint().key());
		EndpointAuthority relay = endpointAuthorities.get(retired.relayEndpoint().key());
		BoundaryAuthority boundary = boundaryAuthorities.get(retired.boundaryBinding().sourceClusterId());
		return source != null && source.highestGeneration() == retired.sourceEndpoint().generation()
			&& source.current().isEmpty() && relay != null
			&& relay.highestGeneration() == retired.relayEndpoint().generation()
			&& relay.current().isEmpty() && boundary != null && !boundary.active()
			&& boundary.binding().equals(retired.boundaryBinding())
			&& !endpoints.containsKey(retired.sourceEndpoint().key())
			&& !endpoints.containsKey(retired.relayEndpoint().key())
			&& !boundaryBindings.containsKey(retired.boundaryBinding().sourceClusterId());
	}

	/** Persists the complete listener authority before exposing any part of it as live. */
	public boolean compareAndSetListenerAuthority(ListenerAuthorityTransaction transaction) {
		transaction = Objects.requireNonNull(transaction, "listener authority transaction");
		if (!authorityState().equals(transaction.expected()) || !validListenerTransactionDelta(transaction))
			return false;
		try {
			if (!authorityStore.compareAndSet(transaction.expected(), transaction.replacement()))
				return false;
		} catch (RuntimeException persistenceFailure) {
			return false;
		}
		installAuthorityState(transaction.replacement());
		installLiveListenerAuthority(transaction);
		return true;
	}

	private Optional<ListenerAuthorityTransaction> prepareListenerActivation(AuthorityState expected,
		ListenerAuthority replacement) {
		if (hasLiveListenerCollision(replacement)) return Optional.empty();
		if (isDurableListenerReactivation(expected, replacement)) {
			OptionalLong next = nextAuthorityRevision(expected.revision());
			if (next.isEmpty()) return Optional.empty();
			return Optional.of(listenerTransaction(expected, new AuthorityState(next.getAsLong(),
				expected.endpoints(), expected.boundaries()), replacement.sourceEndpoint().key(),
				replacement.relayEndpoint().key(), replacement.boundaryBinding().sourceClusterId(),
				Optional.of(replacement)));
		}
		Optional<ListenerAuthority> recoveredPredecessor = durableCurrentListener(expected, replacement);
		if (recoveredPredecessor.isPresent()) {
			ListenerAuthority predecessor = recoveredPredecessor.orElseThrow();
			if (!hasNoLiveListenerAuthority(predecessor)
				|| !sameListenerKeys(predecessor, replacement)
				|| !strictlySupersedes(predecessor, replacement)) return Optional.empty();
			return prepareListenerReplacementDelta(expected, replacement);
		}
		EndpointAuthority source = expected.endpoints().get(replacement.sourceEndpoint().key());
		EndpointAuthority relay = expected.endpoints().get(replacement.relayEndpoint().key());
		BoundaryAuthority boundary = expected.boundaries().get(replacement.boundaryBinding()
			.sourceClusterId());
		if (boundary != null && boundary.revision() >= Long.MAX_VALUE - 1) return Optional.empty();
		OptionalLong next = nextAuthorityRevision(expected.revision());
		if (next.isEmpty()) return Optional.empty();
		if (!canActivateEndpoint(source, replacement.sourceEndpoint())
			|| !canActivateEndpoint(relay, replacement.relayEndpoint())
			|| !canActivateBoundary(boundary, replacement.boundaryBinding())) return Optional.empty();
		int newEndpointKeys = (source == null ? 1 : 0) + (relay == null ? 1 : 0);
		if ((long) expected.endpoints().size() + newEndpointKeys > endpointGenerationFenceCapacity
			|| boundary == null && expected.boundaries().size()
				>= FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES) return Optional.empty();
		LinkedHashMap<RadioEndpoint.Key, EndpointAuthority> nextEndpoints =
			new LinkedHashMap<>(expected.endpoints());
		nextEndpoints.put(replacement.sourceEndpoint().key(), new EndpointAuthority(
			replacement.sourceEndpoint().generation(), Optional.of(replacement.sourceEndpoint())));
		nextEndpoints.put(replacement.relayEndpoint().key(), new EndpointAuthority(
			replacement.relayEndpoint().generation(), Optional.of(replacement.relayEndpoint())));
		LinkedHashMap<UUID, BoundaryAuthority> nextBoundaries = new LinkedHashMap<>(
			expected.boundaries());
		nextBoundaries.put(replacement.boundaryBinding().sourceClusterId(), new BoundaryAuthority(
			boundary == null ? 0 : boundary.revision() + 1, replacement.boundaryBinding(), true));
		return Optional.of(listenerTransaction(expected, new AuthorityState(next.getAsLong(), nextEndpoints,
			nextBoundaries), replacement.sourceEndpoint().key(), replacement.relayEndpoint().key(),
			replacement.boundaryBinding().sourceClusterId(), Optional.of(replacement)));
	}

	private Optional<ListenerAuthorityTransaction> prepareListenerReplacement(AuthorityState expected,
		ListenerAuthority predecessor, ListenerAuthority replacement) {
		if (!sameListenerKeys(predecessor, replacement) || !matchesLiveListener(predecessor)
			|| !matchesDurableListener(expected, predecessor)
			|| !strictlySupersedes(predecessor, replacement)) return Optional.empty();
		return prepareListenerReplacementDelta(expected, replacement);
	}

	private Optional<ListenerAuthorityTransaction> prepareListenerReplacementDelta(
		AuthorityState expected, ListenerAuthority replacement) {
		BoundaryAuthority boundary = expected.boundaries().get(replacement.boundaryBinding()
			.sourceClusterId());
		if (boundary.revision() >= Long.MAX_VALUE - 1) return Optional.empty();
		OptionalLong next = nextAuthorityRevision(expected.revision());
		if (next.isEmpty()) return Optional.empty();
		LinkedHashMap<RadioEndpoint.Key, EndpointAuthority> nextEndpoints =
			new LinkedHashMap<>(expected.endpoints());
		nextEndpoints.put(replacement.sourceEndpoint().key(), new EndpointAuthority(
			replacement.sourceEndpoint().generation(), Optional.of(replacement.sourceEndpoint())));
		nextEndpoints.put(replacement.relayEndpoint().key(), new EndpointAuthority(
			replacement.relayEndpoint().generation(), Optional.of(replacement.relayEndpoint())));
		LinkedHashMap<UUID, BoundaryAuthority> nextBoundaries = new LinkedHashMap<>(
			expected.boundaries());
		nextBoundaries.put(replacement.boundaryBinding().sourceClusterId(), new BoundaryAuthority(
			boundary.revision() + 1, replacement.boundaryBinding(), true));
		return Optional.of(listenerTransaction(expected, new AuthorityState(next.getAsLong(), nextEndpoints,
			nextBoundaries), replacement.sourceEndpoint().key(), replacement.relayEndpoint().key(),
			replacement.boundaryBinding().sourceClusterId(), Optional.of(replacement)));
	}

	private static ListenerAuthorityTransaction listenerTransaction(AuthorityState expected,
		AuthorityState replacement, RadioEndpoint.Key sourceKey, RadioEndpoint.Key relayKey,
		UUID boundarySourceClusterId, Optional<ListenerAuthority> liveReplacement) {
		return new ListenerAuthorityTransaction(expected, replacement, sourceKey, relayKey,
			boundarySourceClusterId, liveReplacement);
	}

	private static OptionalLong nextAuthorityRevision(long revision) {
		return revision >= Long.MAX_VALUE - 1 ? OptionalLong.empty() : OptionalLong.of(revision + 1);
	}

	private boolean hasLiveListenerCollision(ListenerAuthority authority) {
		return endpoints.containsKey(authority.sourceEndpoint().key())
			|| endpoints.containsKey(authority.relayEndpoint().key())
			|| boundaryBindings.containsKey(authority.boundaryBinding().sourceClusterId());
	}

	private static boolean canActivateEndpoint(EndpointAuthority previous, RadioEndpoint candidate) {
		return candidate.generation() != Long.MAX_VALUE && (previous == null || previous.current().isEmpty()
			&& candidate.generation() > previous.highestGeneration());
	}

	private static boolean canActivateBoundary(BoundaryAuthority previous,
		SubnetBoundaryBinding candidate) {
		return previous == null || !previous.active() && previous.revision() < Long.MAX_VALUE - 1
			&& supersedes(candidate, previous.binding());
	}

	private static boolean sameListenerKeys(ListenerAuthority first, ListenerAuthority second) {
		return first.sourceEndpoint().key().equals(second.sourceEndpoint().key())
			&& first.relayEndpoint().key().equals(second.relayEndpoint().key())
			&& first.boundaryBinding().sourceClusterId().equals(second.boundaryBinding().sourceClusterId());
	}

	private static boolean strictlySupersedes(ListenerAuthority predecessor,
		ListenerAuthority replacement) {
		boolean sourceAdvances = endpointAdvances(predecessor.sourceEndpoint(),
			replacement.sourceEndpoint());
		boolean relayAdvances = endpointAdvances(predecessor.relayEndpoint(),
			replacement.relayEndpoint());
		return sourceAdvances && relayAdvances && (replacement.sourceEndpoint().generation()
			> predecessor.sourceEndpoint().generation() || replacement.relayEndpoint().generation()
			> predecessor.relayEndpoint().generation());
	}

	private static boolean endpointAdvances(RadioEndpoint predecessor, RadioEndpoint replacement) {
		return replacement.equals(predecessor) || replacement.generation() > predecessor.generation();
	}

	private boolean matchesLiveListener(ListenerAuthority authority) {
		return authority.sourceEndpoint().equals(endpoints.get(authority.sourceEndpoint().key()))
			&& authority.relayEndpoint().equals(endpoints.get(authority.relayEndpoint().key()))
			&& authority.boundaryBinding().equals(boundaryBindings.get(authority.boundaryBinding()
				.sourceClusterId()));
	}

	private boolean hasNoLiveListenerAuthority(ListenerAuthority authority) {
		return !endpoints.containsKey(authority.sourceEndpoint().key())
			&& !endpoints.containsKey(authority.relayEndpoint().key())
			&& !boundaryBindings.containsKey(authority.boundaryBinding().sourceClusterId());
	}

	private static boolean matchesDurableListener(AuthorityState state, ListenerAuthority authority) {
		return state.endpoints().get(authority.sourceEndpoint().key()) != null
			&& state.endpoints().get(authority.sourceEndpoint().key()).current().filter(
				authority.sourceEndpoint()::equals).isPresent()
			&& state.endpoints().get(authority.relayEndpoint().key()) != null
			&& state.endpoints().get(authority.relayEndpoint().key()).current().filter(
				authority.relayEndpoint()::equals).isPresent()
			&& state.boundaries().get(authority.boundaryBinding().sourceClusterId()) != null
			&& state.boundaries().get(authority.boundaryBinding().sourceClusterId()).active()
			&& state.boundaries().get(authority.boundaryBinding().sourceClusterId()).binding().equals(
				authority.boundaryBinding());
	}

	private static boolean isDurableListenerReactivation(AuthorityState state,
		ListenerAuthority authority) {
		return matchesDurableListener(state, authority);
	}

	/** Derives, rather than trusts a caller to supply, the exact active durable predecessor. */
	private static Optional<ListenerAuthority> durableCurrentListener(AuthorityState state,
		ListenerAuthority candidate) {
		EndpointAuthority source = state.endpoints().get(candidate.sourceEndpoint().key());
		EndpointAuthority relay = state.endpoints().get(candidate.relayEndpoint().key());
		BoundaryAuthority boundary = state.boundaries().get(candidate.boundaryBinding().sourceClusterId());
		if (source == null || relay == null || boundary == null || source.current().isEmpty()
			|| relay.current().isEmpty() || !boundary.active()) return Optional.empty();
		ListenerAuthority predecessor = new ListenerAuthority(source.current().orElseThrow(),
			relay.current().orElseThrow(), boundary.binding());
		return sameListenerKeys(predecessor, candidate) ? Optional.of(predecessor) : Optional.empty();
	}

	private boolean validListenerTransactionDelta(ListenerAuthorityTransaction transaction) {
		AuthorityState expected = transaction.expected();
		AuthorityState replacement = transaction.replacement();
		if (expected.revision() == Long.MAX_VALUE || replacement.revision() != expected.revision() + 1
			|| replacement.endpoints().size() > endpointGenerationFenceCapacity
			|| !listenerMapsDifferOnlyAt(expected, replacement, transaction.sourceKey(),
				transaction.relayKey(), transaction.boundarySourceClusterId())) return false;
		return transaction.liveReplacement().map(authority -> validListenerPublicationDelta(
			transaction, authority)).orElseGet(() -> validListenerRetirementDelta(transaction));
	}

	private static boolean listenerMapsDifferOnlyAt(AuthorityState expected, AuthorityState replacement,
		RadioEndpoint.Key sourceKey, RadioEndpoint.Key relayKey, UUID boundarySourceClusterId) {
		Map<RadioEndpoint.Key, EndpointAuthority> expectedEndpoints = new LinkedHashMap<>(
			expected.endpoints());
		Map<RadioEndpoint.Key, EndpointAuthority> replacementEndpoints = new LinkedHashMap<>(
			replacement.endpoints());
		expectedEndpoints.remove(sourceKey);
		expectedEndpoints.remove(relayKey);
		replacementEndpoints.remove(sourceKey);
		replacementEndpoints.remove(relayKey);
		Map<UUID, BoundaryAuthority> expectedBoundaries = new LinkedHashMap<>(expected.boundaries());
		Map<UUID, BoundaryAuthority> replacementBoundaries = new LinkedHashMap<>(
			replacement.boundaries());
		expectedBoundaries.remove(boundarySourceClusterId);
		replacementBoundaries.remove(boundarySourceClusterId);
		return expectedEndpoints.equals(replacementEndpoints)
			&& expectedBoundaries.equals(replacementBoundaries);
	}

	private boolean validListenerPublicationDelta(ListenerAuthorityTransaction transaction,
		ListenerAuthority authority) {
		if (!authority.sourceEndpoint().key().equals(transaction.sourceKey())
			|| !authority.relayEndpoint().key().equals(transaction.relayKey())
			|| !authority.boundaryBinding().sourceClusterId().equals(transaction.boundarySourceClusterId())
			|| !replacementContainsLiveListener(transaction.replacement(), authority)) return false;
		AuthorityState expected = transaction.expected();
		if (isDurableListenerReactivation(expected, authority))
			return !hasLiveListenerCollision(authority)
				&& expected.endpoints().equals(transaction.replacement().endpoints())
				&& expected.boundaries().equals(transaction.replacement().boundaries());
		EndpointAuthority source = expected.endpoints().get(authority.sourceEndpoint().key());
		EndpointAuthority relay = expected.endpoints().get(authority.relayEndpoint().key());
		BoundaryAuthority boundary = expected.boundaries().get(authority.boundaryBinding()
			.sourceClusterId());
		// Every non-reactivation publication below advances an existing boundary revision.  Reject
		// exhaustion before any candidate arithmetic, so malformed public transactions return false
		// without invoking the durable store.
		if (boundary != null && boundary.revision() == Long.MAX_VALUE) return false;
		if (source == null || relay == null || boundary == null) {
			return !hasLiveListenerCollision(authority) && canActivateEndpoint(source,
				authority.sourceEndpoint()) && canActivateEndpoint(relay, authority.relayEndpoint())
				&& canActivateBoundary(boundary, authority.boundaryBinding())
				&& replacementContainsActiveBoundary(transaction.replacement(), authority,
					boundary == null ? 0 : boundary.revision() + 1);
		}
		if (source.current().isEmpty() || relay.current().isEmpty() || !boundary.active())
			return !hasLiveListenerCollision(authority) && canActivateEndpoint(source,
				authority.sourceEndpoint()) && canActivateEndpoint(relay, authority.relayEndpoint())
				&& canActivateBoundary(boundary, authority.boundaryBinding())
				&& replacementContainsActiveBoundary(transaction.replacement(), authority,
					boundary.revision() + 1);
		ListenerAuthority predecessor = new ListenerAuthority(source.current().orElseThrow(),
			relay.current().orElseThrow(), boundary.binding());
		return (matchesLiveListener(predecessor) || hasNoLiveListenerAuthority(predecessor))
			&& sameListenerKeys(predecessor, authority)
			&& strictlySupersedes(predecessor, authority)
			&& replacementContainsActiveBoundary(transaction.replacement(), authority,
				boundary.revision() + 1);
	}

	private static boolean replacementContainsLiveListener(AuthorityState state,
		ListenerAuthority authority) {
		return new EndpointAuthority(authority.sourceEndpoint().generation(), Optional.of(
			authority.sourceEndpoint())).equals(state.endpoints().get(authority.sourceEndpoint().key()))
			&& new EndpointAuthority(authority.relayEndpoint().generation(), Optional.of(
				authority.relayEndpoint())).equals(state.endpoints().get(authority.relayEndpoint().key()))
			&& state.boundaries().get(authority.boundaryBinding().sourceClusterId()) != null
			&& state.boundaries().get(authority.boundaryBinding().sourceClusterId()).active()
			&& state.boundaries().get(authority.boundaryBinding().sourceClusterId()).binding().equals(
				authority.boundaryBinding());
	}

	private static boolean replacementContainsActiveBoundary(AuthorityState state,
		ListenerAuthority authority, long revision) {
		return new BoundaryAuthority(revision, authority.boundaryBinding(), true).equals(
			state.boundaries().get(authority.boundaryBinding().sourceClusterId()));
	}

	private boolean validListenerRetirementDelta(ListenerAuthorityTransaction transaction) {
		EndpointAuthority source = transaction.expected().endpoints().get(transaction.sourceKey());
		EndpointAuthority relay = transaction.expected().endpoints().get(transaction.relayKey());
		BoundaryAuthority boundary = transaction.expected().boundaries().get(
			transaction.boundarySourceClusterId());
		if (source == null || relay == null || boundary == null || source.current().isEmpty()
			|| relay.current().isEmpty() || !boundary.active() || boundary.revision() == Long.MAX_VALUE)
			return false;
		ListenerAuthority retiring = new ListenerAuthority(source.current().orElseThrow(),
			relay.current().orElseThrow(), boundary.binding());
		return (matchesLiveListener(retiring) || hasNoLiveListenerAuthority(retiring))
			&& new EndpointAuthority(source.highestGeneration(), Optional.empty()).equals(
				transaction.replacement().endpoints().get(transaction.sourceKey()))
			&& new EndpointAuthority(relay.highestGeneration(), Optional.empty()).equals(
				transaction.replacement().endpoints().get(transaction.relayKey()))
			&& new BoundaryAuthority(boundary.revision() + 1, boundary.binding(), false).equals(
				transaction.replacement().boundaries().get(transaction.boundarySourceClusterId()));
	}

	private void installLiveListenerAuthority(ListenerAuthorityTransaction transaction) {
		if (transaction.liveReplacement().isPresent()) {
			ListenerAuthority authority = transaction.liveReplacement().orElseThrow();
			endpoints.put(authority.sourceEndpoint().key(), authority.sourceEndpoint());
			endpoints.put(authority.relayEndpoint().key(), authority.relayEndpoint());
			boundaryBindings.put(authority.boundaryBinding().sourceClusterId(),
				authority.boundaryBinding());
			return;
		}
		endpoints.remove(transaction.sourceKey());
		endpoints.remove(transaction.relayKey());
		boundaryBindings.remove(transaction.boundarySourceClusterId());
	}
	/** Rebuilds only one live endpoint's Cluster-owned primary-frequency projection. */
	public boolean replaceClusterPrimaryFrequency(RadioEndpoint.Key key, long generation,
		com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency) {
		key = Objects.requireNonNull(key, "key");
		frequency = Objects.requireNonNull(frequency, "frequency");
		RadioEndpoint existing = endpoints.get(key);
		if (existing == null || existing.generation() != generation) return false;
		if (existing.frequency().equals(frequency)) return true;
		if (authorityRevision == Long.MAX_VALUE || durableAuthorityRevision == Long.MAX_VALUE)
			return false;
		RadioEndpoint replacement = new RadioEndpoint(existing.clusterId(), existing.memberId(),
			existing.address(), existing.generation(), frequency, existing.listenerCapacity(),
			existing.capabilities());
		LinkedHashMap<RadioEndpoint.Key, EndpointAuthority> next =
			new LinkedHashMap<>(endpointAuthorities);
		next.put(key, new EndpointAuthority(highestEndpointGenerations.get(key),
			Optional.of(replacement)));
		if (!persistAuthority(next, boundaryAuthorities)) return false;
		endpoints.put(key, replacement);
		return true;
	}

	public boolean unregister(RadioEndpoint.Key key, long generation) {
		key = Objects.requireNonNull(key, "key");
		RadioEndpoint endpoint = endpoints.get(key);
		if (endpoint == null || endpoint.generation() != generation)
			return false;
		if (authorityRevision == Long.MAX_VALUE || durableAuthorityRevision == Long.MAX_VALUE)
			return false;
		LinkedHashMap<RadioEndpoint.Key, EndpointAuthority> next =
			new LinkedHashMap<>(endpointAuthorities);
		next.put(key, new EndpointAuthority(highestEndpointGenerations.get(key), Optional.empty()));
		if (!persistAuthority(next, boundaryAuthorities)) return false;
		endpoints.remove(key);
		return true;
	}

	/**
	 * Removes only the complete endpoint authority installed by the caller.  A key/generation pair
	 * alone is insufficient for listener rollback because a same-generation collision can carry
	 * different address or capability authority.
	 */
	public boolean unregisterExactEndpoint(RadioEndpoint expected) {
		expected = Objects.requireNonNull(expected, "expected endpoint");
		RadioEndpoint current = endpoints.get(expected.key());
		if (!expected.equals(current)) return false;
		if (authorityRevision == Long.MAX_VALUE || durableAuthorityRevision == Long.MAX_VALUE)
			return false;
		LinkedHashMap<RadioEndpoint.Key, EndpointAuthority> next =
			new LinkedHashMap<>(endpointAuthorities);
		next.put(expected.key(), new EndpointAuthority(
			highestEndpointGenerations.get(expected.key()), Optional.empty()));
		if (!persistAuthority(next, boundaryAuthorities)) return false;
		endpoints.remove(expected.key());
		return true;
	}

	/**
	 * Installs one composition-root authority for a source Cluster. A source Cluster may own only
	 * one directed boundary binding; duplicate registration is idempotent and conflicting input is
	 * rejected without mutating router state.
	 */
	public boolean registerBoundaryBinding(SubnetBoundaryBinding binding) {
		binding = Objects.requireNonNull(binding, "binding");
		SubnetBoundaryBinding existing = boundaryBindings.get(binding.sourceClusterId());
		if (existing != null) return existing.equals(binding);
		BoundaryAuthority authority = boundaryAuthorities.get(binding.sourceClusterId());
		if (authority != null && authority.active() && authority.binding().equals(binding)) {
			if (authorityRevision >= Long.MAX_VALUE - 1) return false;
			boundaryBindings.put(binding.sourceClusterId(), binding);
			authorityRevision++;
			return true;
		}
		if (authority == null
			&& boundaryAuthorities.size() >= FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
			return false;
		if (authority != null && !supersedes(binding, authority.binding())) return false;
		if (authority != null && authority.revision() >= Long.MAX_VALUE - 1) return false;
		if (authorityRevision >= Long.MAX_VALUE - 1 || durableAuthorityRevision >= Long.MAX_VALUE - 1)
			return false;
		long revision = authority == null ? 0 : authority.revision() + 1;
		LinkedHashMap<UUID, BoundaryAuthority> next = new LinkedHashMap<>(boundaryAuthorities);
		next.put(binding.sourceClusterId(), new BoundaryAuthority(revision, binding, true));
		if (!persistAuthority(endpointAuthorities, next)) return false;
		boundaryBindings.put(binding.sourceClusterId(), binding);
		return true;
	}

	/**
	 * Idempotently revokes only this exact authority. A stale binding cannot remove a replacement
	 * that shares the same source Cluster.
	 */
	public boolean unregisterBoundaryBinding(SubnetBoundaryBinding binding) {
		binding = Objects.requireNonNull(binding, "binding");
		SubnetBoundaryBinding existing = boundaryBindings.get(binding.sourceClusterId());
		if (existing == null) return true;
		if (!existing.equals(binding)) return false;
		BoundaryAuthority authority = boundaryAuthorities.get(binding.sourceClusterId());
		if (authority == null || !authority.active() || !authority.binding().equals(binding)
			|| authority.revision() >= Long.MAX_VALUE - 1 || authorityRevision >= Long.MAX_VALUE - 1
			|| durableAuthorityRevision >= Long.MAX_VALUE - 1) return false;
		LinkedHashMap<UUID, BoundaryAuthority> next = new LinkedHashMap<>(boundaryAuthorities);
		next.put(binding.sourceClusterId(), new BoundaryAuthority(authority.revision() + 1,
			binding, false));
		if (!persistAuthority(endpointAuthorities, next)) return false;
		boundaryBindings.remove(binding.sourceClusterId());
		return true;
	}

	/** Alias for listener composition roots that name boundary removal a revocation. */
	public boolean revokeBoundaryBinding(SubnetBoundaryBinding binding) {
		return unregisterBoundaryBinding(binding);
	}

	/**
	 * Atomically reconfigures one source-Cluster boundary. Repeating an already installed
	 * replacement is idempotent; a stale expected binding cannot overwrite current authority.
	 */
	public boolean replaceBoundaryBinding(SubnetBoundaryBinding expected,
		SubnetBoundaryBinding replacement) {
		expected = Objects.requireNonNull(expected, "expected");
		replacement = Objects.requireNonNull(replacement, "replacement");
		if (!expected.sourceClusterId().equals(replacement.sourceClusterId())) return false;
		SubnetBoundaryBinding current = boundaryBindings.get(expected.sourceClusterId());
		if (current == null) return false;
		if (!current.equals(expected)) return false;
		if (current.equals(replacement)) return true;
		BoundaryAuthority authority = boundaryAuthorities.get(expected.sourceClusterId());
		if (authority == null || !authority.active() || !authority.binding().equals(expected)
			|| authority.revision() >= Long.MAX_VALUE - 1 || authorityRevision >= Long.MAX_VALUE - 1
			|| durableAuthorityRevision >= Long.MAX_VALUE - 1) return false;
		LinkedHashMap<UUID, BoundaryAuthority> next = new LinkedHashMap<>(boundaryAuthorities);
		next.put(replacement.sourceClusterId(), new BoundaryAuthority(authority.revision() + 1,
			replacement, true));
		if (!persistAuthority(endpointAuthorities, next)) return false;
		boundaryBindings.put(replacement.sourceClusterId(), replacement);
		return true;
	}

	/**
	 * Atomically derives structured boundary evidence from a currently registered binding. A raw
	 * envelope or an independently constructed boundary service cannot create this transition.
	 */
	public Optional<RadioEnvelope> forwardBoundary(RadioEnvelope envelope) {
		envelope = Objects.requireNonNull(envelope, "envelope");
		if (envelope.hopSequence() != 0 || envelope.boundaryTransition() != null)
			return Optional.empty();
		SubnetBoundaryBinding binding = boundaryBindings.get(envelope.sourceClusterId());
		if (!matchesBoundaryEnvelope(envelope, binding) || !matchesBoundaryEndpoints(envelope, binding))
			return Optional.empty();
		try {
			return Optional.of(envelope.withBoundaryTransition(envelope.subnetTrace().visit(
				binding.targetNetworkId(), binding.targetClusterId()), binding));
		} catch (IllegalArgumentException | IllegalStateException invalidTrace) {
			return Optional.empty();
		}
	}

	/** Submit one immutable envelope over an explicit, generation-fenced relay list. */
	public RouteStatus submit(RadioEnvelope envelope, List<RadioEndpoint.Hop> explicitRelays) {
		envelope = Objects.requireNonNull(envelope, "envelope");
		if (envelope.hopSequence() != 0)
			throw new IllegalArgumentException("a new route must start at hop zero");
		explicitRelays = List.copyOf(Objects.requireNonNull(explicitRelays, "explicitRelays"));
		List<RadioEndpoint.Hop> path = new ArrayList<>(explicitRelays.size() + 2);
		path.add(envelope.sourceHop());
		path.addAll(explicitRelays);
		path.add(envelope.targetHop());
		path = List.copyOf(path);
		validateSubnetStructure(envelope, path);
		PendingRoute existing = routes.get(envelope.messageId());
		if (existing != null) {
			if (!existing.original.equals(envelope) || !existing.path.equals(path))
				throw new IllegalArgumentException(
					"radio message id collision changed its envelope or frozen path");
			return existing.status;
		}

		validateLeaseOfferEnvelope(envelope);
		validateLeaseAdmission(envelope);
		validateKnownLeaseEnvelope(envelope);
		validateAgentEnvelope(envelope);
		if (envelope.payload() instanceof RecoveryProtocolMessage recovery)
			recovery.validateEnvelope(envelope);
		PendingRoute route = new PendingRoute(envelope, path);
		routes.put(envelope.messageId(), route);
		return route.status;
	}

	/** Legacy test seam: endpoint local positions become a static immutable frame. */
	public TickResult tick(long serverTick) {
		return tick(serverTick, legacyTopologyFrame());
	}

	/** Advances every frozen route against the one current immutable topology frame. */
	public TickResult tick(long serverTick, RadioTopologyFrame frame) {
		frame = Objects.requireNonNull(frame, "frame");
		if (serverTick < lastTick)
			throw new IllegalArgumentException("server ticks cannot move backwards");
		if (serverTick == lastTick)
			return new TickResult(List.of());
		lastTick = serverTick;
		List<Delivery> deliveries = new ArrayList<>();
		for (PendingRoute route : routes.values()) {
			if (route.status == RouteStatus.DELIVERED)
				continue;
			Delivery delivery = advance(route, frame);
			if (delivery != null)
				deliveries.add(delivery);
		}
		return new TickResult(deliveries);
	}
	public RouteStatus status(UUID messageId) {
		PendingRoute route = routes.get(Objects.requireNonNull(messageId, "messageId"));
		if (route == null)
			throw new IllegalArgumentException("unknown radio message");
		return route.status;
	}

	public int listenerOccupancy(RadioEndpoint.Key key) {
		return listenerOccupancy.getOrDefault(Objects.requireNonNull(key, "key"), 0);
	}

	public Map<RadioEndpoint.Key, Integer> listenerOccupancySnapshot() {
		return Map.copyOf(listenerOccupancy);
	}

	/** Read-only bounded rows for routes that contain this exact endpoint. */
 public List<String> diagnosticRouteRows(RadioEndpoint.Key key,int limit) {
  if(limit<1||limit>com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryAdvancedGoggleProjection.MAX_ROWS)throw new IllegalArgumentException("invalid route diagnostics limit");
  var result=new ArrayList<String>();
  for(var route:routes.values()) {
   if(result.size()>=limit)break;
   if(route.path.stream().noneMatch(hop->hop.key().equals(key)))continue;
   result.add("message="+route.current.messageId()+" status="+route.status+" hop="+route.nextHopIndex);
   for(var hop:route.path){if(result.size()>=limit)break;result.add("path="+hop.key()+" generation="+hop.generation());}
   for(var visited:route.current.subnetTrace().visitedClusters()){if(result.size()>=limit)break;result.add("visitedCluster="+visited);}
   for(var visited:route.current.subnetTrace().visitedNetworks()){if(result.size()>=limit)break;result.add("visitedNetwork="+visited);}
  }
  return List.copyOf(result);
 }

	public RouteProgress routeProgress(UUID messageId) {
		PendingRoute route = routes.get(Objects.requireNonNull(messageId, "messageId"));
		if (route == null) throw new IllegalArgumentException("unknown radio message");
		return new RouteProgress(route.current, route.nextHopIndex,
			Optional.ofNullable(route.heldRelayListener));
	}

	public int appliedOfferCount(UUID leaseId) {
		LeaseState state = leases.get(Objects.requireNonNull(leaseId, "leaseId"));
		return state == null ? 0 : state.appliedOffers;
	}

	public int appliedResultCount(UUID leaseId) {
		LeaseState state = leases.get(Objects.requireNonNull(leaseId, "leaseId"));
		return state == null ? 0 : state.results.size();
	}

	public Optional<RemoteExpansionResult> result(UUID leaseId, long revision) {
		if (revision < 0) throw new IllegalArgumentException("revision cannot be negative");
		LeaseState state = leases.get(Objects.requireNonNull(leaseId, "leaseId"));
		return state == null ? Optional.empty() : Optional.ofNullable(state.results.get(revision));
	}

	/** True only while this exact revision-fenced lease remains active in the router. */
	public boolean hasExactLease(UUID leaseId, long revision) {
		leaseId = Objects.requireNonNull(leaseId, "lease id");
		if (revision < 0) return false;
		LeaseState state = leases.get(leaseId);
		return state != null && state.lease.revisionFence() == revision;
	}
	public Optional<UUID> goalOwner(UUID leaseId) {
		LeaseState state = leases.get(Objects.requireNonNull(leaseId, "leaseId"));
		return state == null ? Optional.empty() : Optional.of(state.lease.goalOwnerClusterId());
	}

	public Optional<WaitReason> leaseWaitReason(UUID leaseId) {
		LeaseState state = leases.get(Objects.requireNonNull(leaseId, "leaseId"));
		return state == null || state.waitingRouteMessages.isEmpty()
			? Optional.empty() : Optional.of(WaitReason.ROUTE);
	}

	public int activeLeaseCount() { return leases.size(); }

	public int retiredLeaseLineageCount() { return retiredLeaseLineages.size(); }

	/** Diagnostic size of the actual per-tick route scan set. */
	public int activeRouteCount() { return routes.size(); }

	public int activeLeaseRouteCount() {
		return (int) routes.values().stream().filter(route ->
			leaseIdOrNull(route.original.payload()) != null).count();
	}

	/** Diagnostic size of the per-tick route scan set owned by one exact lease. */
	public int activeLeaseRouteCount(UUID leaseId) {
		leaseId = Objects.requireNonNull(leaseId, "lease id");
		UUID exactLeaseId = leaseId;
		return (int) routes.values().stream().filter(route ->
			exactLeaseId.equals(leaseIdOrNull(route.original.payload()))).count();
	}

	/** Preflights the exact no-route-or-final-route condition for an enclosing durable batch. */
	public boolean canRetireDeliveredRoute(UUID messageId) {
		PendingRoute route = routes.get(Objects.requireNonNull(messageId, "messageId"));
		return route == null || route.status == RouteStatus.DELIVERED;
	}

	/** Removes only a final route whose recipient-side state is already durable. */
	public boolean retireDeliveredRoute(UUID messageId) {
		PendingRoute route = routes.get(Objects.requireNonNull(messageId, "messageId"));
		if (route == null) return true;
		if (route.status != RouteStatus.DELIVERED) return false;
		if (route.heldRelayListener != null) releaseListener(route.heldRelayListener);
		routes.remove(messageId);
		return true;
	}

	/**
	 * Removes an obsolete route after the matching lease terminal tombstone is durable. Unlike a
	 * normal delivered-route retirement this also retires queued or route-waiting protocol
	 * envelopes, so an aborted OFFER cannot replay after restart/reconciliation.
	 */
	public boolean retireTerminalRoute(UUID messageId) {
		PendingRoute route = routes.get(Objects.requireNonNull(messageId, "messageId"));
		if (route == null) return true;
		UUID leaseId = leaseIdOrNull(route.original.payload());
		if (leaseId == null || retiredLeaseLineages.values().stream()
			.noneMatch(retired -> retired.leaseId().equals(leaseId))) return false;
		if (route.heldRelayListener != null) releaseListener(route.heldRelayListener);
		routes.remove(messageId);
		return true;
	}

	/**
	 * Atomically replaces a completed, material-consumed lease with one high-watermark per
	 * logical source child. This is idempotent across crash replay and uses the configured
	 * protocol evidence capacity rather than a Run-count limit.
	 */
	public boolean retireCompletedLease(RemoteProcessLease lease,
		RemoteExpansionResult result) {
		Optional<CompletedLeaseRetirement> prepared = prepareCompletedLeaseRetirement(lease,
			result);
		if (prepared.isEmpty()) return false;
		CompletedLeaseRetirement retirement = prepared.orElseThrow();
		leaseProtocolStore.persist(retirement.state());
		installPersistedLeaseProtocolState(retirement.state());
		removeIssuedMaterialPermits(retirement.leaseId());
		return true;
	}

	/**
	 * Preflights the durable replacement without mutating router state. A composition root may
	 * publish this state in the same journal batch as its own exchange/job tombstone, then call
	 * {@link #installPersistedLeaseProtocolState(LeaseProtocolState)}.
	 */
	public Optional<CompletedLeaseRetirement> prepareCompletedLeaseRetirement(
		RemoteProcessLease lease, RemoteExpansionResult result) {
		lease = Objects.requireNonNull(lease, "lease");
		result = Objects.requireNonNull(result, "result");
		RemoteLeaseLineage lineage = RemoteLeaseLineage.from(lease);
		LeaseState active = leases.get(lease.leaseId());
		RetiredLeaseState retired = retiredLeaseLineages.get(lineage);
		if (active == null) return retired != null && retired.matches(lease, result)
			? Optional.of(new CompletedLeaseRetirement(lease.leaseId(),
				new LeaseProtocolState(durableLeases(), retiredLeaseLineages)))
			: Optional.empty();
		validateSameLease(active, lease, active.source, active.receiver);
		long revision = lease.revisionFence();
		RemoteExpansionRevision accepted = active.revisions.get(revision);
		RemoteExpansionResult delivered = active.results.get(revision);
		if (accepted == null || !active.acknowledgedRevisions.contains(revision)
			|| !active.consumedMaterialRevisions.contains(revision)
			|| delivered == null || !delivered.equals(result)
			|| !result.matches(lease.expansionRequest(), accepted)
			|| !active.waitingRouteMessages.isEmpty()) return Optional.empty();
		RetiredLeaseState nextRetired = RetiredLeaseState.completed(lineage, lease, result);
		if (retired != null && retired.revisionFence() >= revision) {
			if (!retired.matches(lease, result)) return Optional.empty();
			nextRetired = retired;
		}
		LinkedHashMap<UUID, DurableLeaseState> nextLeases = durableLeases();
		nextLeases.remove(lease.leaseId());
		LinkedHashMap<RemoteLeaseLineage, RetiredLeaseState> nextRetiredLineages =
			new LinkedHashMap<>(retiredLeaseLineages);
		nextRetiredLineages.put(lineage, nextRetired);
		LeaseProtocolState state = checkedLeaseProtocolState(nextLeases,
			nextRetiredLineages);
		return Optional.of(new CompletedLeaseRetirement(lease.leaseId(), state));
	}

	/** Preflights cancellation/migration retirement without requiring a result contract. */
	public Optional<TerminalLeaseRetirement> prepareTerminalLeaseRetirement(
		RemoteProcessLease lease, LeaseTerminalOutcome outcome) {
		lease = Objects.requireNonNull(lease, "lease");
		outcome = Objects.requireNonNull(outcome, "outcome");
		if (outcome == LeaseTerminalOutcome.COMPLETED)
			throw new IllegalArgumentException("Completed leases require result retirement");
		RemoteLeaseLineage lineage = RemoteLeaseLineage.from(lease);
		LeaseState active = leases.get(lease.leaseId());
		RetiredLeaseState retired = retiredLeaseLineages.get(lineage);
		if (active == null) {
			if (retired != null && retired.matchesTerminal(lease, outcome))
				return Optional.of(new TerminalLeaseRetirement(lease.leaseId(), outcome,
					new LeaseProtocolState(durableLeases(), retiredLeaseLineages)));
			if (activeLineage(lineage).isPresent()) return Optional.empty();
			if (retired != null && retired.revisionFence() >= lease.revisionFence())
				return Optional.empty();
			LinkedHashMap<RemoteLeaseLineage, RetiredLeaseState> next =
				new LinkedHashMap<>(retiredLeaseLineages);
			next.put(lineage, RetiredLeaseState.terminal(lineage, lease, outcome));
			return Optional.of(new TerminalLeaseRetirement(lease.leaseId(), outcome,
				checkedLeaseProtocolState(durableLeases(), next)));
		}
		validateSameLease(active, lease, active.source, active.receiver);
		if (!active.consumedMaterialRevisions.isEmpty()) return Optional.empty();
		RetiredLeaseState nextRetired = RetiredLeaseState.terminal(lineage, lease, outcome);
		if (retired != null && retired.revisionFence() >= lease.revisionFence()) {
			if (!retired.matchesTerminal(lease, outcome)) return Optional.empty();
			nextRetired = retired;
		}
		LinkedHashMap<UUID, DurableLeaseState> nextLeases = durableLeases();
		nextLeases.remove(lease.leaseId());
		LinkedHashMap<RemoteLeaseLineage, RetiredLeaseState> nextRetiredLineages =
			new LinkedHashMap<>(retiredLeaseLineages);
		nextRetiredLineages.put(lineage, nextRetired);
		return Optional.of(new TerminalLeaseRetirement(lease.leaseId(), outcome,
			checkedLeaseProtocolState(nextLeases, nextRetiredLineages)));
	}

	/** Installs only an already-durable, fully validated lease-protocol snapshot. */
	public void installPersistedLeaseProtocolState(LeaseProtocolState state) {
		state = Objects.requireNonNull(state, "state");
		checkedLeaseProtocolState(state.leases(), state.retiredLineages());
		leases.clear();
		state.leases().forEach((leaseId, durable) ->
			leases.put(leaseId, LeaseState.restore(durable)));
		retiredLeaseLineages.clear();
		retiredLeaseLineages.putAll(state.retiredLineages());
	}

	public void completePersistedLeaseRetirement(CompletedLeaseRetirement retirement) {
		retirement = Objects.requireNonNull(retirement, "retirement");
		installPersistedLeaseProtocolState(retirement.state());
		removeIssuedMaterialPermits(retirement.leaseId());
	}

	public void completePersistedLeaseRetirement(TerminalLeaseRetirement retirement) {
		retirement = Objects.requireNonNull(retirement, "retirement");
		installPersistedLeaseProtocolState(retirement.state());
		removeIssuedMaterialPermits(retirement.leaseId());
	}

	private void removeIssuedMaterialPermits(UUID retiredLeaseId) {
		issuedMaterialPermits.entrySet().removeIf(entry ->
			entry.getValue().leaseId.equals(retiredLeaseId));
	}

	/**
	 * Issues a router-owned one-shot capability. The returned value is not commit authority
	 * until this router atomically consumes it.
	 */
	public Optional<MaterialIntentPermit> issueMaterialIntentPermit(RadioEndpoint.Key receiver,
		UUID leaseId, long revision) {
		receiver = Objects.requireNonNull(receiver, "receiver");
		LeaseState state = leases.get(Objects.requireNonNull(leaseId, "leaseId"));
		if (!materialAuthorizationAvailable(state, receiver, revision))
			return Optional.empty();
		MaterialIntentPermit permit = new MaterialIntentPermit(UUID.randomUUID(), leaseId,
			state.lease.sourceGoalId(),
			state.lease.sourceProcessId(), state.lease.sourceClusterId(), receiver,
			state.receiver.generation(), revision);
		issuedMaterialPermits.put(permit.nonce, permit);
		return Optional.of(permit);
	}

	/**
	 * The sole material-boundary authority. A token is burned before all live fences are
	 * rechecked, so success, replay, revocation, and generation changes are one-shot.
	 */
	public boolean consumeMaterialIntentPermit(MaterialIntentPermit permit) {
		permit = Objects.requireNonNull(permit, "permit");
		MaterialIntentPermit issued = issuedMaterialPermits.remove(permit.nonce);
		if (issued != permit)
			return false;
		LeaseState state = leases.get(permit.leaseId);
		if (!materialAuthorizationAvailable(state, permit.receiver,
			permit.acknowledgedRevision))
			return false;
		LeaseState updated = state.copy();
		updated.consumedMaterialRevisions.add(permit.acknowledgedRevision);
		persistLease(updated);
		return true;
	}

	/** Read-only preview; callers must consume a router-issued permit to commit. */
	public boolean canCommitMaterialIntent(RadioEndpoint.Key receiver, UUID leaseId,
		long revision) {
		receiver = Objects.requireNonNull(receiver, "receiver");
		return materialAuthorizationAvailable(
			leases.get(Objects.requireNonNull(leaseId, "leaseId")), receiver, revision);
	}

	/** Durable idempotent material authorization used by the production composition root. */
	public boolean authorizeMaterialIntent(RadioEndpoint.Key receiver, UUID leaseId,
		long revision) {
		receiver = Objects.requireNonNull(receiver, "receiver");
		leaseId = Objects.requireNonNull(leaseId, "leaseId");
		LeaseState state = leases.get(leaseId);
		if (state != null && state.receiver.key().equals(receiver)
			&& state.consumedMaterialRevisions.contains(revision)) return true;
		Optional<MaterialIntentPermit> permit = issueMaterialIntentPermit(receiver, leaseId,
			revision);
		return permit.isPresent() && consumeMaterialIntentPermit(permit.orElseThrow());
	}

	/**
	 * Copy-on-write material handoff proposal for an enclosing journal batch. The live router and
	 * its store remain unchanged until the caller durably publishes and installs {@link #state()}.
	 */
	public Optional<PreparedMaterialAuthorization> prepareMaterialIntentAuthorization(
		RadioEndpoint.Key receiver, UUID leaseId, long revision) {
		receiver = Objects.requireNonNull(receiver, "receiver");
		leaseId = Objects.requireNonNull(leaseId, "leaseId");
		LeaseState active = leases.get(leaseId);
		if (active != null && active.receiver.key().equals(receiver)
			&& active.consumedMaterialRevisions.contains(revision))
			return Optional.of(new PreparedMaterialAuthorization(leaseId, receiver, revision,
				checkedLeaseProtocolState(durableLeases(), retiredLeaseLineages)));
		if (!materialAuthorizationAvailable(active, receiver, revision)) return Optional.empty();
		LeaseState updated = active.copy();
		updated.consumedMaterialRevisions.add(revision);
		LinkedHashMap<UUID, DurableLeaseState> next = durableLeases();
		next.put(leaseId, updated.snapshot());
		return Optional.of(new PreparedMaterialAuthorization(leaseId, receiver, revision,
			checkedLeaseProtocolState(next, retiredLeaseLineages)));
	}

	/** Read-only durable handoff evidence, including after reload. */
	public boolean materialIntentAuthorized(RadioEndpoint.Key receiver, UUID leaseId,
		long revision) {
		receiver = Objects.requireNonNull(receiver, "receiver");
		LeaseState state = leases.get(Objects.requireNonNull(leaseId, "leaseId"));
		return state != null && state.receiver.key().equals(receiver)
			&& state.consumedMaterialRevisions.contains(revision);
	}

	private boolean materialAuthorizationAvailable(LeaseState state,
		RadioEndpoint.Key receiver, long revision) {
		RadioEndpoint currentSource = state == null ? null : exact(state.source);
		RadioEndpoint currentReceiver = state == null ? null : exact(state.receiver);
		return state != null && state.appliedOffers > 0
			&& state.receiver.key().equals(receiver)
			&& currentSource != null && currentReceiver != null
			&& currentReceiver.supports(state.lease.requiredCapability())
			&& revision >= state.lease.revisionFence()
			&& state.acknowledgedRevisions.contains(revision)
			&& !state.consumedMaterialRevisions.contains(revision);
	}

	private Delivery advance(PendingRoute route, RadioTopologyFrame frame) {
		if (!boundaryAuthorityAvailable(route.original)) {
			routeUnavailable(route);
			return null;
		}
		RadioEndpoint.Hop fromHop = route.path.get(route.nextHopIndex - 1);
		RadioEndpoint.Hop toHop = route.path.get(route.nextHopIndex);
		RadioEndpoint from = exact(fromHop);
		RadioEndpoint to = exact(toHop);
		if (from == null || to == null) {
			routeUnavailable(route);
			return null;
		}
		RadioLinkPolicy.EdgeResult link = linkPolicy.edge(from, to, frame);
		if (!link.reachable() || !link.virtual()
			&& (!from.frequency().equals(route.current.frequency())
				|| !to.frequency().equals(route.current.frequency()))) {
			routeUnavailable(route);
			return null;
		}
		if (!agentProtocolAdmissionAvailable(route.current)) {
			route.status = RouteStatus.WAITING_PROTOCOL_CAPACITY;
			return null;
		}
		if (!leaseProtocolAdmissionAvailable(route.current)) {
			route.status = RouteStatus.WAITING_PROTOCOL_CAPACITY;
			return null;
		}

		boolean finalRecipient = route.nextHopIndex == route.path.size() - 1;
		if (finalRecipient
			&& route.current.payload() instanceof ProcessProtocolMessage.LeaseOffer offer
			&& !to.supports(offer.lease().requiredCapability())) {
			routeUnavailable(route);
			return null;
		}
		if (link.requiresRemoteListener()
			&& listenerOccupancy(to.key()) >= to.listenerCapacity()) {
			route.status = markLeaseWaiting(route) ? RouteStatus.WAITING_ROUTE
				: RouteStatus.WAITING_PROTOCOL_CAPACITY;
			return null;
		}
		if (link.requiresRemoteListener())
			listenerOccupancy.merge(to.key(), 1, Integer::sum);
		RadioEndpoint.Key previousHeldListener = route.heldRelayListener;
		route.current = route.current.advanceHop();
		boolean duplicate = finalRecipient && applyProtocol(route.current, to.key());
		Delivery delivery = new Delivery(route.current, to.key(), finalRecipient,
			link.virtual(), link.requiresRemoteListener()
				? Optional.of(ProcessKind.REMOTE_LISTENER) : Optional.empty(), duplicate);

		if (previousHeldListener != null)
			releaseListener(previousHeldListener);
		if (finalRecipient) {
			if (link.requiresRemoteListener())
				releaseListener(to.key());
			route.heldRelayListener = null;
			route.status = RouteStatus.DELIVERED;
			clearLeaseWait(route);
		} else {
			route.heldRelayListener = link.requiresRemoteListener() ? to.key() : null;
			route.nextHopIndex++;
			route.status = RouteStatus.IN_TRANSIT;
		}
		return delivery;
	}

	private boolean agentProtocolAdmissionAvailable(RadioEnvelope envelope) {
		if (!(envelope.payload() instanceof AgentProtocolMessage message)) return true;
		AgentPeerState current = agentPeers.get(message.key());
		if (current != null) {
			current.binding().validate(envelope, message);
			return true;
		}
		if (!(message instanceof AgentProtocolMessage.Offer offer))
			throw new IllegalArgumentException("Agent protocol message has no delivered OFFER binding");
		AgentPeerBinding.from(envelope, offer);
		return agentPeers.size() < agentProtocolEvidenceCapacity;
	}

	private boolean leaseProtocolAdmissionAvailable(RadioEnvelope envelope) {
		if (!(envelope.payload() instanceof ProcessProtocolMessage.LeaseOffer offer)) return true;
		RemoteProcessLease lease = offer.lease();
		LeaseState exact = leases.get(lease.leaseId());
		if (exact != null) {
			validateSameLease(exact, lease, envelope.sourceHop(), envelope.targetHop());
			return true;
		}
		RemoteLeaseLineage lineage = RemoteLeaseLineage.from(lease);
		if (activeLineage(lineage).isPresent()) return false;
		RetiredLeaseState retired = retiredLeaseLineages.get(lineage);
		if (retired != null && lease.revisionFence() <= retired.revisionFence()) return true;
		long occupied = (long) leases.size() + retiredLeaseLineages.size();
		return occupied < leaseProtocolEvidenceCapacity || retired != null;
	}

	private RadioEndpoint exact(RadioEndpoint.Hop hop) {
		RadioEndpoint endpoint = endpoints.get(hop.key());
		return endpoint != null && endpoint.generation() == hop.generation() ? endpoint : null;
	}

	private RadioTopologyFrame legacyTopologyFrame() {
		Map<RadioEndpoint.Key, RadioTopologyFrame.Sample> samples = new LinkedHashMap<>();
		for (RadioEndpoint endpoint : endpoints.values()) {
			SpaceAddress source = endpoint.address();
			samples.put(endpoint.key(), new RadioTopologyFrame.Sample(source.dimension(), source,
				net.minecraft.world.phys.Vec3.atCenterOf(source.localPos()), true,
				endpoint.generation(), Optional.ofNullable(source.subLevelId())));
		}
		return new RadioTopologyFrame(0, false, samples);
	}

	/**
	 * Every explicit radio path is constrained by the envelope's immutable subnet evidence.
	 * Same-Cluster relays remain virtual, but returning to a prior Cluster is a rejected loop.
	 */
	private void validateSubnetStructure(RadioEnvelope envelope,
		List<RadioEndpoint.Hop> path) {
		SubnetRouteTrace trace = envelope.subnetTrace();
		if (!trace.hasVisitedCluster(envelope.sourceClusterId()))
			throw new IllegalArgumentException("subnet trace does not include the route source");
		SubnetBoundaryTransition transition = envelope.boundaryTransition();
		if (transition != null
			&& (!matchesBoundaryEnvelope(envelope, transition.binding())
				|| !transition.exactlyExtends(trace)))
			throw new IllegalArgumentException("malformed subnet boundary transition");
		Set<UUID> visitedClusters = new HashSet<>(trace.visitedClusters());
		UUID priorCluster = envelope.sourceClusterId();
		for (int index = 1; index < path.size(); index++) {
			UUID nextCluster = path.get(index).key().clusterId();
			if (nextCluster.equals(priorCluster)) continue;
			boolean admittedBoundaryTarget = index == path.size() - 1
				&& nextCluster.equals(envelope.targetClusterId())
				&& transition != null;
			if (!admittedBoundaryTarget && !visitedClusters.add(nextCluster))
				throw new IllegalArgumentException("radio route re-enters a visited subnet Cluster");
			if (visitedClusters.size() > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
				throw new IllegalArgumentException("radio route exceeds the subnet trace bound");
			priorCluster = nextCluster;
		}
	}

	/** Live binding/endpoints are availability, not part of immutable durable-record validation. */
	private boolean boundaryAuthorityAvailable(RadioEnvelope envelope) {
		SubnetBoundaryTransition transition = envelope.boundaryTransition();
		if (transition == null) return true;
		SubnetBoundaryBinding binding = boundaryBindings.get(transition.sourceClusterId());
		return transition.binding().equals(binding)
			&& matchesBoundaryEnvelope(envelope, binding)
			&& matchesBoundaryEndpoints(envelope, binding);
	}

	private static boolean matchesBoundaryEnvelope(RadioEnvelope envelope,
		SubnetBoundaryBinding binding) {
		return binding != null
			&& binding.sourceClusterId().equals(envelope.sourceClusterId())
			&& binding.targetClusterId().equals(envelope.targetClusterId())
			&& binding.listenerFrequency().equals(envelope.frequency())
			&& binding.sourceHop().equals(envelope.sourceHop())
			&& binding.targetHop().equals(envelope.targetHop());
	}

	private boolean matchesBoundaryEndpoints(RadioEnvelope envelope,
		SubnetBoundaryBinding binding) {
		if (binding == null) return false;
		RadioEndpoint source = exact(envelope.sourceHop());
		RadioEndpoint target = exact(envelope.targetHop());
		return source != null && target != null
			&& source.hop().equals(binding.sourceHop())
			&& target.hop().equals(binding.targetHop())
			&& source.frequency().equals(binding.listenerFrequency())
			&& target.frequency().equals(binding.listenerFrequency());
	}

	private static boolean supersedes(SubnetBoundaryBinding replacement,
		SubnetBoundaryBinding previous) {
		return replacement.sourceHop().key().equals(previous.sourceHop().key())
			&& replacement.targetHop().key().equals(previous.targetHop().key())
			&& replacement.sourceHop().generation() > previous.sourceHop().generation()
			&& replacement.targetHop().generation() > previous.targetHop().generation();
	}

	private boolean persistAuthority(Map<RadioEndpoint.Key, EndpointAuthority> endpoints,
		Map<UUID, BoundaryAuthority> boundaries) {
		if (durableAuthorityRevision >= Long.MAX_VALUE - 1 || authorityRevision >= Long.MAX_VALUE - 1
			|| endpoints.size() > endpointGenerationFenceCapacity
			|| boundaries.size() > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
			return false;
		try {
			AuthorityState expected = authorityState();
			AuthorityState replacement = new AuthorityState(durableAuthorityRevision + 1,
				endpoints, boundaries);
			if (!authorityStore.compareAndSet(expected, replacement)) return false;
			installAuthorityState(replacement);
			return true;
		} catch (RuntimeException persistenceFailure) {
			return false;
		}
	}

	private AuthorityState authorityState() {
		return new AuthorityState(durableAuthorityRevision, endpointAuthorities,
			boundaryAuthorities);
	}

	private void installAuthorityState(AuthorityState state) {
		state = Objects.requireNonNull(state, "authority state");
		endpointAuthorities.clear();
		endpointAuthorities.putAll(state.endpoints());
		highestEndpointGenerations.clear();
		state.endpoints().forEach((key, authority) ->
			highestEndpointGenerations.put(key, authority.highestGeneration()));
		boundaryAuthorities.clear();
		boundaryAuthorities.putAll(state.boundaries());
		durableAuthorityRevision = state.revision();
		authorityRevision = Math.max(authorityRevision, durableAuthorityRevision);
	}
	private void routeUnavailable(PendingRoute route) {
		if (route.heldRelayListener != null)
			releaseListener(route.heldRelayListener);
		route.heldRelayListener = null;
		route.status = markLeaseWaiting(route) ? RouteStatus.WAITING_ROUTE
			: RouteStatus.WAITING_PROTOCOL_CAPACITY;
	}

	private void releaseListener(RadioEndpoint.Key key) {
		int occupied = listenerOccupancy.getOrDefault(key, 0);
		if (occupied <= 1)
			listenerOccupancy.remove(key);
		else
			listenerOccupancy.put(key, occupied - 1);
	}

	private boolean applyProtocol(RadioEnvelope envelope, RadioEndpoint.Key recipient) {
		ProcessProtocolMessage payload = envelope.payload();
		if (payload instanceof ProcessProtocolMessage.Data)
			return false;
		if (payload instanceof AgentProtocolMessage message)
			return applyAgentProtocol(envelope, message);
		if (payload instanceof RecoveryProtocolMessage recovery) {
			recovery.validateEnvelope(envelope);
			return false;
		}
		if (payload instanceof ProcessProtocolMessage.LeaseOffer offer) {
			RemoteProcessLease lease = offer.lease();
			LeaseState state = leases.get(lease.leaseId());
			if (state == null) {
				RemoteLeaseLineage lineage = RemoteLeaseLineage.from(lease);
				RetiredLeaseState retired = retiredLeaseLineages.get(lineage);
				if (retired != null && lease.revisionFence() <= retired.revisionFence())
					return true;
				if (activeLineage(lineage).isPresent())
					throw new IllegalStateException("logical remote child lease overlap");
				long occupied = (long) leases.size() + retiredLeaseLineages.size();
				if (occupied >= leaseProtocolEvidenceCapacity && retired == null)
					throw new IllegalStateException("lease protocol correctness evidence is full");
				state = new LeaseState(lease, envelope.sourceHop(), envelope.targetHop());
			} else {
				validateSameLease(state, lease, envelope.sourceHop(), envelope.targetHop());
				state = state.copy();
			}
			boolean duplicate = state.appliedOffers > 0;
			if (!duplicate) {
				state.appliedOffers = 1;
				persistLease(state);
			}
			return duplicate;
		}

		UUID leaseId = leaseId(payload);
		LeaseState state = requireLease(leaseId);
		validateLeaseDirection(envelope, recipient, state, payload);
		if (payload instanceof ProcessProtocolMessage.LeaseRevision revision) {
			if (revision.revision() < state.lease.revisionFence())
				return true;
			if (revision.revision() != state.lease.revisionFence()
				|| !revision.calculationState().matches(state.lease.expansionRequest()))
				throw new IllegalArgumentException(
					"remote revision does not match its immutable lease request");
			RemoteExpansionRevision previous = state.revisions.get(revision.revision());
			if (previous != null && !previous.equals(revision.calculationState()))
				throw new IllegalStateException("conflicting remote revision payload");
			if (previous == null) {
				if (state.revisions.size() >= leaseProtocolEvidenceCapacity)
					throw new IllegalStateException("lease revision evidence is full");
				LeaseState updated = state.copy();
				updated.revisions.put(revision.revision(), revision.calculationState());
				persistLease(updated);
			}
			return previous != null;
		}
		if (payload instanceof ProcessProtocolMessage.RevisionAcknowledgement acknowledgement) {
			if (!state.revisions.containsKey(acknowledgement.revision()))
				return true;
			if (state.acknowledgedRevisions.contains(acknowledgement.revision())) return true;
			LeaseState updated = state.copy();
			updated.acknowledgedRevisions.add(acknowledgement.revision());
			persistLease(updated);
			return false;
		}
		ProcessProtocolMessage.LeaseResult result = (ProcessProtocolMessage.LeaseResult) payload;
		if (result.revision() < state.lease.revisionFence())
			return true;
		RemoteExpansionRevision accepted = state.revisions.get(result.revision());
		if (accepted == null || !state.acknowledgedRevisions.contains(result.revision())
			|| !result.result().matches(state.lease.expansionRequest(), accepted))
			throw new IllegalArgumentException(
				"remote result lacks its exact acknowledged receiver revision");
		RemoteExpansionResult previous = state.results.get(result.revision());
		if (previous != null && !previous.equals(result.result()))
			throw new IllegalStateException("conflicting remote result payload");
		if (previous == null) {
			if (state.results.size() >= leaseProtocolEvidenceCapacity)
				throw new IllegalStateException("lease result evidence is full");
			LeaseState updated = state.copy();
			updated.results.put(result.revision(), result.result());
			persistLease(updated);
		}
		return previous != null;
	}

	private static void validateLeaseOfferEnvelope(RadioEnvelope envelope) {
		if (!(envelope.payload() instanceof ProcessProtocolMessage.LeaseOffer offer))
			return;
		RemoteProcessLease lease = offer.lease();
		if (!lease.sourceClusterId().equals(envelope.sourceClusterId())
			|| !lease.receiverClusterId().equals(envelope.targetClusterId())
			|| lease.sourceGeneration() != envelope.sourceGeneration()
			|| lease.receiverGeneration() != envelope.targetGeneration())
			throw new IllegalArgumentException("lease offer does not match its route fence");
	}

	private void validateLeaseAdmission(RadioEnvelope envelope) {
		if (!(envelope.payload() instanceof ProcessProtocolMessage.LeaseOffer offer)) return;
		RemoteProcessLease lease = offer.lease();
		LeaseState exact = leases.get(lease.leaseId());
		if (exact != null) {
			validateSameLease(exact, lease, envelope.sourceHop(), envelope.targetHop());
			return;
		}
		RemoteLeaseLineage lineage = RemoteLeaseLineage.from(lease);
		Optional<LeaseState> active = activeLineage(lineage);
		if (active.isPresent())
			throw new IllegalArgumentException("logical remote child already has an active lease");
		RetiredLeaseState retired = retiredLeaseLineages.get(lineage);
		if (retired != null && lease.revisionFence() <= retired.revisionFence())
			throw new IllegalArgumentException("remote lease is below its durable retired fence");
	}

	private Optional<LeaseState> activeLineage(RemoteLeaseLineage lineage) {
		return leases.values().stream().filter(state ->
			RemoteLeaseLineage.from(state.lease).equals(lineage)).findFirst();
	}

	private void validateAgentEnvelope(RadioEnvelope envelope) {
		if (!(envelope.payload() instanceof AgentProtocolMessage message)) return;
		boolean targetToSource = message instanceof AgentProtocolMessage.Status
			|| message instanceof AgentProtocolMessage.Terminal;
		long routedSourceGeneration = targetToSource ? envelope.targetGeneration()
			: envelope.sourceGeneration();
		long routedAgentGeneration = targetToSource ? envelope.sourceGeneration()
			: envelope.targetGeneration();
		if (message.key().sourceGeneration() != routedSourceGeneration
			|| message.key().agentGeneration() != routedAgentGeneration)
			throw new IllegalArgumentException("Agent protocol key differs from its route fence");
		if (message instanceof AgentProtocolMessage.Offer offer
			&& !offer.ticket().sourceClusterId().equals(envelope.sourceClusterId()))
			throw new IllegalArgumentException("Agent offer source differs from its route owner");
		AgentPeerState known = agentPeers.get(message.key());
		if (known == null) {
			if (!(message instanceof AgentProtocolMessage.Offer))
				throw new IllegalArgumentException("Agent protocol message has no delivered OFFER binding");
			return;
		}
		known.binding().validate(envelope, message);
	}

	private boolean applyAgentProtocol(RadioEnvelope envelope, AgentProtocolMessage message) {
		AgentPeerState current = agentPeers.get(message.key());
		if (current == null) {
			if (agentPeers.size() >= agentProtocolEvidenceCapacity)
				throw new IllegalStateException("Agent protocol correctness evidence is full");
			AgentProtocolMessage.Offer offer = (AgentProtocolMessage.Offer) message;
			AgentPeerState admitted = new AgentPeerState(AgentPeerBinding.from(envelope, offer),
				offer.fingerprint(), Optional.empty(), Optional.empty(), Map.of());
			return persistAgentPeer(message.key(), admitted);
		} else {
			current.binding().validate(envelope, message);
		}
		String fingerprint = message.fingerprint();
		if (message instanceof AgentProtocolMessage.Offer) {
			if (!current.offerFingerprint().equals(fingerprint))
				throw new IllegalStateException("conflicting immutable Agent OFFER evidence");
			return true;
		}
		if (message instanceof AgentProtocolMessage.StatusQuery)
			return persistAgentControl(message.key(), current, fingerprint, true);
		if (message instanceof AgentProtocolMessage.Cancel)
			return persistAgentControl(message.key(), current, fingerprint, false);
		String slot = agentMessageSlot(message);
		if (message instanceof AgentProtocolMessage.Terminal terminal) {
			String acknowledged = current.immutableMessages().get(
				"ACK:" + terminal.record().revision());
			if (acknowledged != null) {
				if (!acknowledged.startsWith(fingerprint + "\u0000"))
					throw new IllegalStateException("conflicting acknowledged terminal evidence");
				return true;
			}
			return persistAgentTerminal(message.key(), current, terminal, fingerprint, slot);
		}
		if (message instanceof AgentProtocolMessage.Status status)
			return persistAgentStatus(message.key(), current, status, fingerprint, slot);
		String previous = current.immutableMessages().get(slot);
		if (previous != null) {
			String expected = message instanceof AgentProtocolMessage.EvidenceAck
				? previous.substring(previous.indexOf('\u0000') + 1) : previous;
			if (!expected.equals(fingerprint))
				throw new IllegalStateException("conflicting immutable Agent protocol evidence");
			return true;
		}
		LinkedHashMap<String, String> messages =
			new LinkedHashMap<>(current.immutableMessages());
		if (message instanceof AgentProtocolMessage.EvidenceAck ack) {
			String terminalSlot = "TERMINAL:" + ack.revision();
			String terminalFingerprint = messages.remove(terminalSlot);
			if (terminalFingerprint == null)
				throw new IllegalStateException("Agent ACK lacks immutable terminal evidence");
			messages.remove("STATUS:" + ack.revision());
			messages.put(slot, terminalFingerprint + "\u0000" + fingerprint);
		} else {
			if (messages.size() >= agentProtocolEvidenceCapacity)
				throw new IllegalStateException("Agent protocol message evidence is full");
			messages.put(slot, fingerprint);
		}
		AgentPeerState updated = new AgentPeerState(current.binding(), current.offerFingerprint(),
			current.statusQueryFingerprint(), current.cancelFingerprint(), messages);
		return persistAgentPeer(message.key(), updated);
	}

	private boolean persistAgentTerminal(RemoteRequestKey key, AgentPeerState current,
		AgentProtocolMessage.Terminal terminal, String fingerprint, String slot) {
		String previous = current.immutableMessages().get(slot);
		if (previous != null) {
			if (!previous.equals(fingerprint))
				throw new IllegalStateException("conflicting immutable Agent terminal evidence");
			return true;
		}
		LinkedHashMap<String, String> messages = new LinkedHashMap<>(current.immutableMessages());
		long terminalRevision = terminal.record().revision();
		for (String knownSlot : List.copyOf(messages.keySet())) {
			if (knownSlot.startsWith("TERMINAL:") || knownSlot.startsWith("ACK:"))
				throw new IllegalStateException("Agent terminal conflicts with a durable high-watermark");
			if (knownSlot.equals("STATUS:MISSING")) {
				messages.remove(knownSlot);
				continue;
			}
			if (!knownSlot.startsWith("STATUS:")) continue;
			long statusRevision = protocolSlotRevision(knownSlot, "STATUS:");
			if (statusRevision > terminalRevision)
				throw new IllegalStateException("Agent terminal revision regressed below durable status");
			if (statusRevision == terminalRevision) {
				String expectedStatus = new AgentProtocolMessage.Status(key,
					AgentProtocolMessage.StatusCode.PRESENT, Optional.of(terminal.record()))
					.fingerprint();
				if (!messages.get(knownSlot).equals(expectedStatus))
					throw new IllegalStateException("Agent terminal conflicts with durable status");
			}
			messages.remove(knownSlot);
		}
		if (messages.size() >= agentProtocolEvidenceCapacity)
			throw new IllegalStateException("Agent protocol message evidence is full");
		messages.put(slot, fingerprint);
		return persistAgentPeer(key, new AgentPeerState(current.binding(),
			current.offerFingerprint(), current.statusQueryFingerprint(),
			current.cancelFingerprint(), messages));
	}

	private boolean persistAgentStatus(RemoteRequestKey key, AgentPeerState current,
		AgentProtocolMessage.Status status, String fingerprint, String slot) {
		String previous = current.immutableMessages().get(slot);
		if (previous != null) {
			if (!previous.equals(fingerprint))
				throw new IllegalStateException("conflicting immutable Agent status evidence");
			return true;
		}
		for (Map.Entry<String, String> entry : current.immutableMessages().entrySet()) {
			if (!entry.getKey().startsWith("TERMINAL:")
				&& !entry.getKey().startsWith("ACK:")) continue;
			if (status.record().isPresent() && status.record().orElseThrow().isTerminal()) {
				String terminalFingerprint = new AgentProtocolMessage.Terminal(key,
					status.record().orElseThrow()).fingerprint();
				String durableTerminal = entry.getValue();
				int delimiter = durableTerminal.indexOf('\u0000');
				if (delimiter >= 0) durableTerminal = durableTerminal.substring(0, delimiter);
				if (durableTerminal.equals(terminalFingerprint)) return true;
			}
			throw new IllegalStateException("Agent status conflicts with terminal high-watermark");
		}
		LinkedHashMap<String, String> messages = new LinkedHashMap<>(current.immutableMessages());
		if (status.record().isPresent()) {
			long statusRevision = status.record().orElseThrow().revision();
			messages.remove("STATUS:MISSING");
			for (String knownSlot : List.copyOf(messages.keySet())) {
				if (!knownSlot.startsWith("STATUS:") || knownSlot.equals("STATUS:MISSING"))
					continue;
				long knownRevision = protocolSlotRevision(knownSlot, "STATUS:");
				if (knownRevision > statusRevision) return true;
				messages.remove(knownSlot);
			}
		}
		if (messages.size() >= agentProtocolEvidenceCapacity)
			throw new IllegalStateException("Agent protocol message evidence is full");
		messages.put(slot, fingerprint);
		return persistAgentPeer(key, new AgentPeerState(current.binding(),
			current.offerFingerprint(), current.statusQueryFingerprint(),
			current.cancelFingerprint(), messages));
	}

	private static long protocolSlotRevision(String slot, String prefix) {
		try {
			return Long.parseLong(slot.substring(prefix.length()));
		} catch (RuntimeException invalid) {
			throw new IllegalStateException("Invalid durable Agent protocol slot: " + slot, invalid);
		}
	}

	private boolean persistAgentControl(RemoteRequestKey key, AgentPeerState current,
		String fingerprint, boolean statusQuery) {
		Optional<String> previous = statusQuery ? current.statusQueryFingerprint()
			: current.cancelFingerprint();
		if (previous.isPresent()) {
			if (!previous.orElseThrow().equals(fingerprint))
				throw new IllegalStateException("conflicting immutable Agent control evidence");
			return true;
		}
		AgentPeerState updated = new AgentPeerState(current.binding(), current.offerFingerprint(),
			statusQuery ? Optional.of(fingerprint) : current.statusQueryFingerprint(),
			statusQuery ? current.cancelFingerprint() : Optional.of(fingerprint),
			current.immutableMessages());
		return persistAgentPeer(key, updated);
	}

	private boolean persistAgentPeer(RemoteRequestKey key, AgentPeerState updated) {
		LinkedHashMap<RemoteRequestKey, AgentPeerState> next =
			new LinkedHashMap<>(agentPeers);
		next.put(key, updated);
		AgentProtocolState state = new AgentProtocolState(next);
		agentProtocolStore.persist(state);
		agentPeers.clear();
		agentPeers.putAll(state.peers());
		return false;
	}

	private static String agentMessageSlot(AgentProtocolMessage message) {
		if (message instanceof AgentProtocolMessage.Offer) return "OFFER";
		if (message instanceof AgentProtocolMessage.StatusQuery) return "STATUS_QUERY";
		if (message instanceof AgentProtocolMessage.Cancel) return "CANCEL";
		if (message instanceof AgentProtocolMessage.EvidenceAck ack)
			return "ACK:" + ack.revision();
		if (message instanceof AgentProtocolMessage.Terminal terminal)
			return "TERMINAL:" + terminal.record().revision();
		AgentProtocolMessage.Status status = (AgentProtocolMessage.Status) message;
		return status.record().map(record -> "STATUS:" + record.revision())
			.orElse("STATUS:MISSING");
	}

	public boolean compactAgentProtocolPeer(AgentProtocolCompactionEvidence evidence) {
		evidence = Objects.requireNonNull(evidence, "evidence");
		AgentPeerState peer = agentPeers.get(evidence.key());
		if (peer == null || peer.immutableMessages().keySet().stream()
			.noneMatch(slot -> slot.startsWith("ACK:"))) return false;
		LinkedHashMap<RemoteRequestKey, AgentPeerState> next = new LinkedHashMap<>(agentPeers);
		next.remove(evidence.key());
		AgentProtocolState state = new AgentProtocolState(next);
		agentProtocolStore.persist(state);
		agentPeers.clear();
		agentPeers.putAll(state.peers());
		return true;
	}

	private void validateKnownLeaseEnvelope(RadioEnvelope envelope) {
		if (envelope.payload() instanceof ProcessProtocolMessage.Data
			|| envelope.payload() instanceof ProcessProtocolMessage.LeaseOffer
			|| envelope.payload() instanceof AgentProtocolMessage
			|| envelope.payload() instanceof RecoveryProtocolMessage)
			return;
		UUID leaseId = leaseId(envelope.payload());
		LeaseState state = leases.get(leaseId);
		if (state == null || state.appliedOffers == 0)
			throw new IllegalArgumentException("remote response arrived before its lease offer");
		validateLeaseDirection(envelope, envelope.targetHop().key(), state, envelope.payload());
	}

	private static void validateLeaseDirection(RadioEnvelope envelope,
		RadioEndpoint.Key recipient, LeaseState state, ProcessProtocolMessage payload) {
		if (payload instanceof ProcessProtocolMessage.RevisionAcknowledgement) {
			if (!recipient.equals(state.receiver.key())
				|| !envelope.sourceHop().equals(state.source)
				|| !envelope.targetHop().equals(state.receiver))
				throw new IllegalArgumentException("revision ACK does not match the exact lease fence");
		} else if (!recipient.equals(state.source.key())
			|| !envelope.sourceHop().equals(state.receiver)
			|| !envelope.targetHop().equals(state.source)) {
			throw new IllegalArgumentException("remote response does not match the exact source fence");
		}
	}

	private boolean markLeaseWaiting(PendingRoute route) {
		ProcessProtocolMessage payload = route.current.payload();
		if (payload instanceof AgentProtocolMessage) return true;
		if (payload instanceof ProcessProtocolMessage.LeaseOffer offer) {
			RemoteProcessLease lease = offer.lease();
			LeaseState state = leases.get(lease.leaseId());
			if (state == null) {
				if (leases.size() >= leaseProtocolEvidenceCapacity) return false;
				state = new LeaseState(lease, route.original.sourceHop(),
					route.original.targetHop());
			} else state = state.copy();
			if (state.waitingRouteMessages.contains(route.original.messageId())) return true;
			if (state.waitingRouteMessages.size() >= leaseProtocolEvidenceCapacity) return false;
			state.waitingRouteMessages.add(route.original.messageId());
			persistLease(state);
			return true;
		}
		UUID leaseId = leaseIdOrNull(payload);
		if (leaseId != null) {
			LeaseState state = leases.get(leaseId);
			if (state == null) return false;
			if (state.waitingRouteMessages.contains(route.original.messageId())) return true;
			if (state.waitingRouteMessages.size() >= leaseProtocolEvidenceCapacity) return false;
			LeaseState updated = state.copy();
			updated.waitingRouteMessages.add(route.original.messageId());
			persistLease(updated);
		}
		return true;
	}

	private void clearLeaseWait(PendingRoute route) {
		ProcessProtocolMessage payload = route.current.payload();
		UUID leaseId = leaseIdOrNull(payload);
		if (leaseId != null) {
			LeaseState state = leases.get(leaseId);
			if (state != null && state.waitingRouteMessages.contains(route.original.messageId())) {
				LeaseState updated = state.copy();
				updated.waitingRouteMessages.remove(route.original.messageId());
				persistLease(updated);
			}
		}
	}

	private static UUID leaseId(ProcessProtocolMessage payload) {
		UUID leaseId = leaseIdOrNull(payload);
		if (leaseId == null)
			throw new IllegalArgumentException("payload is not a lease protocol message");
		return leaseId;
	}

	private static UUID leaseIdOrNull(ProcessProtocolMessage payload) {
		if (payload instanceof ProcessProtocolMessage.LeaseOffer offer)
			return offer.lease().leaseId();
		if (payload instanceof ProcessProtocolMessage.LeaseRevision revision)
			return revision.leaseId();
		if (payload instanceof ProcessProtocolMessage.RevisionAcknowledgement acknowledgement)
			return acknowledgement.leaseId();
		if (payload instanceof ProcessProtocolMessage.LeaseResult result)
			return result.leaseId();
		return null;
	}

	private LeaseState requireLease(UUID leaseId) {
		LeaseState state = leases.get(leaseId);
		if (state == null)
			throw new IllegalStateException("remote response arrived before its lease offer");
		return state;
	}

	private static void validateSameLease(LeaseState state, RemoteProcessLease lease,
		RadioEndpoint.Hop source, RadioEndpoint.Hop receiver) {
		if (!state.lease.equals(lease) || !state.source.equals(source)
			|| !state.receiver.equals(receiver))
			throw new IllegalStateException("conflicting duplicate lease offer");
	}

	private void persistLease(LeaseState updated) {
		LinkedHashMap<UUID, DurableLeaseState> next = durableLeases();
		DurableLeaseState snapshot = updated.snapshot();
		next.put(updated.lease.leaseId(), snapshot);
		validateDurableLease(updated.lease.leaseId(), snapshot,
			leaseProtocolEvidenceCapacity);
		LinkedHashMap<RemoteLeaseLineage, RetiredLeaseState> retired =
			new LinkedHashMap<>(retiredLeaseLineages);
		retired.remove(RemoteLeaseLineage.from(updated.lease));
		persistLeaseProtocol(next, retired);
	}

	private LinkedHashMap<UUID, DurableLeaseState> durableLeases() {
		LinkedHashMap<UUID, DurableLeaseState> durable = new LinkedHashMap<>();
		leases.forEach((leaseId, state) -> durable.put(leaseId, state.snapshot()));
		return durable;
	}

	private void persistLeaseProtocol(Map<UUID, DurableLeaseState> nextLeases,
		Map<RemoteLeaseLineage, RetiredLeaseState> nextRetiredLineages) {
		LeaseProtocolState durable = checkedLeaseProtocolState(nextLeases,
			nextRetiredLineages);
		leaseProtocolStore.persist(durable);
		installPersistedLeaseProtocolState(durable);
	}

	private LeaseProtocolState checkedLeaseProtocolState(
		Map<UUID, DurableLeaseState> nextLeases,
		Map<RemoteLeaseLineage, RetiredLeaseState> nextRetiredLineages) {
		if ((long) nextLeases.size() + nextRetiredLineages.size()
			> leaseProtocolEvidenceCapacity)
			throw new IllegalStateException("lease protocol correctness evidence is full");
		LeaseProtocolState durable = new LeaseProtocolState(nextLeases,
			nextRetiredLineages);
		durable.leases().forEach((leaseId, state) ->
			validateDurableLease(leaseId, state, leaseProtocolEvidenceCapacity));
		durable.retiredLineages().forEach((lineage, retired) -> {
			if (!lineage.equals(retired.lineage()))
				throw new IllegalArgumentException(
					"Durable retired lease lineage key mismatch");
		});
		return durable;
	}

	private static void validateDurableLease(UUID leaseId, DurableLeaseState state,
		int capacity) {
		if (!leaseId.equals(state.lease().leaseId()) || state.appliedOffers() < 0
			|| state.appliedOffers() > 1 || state.revisions().size() > capacity
			|| state.acknowledgedRevisions().size() > capacity
			|| state.results().size() > capacity
			|| state.consumedMaterialRevisions().size() > capacity
			|| state.waitingRouteMessages().size() > capacity)
			throw new IllegalArgumentException("invalid durable lease protocol evidence");
		if (!state.source().key().equals(new RadioEndpoint.Key(state.lease().sourceClusterId(),
			state.source().key().memberId()))
			|| !state.receiver().key().equals(new RadioEndpoint.Key(state.lease().receiverClusterId(),
				state.receiver().key().memberId()))
			|| state.source().generation() != state.lease().sourceGeneration()
			|| state.receiver().generation() != state.lease().receiverGeneration()
			|| !state.revisions().keySet().containsAll(state.acknowledgedRevisions())
			|| !state.acknowledgedRevisions().containsAll(state.consumedMaterialRevisions())
			|| !state.acknowledgedRevisions().containsAll(state.results().keySet())
			|| state.revisions().keySet().stream().anyMatch(revision ->
				revision != state.lease().revisionFence())
			|| state.results().keySet().stream().anyMatch(revision ->
				revision != state.lease().revisionFence())
			|| state.revisions().entrySet().stream().anyMatch(entry ->
				!entry.getValue().matches(state.lease().expansionRequest()))
			|| state.results().entrySet().stream().anyMatch(entry -> {
				RemoteExpansionRevision revision = state.revisions().get(entry.getKey());
				return revision == null || !entry.getValue().matches(
					state.lease().expansionRequest(), revision);
			}))
			throw new IllegalArgumentException("durable lease protocol fence mismatch");
	}

	public interface AgentProtocolStateStore {
		default Optional<AgentProtocolState> load() { return Optional.empty(); }
		void persist(AgentProtocolState state);
	}

	public interface LeaseProtocolStateStore {
		default Optional<LeaseProtocolState> load() { return Optional.empty(); }
		void persist(LeaseProtocolState state);
	}

	/** Exact bounded CAS store for endpoint generations and listener-boundary tombstones. */
	public interface AuthorityStateStore {
		default Optional<AuthorityState> load() { return Optional.empty(); }
		default boolean compareAndSet(AuthorityState expected, AuthorityState replacement) {
			return true;
		}
	}

	public record AuthorityState(long revision,
		Map<RadioEndpoint.Key, EndpointAuthority> endpoints,
		Map<UUID, BoundaryAuthority> boundaries) {
		public AuthorityState {
			if (revision < 0) throw new IllegalArgumentException("negative authority revision");
			if (revision == Long.MAX_VALUE)
				throw new IllegalArgumentException("authority revision exhausted");
			endpoints = Map.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
			boundaries = Map.copyOf(Objects.requireNonNull(boundaries, "boundaries"));
			if (endpoints.size() > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
				throw new IllegalArgumentException("endpoint authority exceeds capacity");
			if (boundaries.size() > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
				throw new IllegalArgumentException("boundary authority exceeds capacity");
			Map<RadioEndpoint.Key, EndpointAuthority> exactEndpoints = endpoints;
			endpoints.forEach((key, authority) -> {
				if (authority.highestGeneration() == Long.MAX_VALUE)
					throw new IllegalArgumentException("endpoint generation exhausted");
				if (!key.equals(authority.current().map(RadioEndpoint::key).orElse(key)))
					throw new IllegalArgumentException("endpoint authority key mismatch");
			});
			boundaries.forEach((clusterId, authority) -> {
				if (!clusterId.equals(authority.binding().sourceClusterId()))
					throw new IllegalArgumentException("boundary authority key mismatch");
				if (authority.revision() > revision || authority.revision() == Long.MAX_VALUE)
					throw new IllegalArgumentException("boundary revision exceeds global authority");
				if (authority.active()) requireExactCurrentBoundaryHops(exactEndpoints, authority.binding());
			});
		}

		private static void requireExactCurrentBoundaryHops(
			Map<RadioEndpoint.Key, EndpointAuthority> endpoints, SubnetBoundaryBinding binding) {
			EndpointAuthority sourceAuthority = endpoints.get(binding.sourceHop().key());
			EndpointAuthority targetAuthority = endpoints.get(binding.targetHop().key());
			RadioEndpoint source = sourceAuthority == null ? null : sourceAuthority.current().orElse(null);
			RadioEndpoint target = targetAuthority == null ? null : targetAuthority.current().orElse(null);
			if (source == null) throw new IllegalArgumentException("active boundary source is missing");
			if (target == null) throw new IllegalArgumentException("active boundary relay is missing");
			if (!source.hop().equals(binding.sourceHop()) || !target.hop().equals(binding.targetHop())
				|| !source.frequency().equals(binding.listenerFrequency())
				|| !target.frequency().equals(binding.listenerFrequency()))
				throw new IllegalArgumentException("active boundary does not match current endpoint hops");
		}
		static AuthorityState empty() { return new AuthorityState(0, Map.of(), Map.of()); }
	}

	public record EndpointAuthority(long highestGeneration, Optional<RadioEndpoint> current) {
		public EndpointAuthority {
			if (highestGeneration < 0)
				throw new IllegalArgumentException("negative endpoint generation fence");
			current = Objects.requireNonNull(current, "current endpoint");
			if (current.isPresent() && current.orElseThrow().generation() != highestGeneration)
				throw new IllegalArgumentException("current endpoint differs from generation fence");
		}
	}

	/** One listener's complete source/relay/boundary authority. */
	public record ListenerAuthority(RadioEndpoint sourceEndpoint, RadioEndpoint relayEndpoint,
		SubnetBoundaryBinding boundaryBinding) {
		public ListenerAuthority {
			sourceEndpoint = Objects.requireNonNull(sourceEndpoint, "source endpoint");
			relayEndpoint = Objects.requireNonNull(relayEndpoint, "relay endpoint");
			boundaryBinding = Objects.requireNonNull(boundaryBinding, "boundary binding");
			if (!sourceEndpoint.hop().equals(boundaryBinding.sourceHop())
				|| !relayEndpoint.hop().equals(boundaryBinding.targetHop())
				|| !sourceEndpoint.frequency().equals(boundaryBinding.listenerFrequency())
				|| !relayEndpoint.frequency().equals(boundaryBinding.listenerFrequency()))
				throw new IllegalArgumentException("listener authority does not match its boundary");
		}
	}

	/** Immutable persist-before-live listener authority transition. */
	public record ListenerAuthorityTransaction(AuthorityState expected, AuthorityState replacement,
		RadioEndpoint.Key sourceKey, RadioEndpoint.Key relayKey, UUID boundarySourceClusterId,
		Optional<ListenerAuthority> liveReplacement) {
		public ListenerAuthorityTransaction {
			expected = Objects.requireNonNull(expected, "expected authority");
			replacement = Objects.requireNonNull(replacement, "replacement authority");
			sourceKey = Objects.requireNonNull(sourceKey, "source key");
			relayKey = Objects.requireNonNull(relayKey, "relay key");
			boundarySourceClusterId = Objects.requireNonNull(boundarySourceClusterId,
				"boundary source cluster id");
			liveReplacement = Objects.requireNonNull(liveReplacement, "live replacement");
		}
	}

	/** Retains the last exact binding even when inactive, so retired authority cannot reappear. */
	public record BoundaryAuthority(long revision, SubnetBoundaryBinding binding, boolean active) {
		public BoundaryAuthority {
			if (revision < 0) throw new IllegalArgumentException("negative boundary revision");
			binding = Objects.requireNonNull(binding, "binding");
		}
	}

	public record LeaseProtocolState(Map<UUID, DurableLeaseState> leases,
		Map<RemoteLeaseLineage, RetiredLeaseState> retiredLineages) {
		public LeaseProtocolState {
			leases = Map.copyOf(Objects.requireNonNull(leases, "leases"));
			retiredLineages = Map.copyOf(Objects.requireNonNull(retiredLineages,
				"retiredLineages"));
		}
	}

	public record CompletedLeaseRetirement(UUID leaseId, LeaseProtocolState state) {
		public CompletedLeaseRetirement {
			leaseId = Objects.requireNonNull(leaseId, "leaseId");
			state = Objects.requireNonNull(state, "state");
			if (state.leases().containsKey(leaseId))
				throw new IllegalArgumentException("retired lease remains active");
		}
	}

	public enum LeaseTerminalOutcome { COMPLETED, CANCELLED, MIGRATED }

	public record TerminalLeaseRetirement(UUID leaseId, LeaseTerminalOutcome outcome,
		LeaseProtocolState state) {
		public TerminalLeaseRetirement {
			leaseId = Objects.requireNonNull(leaseId, "leaseId");
			outcome = Objects.requireNonNull(outcome, "outcome");
			state = Objects.requireNonNull(state, "state");
			if (outcome == LeaseTerminalOutcome.COMPLETED || state.leases().containsKey(leaseId))
				throw new IllegalArgumentException("Invalid terminal lease retirement");
		}
	}

	public record RemoteLeaseLineage(UUID sourceClusterId, UUID sourceGoalId,
		UUID sourceProcessId, UUID childProcessId) {
		public RemoteLeaseLineage {
			sourceClusterId = Objects.requireNonNull(sourceClusterId, "sourceClusterId");
			sourceGoalId = Objects.requireNonNull(sourceGoalId, "sourceGoalId");
			sourceProcessId = Objects.requireNonNull(sourceProcessId, "sourceProcessId");
			childProcessId = Objects.requireNonNull(childProcessId, "childProcessId");
		}

		private static RemoteLeaseLineage from(RemoteProcessLease lease) {
			return new RemoteLeaseLineage(lease.sourceClusterId(), lease.sourceGoalId(),
				lease.sourceProcessId(), RemoteExpansionRevision.logicalChildId(
					lease.expansionRequest()));
		}
	}

	public record RetiredLeaseState(RemoteLeaseLineage lineage, UUID leaseId,
		long revisionFence, LeaseTerminalOutcome outcome, String requestFingerprint,
		Optional<String> resultFingerprint) {
		public RetiredLeaseState {
			lineage = Objects.requireNonNull(lineage, "lineage");
			leaseId = Objects.requireNonNull(leaseId, "leaseId");
			outcome = Objects.requireNonNull(outcome, "outcome");
			if (revisionFence < 0) throw new IllegalArgumentException("negative retired lease fence");
			requestFingerprint = requireFingerprint(requestFingerprint, "requestFingerprint");
			resultFingerprint = Objects.requireNonNull(resultFingerprint, "resultFingerprint")
				.map(value -> requireFingerprint(value, "resultFingerprint"));
			if ((outcome == LeaseTerminalOutcome.COMPLETED) != resultFingerprint.isPresent())
				throw new IllegalArgumentException("Terminal lease result does not match outcome");
		}

		private static RetiredLeaseState completed(RemoteLeaseLineage lineage,
			RemoteProcessLease lease, RemoteExpansionResult result) {
			return new RetiredLeaseState(lineage, lease.leaseId(), lease.revisionFence(),
				LeaseTerminalOutcome.COMPLETED, lease.expansionRequest().fingerprint(),
				Optional.of(result.fingerprint()));
		}

		private static RetiredLeaseState terminal(RemoteLeaseLineage lineage,
			RemoteProcessLease lease, LeaseTerminalOutcome outcome) {
			return new RetiredLeaseState(lineage, lease.leaseId(), lease.revisionFence(), outcome,
				lease.expansionRequest().fingerprint(), Optional.empty());
		}

		private boolean matches(RemoteProcessLease lease, RemoteExpansionResult result) {
			return lineage.equals(RemoteLeaseLineage.from(lease))
				&& leaseId.equals(lease.leaseId()) && revisionFence == lease.revisionFence()
				&& requestFingerprint.equals(lease.expansionRequest().fingerprint())
				&& outcome == LeaseTerminalOutcome.COMPLETED
				&& resultFingerprint.equals(Optional.of(result.fingerprint()));
		}

		private boolean matchesTerminal(RemoteProcessLease lease,
			LeaseTerminalOutcome expectedOutcome) {
			return lineage.equals(RemoteLeaseLineage.from(lease))
				&& leaseId.equals(lease.leaseId()) && revisionFence == lease.revisionFence()
				&& outcome == expectedOutcome
				&& requestFingerprint.equals(lease.expansionRequest().fingerprint())
				&& resultFingerprint.isEmpty();
		}

		private static String requireFingerprint(String value, String field) {
			value = Objects.requireNonNull(value, field);
			if (value.isBlank() || value.length() > RemoteExpansionRequest.MAX_SERIALIZED_BYTES)
				throw new IllegalArgumentException("invalid retired lease " + field);
			return value;
		}
	}

	public record DurableLeaseState(RemoteProcessLease lease, RadioEndpoint.Hop source,
		RadioEndpoint.Hop receiver, Map<Long, RemoteExpansionRevision> revisions,
		Set<Long> acknowledgedRevisions, Map<Long, RemoteExpansionResult> results,
		Set<Long> consumedMaterialRevisions, Set<UUID> waitingRouteMessages,
		int appliedOffers) {
		public DurableLeaseState {
			lease = Objects.requireNonNull(lease, "lease");
			source = Objects.requireNonNull(source, "source");
			receiver = Objects.requireNonNull(receiver, "receiver");
			revisions = Map.copyOf(Objects.requireNonNull(revisions, "revisions"));
			acknowledgedRevisions = Set.copyOf(Objects.requireNonNull(acknowledgedRevisions,
				"acknowledgedRevisions"));
			results = Map.copyOf(Objects.requireNonNull(results, "results"));
			consumedMaterialRevisions = Set.copyOf(Objects.requireNonNull(
				consumedMaterialRevisions, "consumedMaterialRevisions"));
			waitingRouteMessages = Set.copyOf(Objects.requireNonNull(waitingRouteMessages,
				"waitingRouteMessages"));
		}
	}

	public static final class AgentProtocolCompactionEvidence {
		private final RemoteRequestKey key;
		private final UUID coherenceEpoch;

		private AgentProtocolCompactionEvidence(ResolvedAgentProtocolCompactionFacts facts) {
			this.key = facts.key();
			this.coherenceEpoch = facts.coherenceEpoch();
		}

		public RemoteRequestKey key() { return key; }
		public UUID coherenceEpoch() { return coherenceEpoch; }
	}

	public static final class AgentProtocolCompactionEvidenceIssuer {
		private final ServerThreadGuard guard;
		private final AgentProtocolCompactionResolver resolver;

		public AgentProtocolCompactionEvidenceIssuer(ServerThreadGuard guard,
			AgentProtocolCompactionResolver resolver) {
			this.guard = Objects.requireNonNull(guard, "guard");
			this.resolver = Objects.requireNonNull(resolver, "resolver");
		}

		public Optional<AgentProtocolCompactionEvidence> capture(RemoteRequestKey key) {
			key = Objects.requireNonNull(key, "key");
			if (!guard.onServerThread()) return Optional.empty();
			ResolvedAgentProtocolCompactionFacts facts = resolver.resolve(
				new AgentProtocolCompactionRequest(key)).orElse(null);
			return facts != null && facts.key().equals(key) && facts.bothPeersRetired()
				&& facts.sourceGenerationFloor() > key.sourceGeneration()
				&& facts.agentGenerationFloor() > key.agentGeneration()
				? Optional.of(new AgentProtocolCompactionEvidence(facts)) : Optional.empty();
		}
	}

	@FunctionalInterface
	public interface ServerThreadGuard { boolean onServerThread(); }

	@FunctionalInterface
	public interface AgentProtocolCompactionResolver {
		Optional<ResolvedAgentProtocolCompactionFacts> resolve(
			AgentProtocolCompactionRequest request);
	}

	public record AgentProtocolCompactionRequest(RemoteRequestKey key) {
		public AgentProtocolCompactionRequest {
			key = Objects.requireNonNull(key, "key");
		}
	}

	public record ResolvedAgentProtocolCompactionFacts(RemoteRequestKey key, UUID coherenceEpoch,
		boolean bothPeersRetired, long sourceGenerationFloor, long agentGenerationFloor) {
		public ResolvedAgentProtocolCompactionFacts {
			key = Objects.requireNonNull(key, "key");
			coherenceEpoch = Objects.requireNonNull(coherenceEpoch, "coherenceEpoch");
			if (sourceGenerationFloor < 0 || agentGenerationFloor < 0)
				throw new IllegalArgumentException("Protocol compaction floor is negative");
		}
	}

	public record AgentProtocolState(Map<RemoteRequestKey, AgentPeerState> peers) {
		public AgentProtocolState {
			peers = Map.copyOf(Objects.requireNonNull(peers, "peers"));
			for (Map.Entry<RemoteRequestKey, AgentPeerState> entry : peers.entrySet())
				if (!entry.getKey().equals(entry.getValue().binding().key()))
					throw new IllegalArgumentException("Agent peer state key mismatch");
		}
	}

	public record AgentPeerState(AgentPeerBinding binding, String offerFingerprint,
		Optional<String> statusQueryFingerprint, Optional<String> cancelFingerprint,
		Map<String, String> immutableMessages) {
		public AgentPeerState {
			binding = Objects.requireNonNull(binding, "binding");
			offerFingerprint = Objects.requireNonNull(offerFingerprint, "offerFingerprint");
			if (offerFingerprint.isBlank())
				throw new IllegalArgumentException("Agent OFFER fingerprint is blank");
			statusQueryFingerprint = Objects.requireNonNull(statusQueryFingerprint,
				"statusQueryFingerprint");
			cancelFingerprint = Objects.requireNonNull(cancelFingerprint, "cancelFingerprint");
			if (statusQueryFingerprint.filter(String::isBlank).isPresent()
				|| cancelFingerprint.filter(String::isBlank).isPresent())
				throw new IllegalArgumentException("Agent control fingerprint is blank");
			immutableMessages = Map.copyOf(Objects.requireNonNull(immutableMessages,
				"immutableMessages"));
		}
	}

	public record AgentPeerBinding(RemoteRequestKey key, RadioEndpoint.Hop source,
		RadioEndpoint.Hop target, UUID sourceClusterId, UUID targetClusterId,
		UUID sourceMemberId, UUID targetMemberId, UUID agentId, CreateRouteKey targetRoute) {
		public AgentPeerBinding {
			key = Objects.requireNonNull(key, "key");
			source = Objects.requireNonNull(source, "source");
			target = Objects.requireNonNull(target, "target");
			sourceClusterId = Objects.requireNonNull(sourceClusterId, "sourceClusterId");
			targetClusterId = Objects.requireNonNull(targetClusterId, "targetClusterId");
			sourceMemberId = Objects.requireNonNull(sourceMemberId, "sourceMemberId");
			targetMemberId = Objects.requireNonNull(targetMemberId, "targetMemberId");
			agentId = Objects.requireNonNull(agentId, "agentId");
			targetRoute = Objects.requireNonNull(targetRoute, "targetRoute");
			if (!source.key().equals(new RadioEndpoint.Key(sourceClusterId, sourceMemberId))
				|| !target.key().equals(new RadioEndpoint.Key(targetClusterId, targetMemberId))
				|| key.sourceGeneration() != source.generation()
				|| key.agentGeneration() != target.generation())
				throw new IllegalArgumentException("Agent peer binding fence mismatch");
		}

		static AgentPeerBinding from(RadioEnvelope envelope,
			AgentProtocolMessage.Offer offer) {
			ProductionRequestTicket ticket = offer.ticket();
			return new AgentPeerBinding(offer.key(), envelope.sourceHop(), envelope.targetHop(),
				envelope.sourceClusterId(), envelope.targetClusterId(), envelope.sourceMemberId(),
				envelope.targetMemberId(), ticket.agentId(), ticket.targetRoute());
		}

		void validate(RadioEnvelope envelope, AgentProtocolMessage message) {
			boolean reverse = message instanceof AgentProtocolMessage.Status
				|| message instanceof AgentProtocolMessage.Terminal;
			RadioEndpoint.Hop expectedSource = reverse ? target : source;
			RadioEndpoint.Hop expectedTarget = reverse ? source : target;
			if (!key.equals(message.key()) || !expectedSource.equals(envelope.sourceHop())
				|| !expectedTarget.equals(envelope.targetHop()))
				throw new IllegalArgumentException("Agent protocol peer binding mismatch");
			if (message instanceof AgentProtocolMessage.Offer offer
				&& (!offer.ticket().agentId().equals(agentId)
					|| !offer.ticket().targetRoute().equals(targetRoute)
					|| !offer.ticket().sourceClusterId().equals(sourceClusterId)))
				throw new IllegalArgumentException("Agent OFFER identity changed");
		}
	}

	public enum RouteStatus {
		QUEUED,
		IN_TRANSIT,
		WAITING_ROUTE,
		WAITING_PROTOCOL_CAPACITY,
		DELIVERED
	}

	public record RouteProgress(RadioEnvelope current, int nextHopIndex,
		Optional<RadioEndpoint.Key> heldRelayListener) {
		public RouteProgress {
			current = Objects.requireNonNull(current, "current");
			heldRelayListener = Objects.requireNonNull(heldRelayListener,
				"heldRelayListener");
			if (nextHopIndex <= 0)
				throw new IllegalArgumentException("nextHopIndex must be positive");
		}
	}

	public record TickResult(List<Delivery> deliveries) {
		public TickResult {
			deliveries = List.copyOf(Objects.requireNonNull(deliveries, "deliveries"));
		}
	}

	public record Delivery(RadioEnvelope envelope, RadioEndpoint.Key recipient,
		boolean finalRecipient, boolean virtualDelivery,
		Optional<ProcessKind> listenerProcess, boolean duplicateProtocolMessage) {
		public Delivery {
			envelope = Objects.requireNonNull(envelope, "envelope");
			recipient = Objects.requireNonNull(recipient, "recipient");
			listenerProcess = Objects.requireNonNull(listenerProcess, "listenerProcess");
		}
	}

	public record PreparedMaterialAuthorization(UUID leaseId, RadioEndpoint.Key receiver,
		long revision, LeaseProtocolState state) {
		public PreparedMaterialAuthorization {
			leaseId = Objects.requireNonNull(leaseId, "leaseId");
			receiver = Objects.requireNonNull(receiver, "receiver");
			state = Objects.requireNonNull(state, "state");
			DurableLeaseState lease = state.leases().get(leaseId);
			if (revision < 0 || lease == null || !lease.receiver().key().equals(receiver)
				|| !lease.consumedMaterialRevisions().contains(revision))
				throw new IllegalArgumentException("invalid prepared material authorization");
		}
	}

	public static final class MaterialIntentPermit {
		private final UUID nonce;
		private final UUID leaseId;
		private final UUID sourceGoalId;
		private final UUID sourceProcessId;
		private final UUID sourceClusterId;
		private final RadioEndpoint.Key receiver;
		private final long receiverGeneration;
		private final long acknowledgedRevision;

		private MaterialIntentPermit(UUID nonce, UUID leaseId, UUID sourceGoalId, UUID sourceProcessId,
			UUID sourceClusterId, RadioEndpoint.Key receiver, long receiverGeneration,
			long acknowledgedRevision) {
			this.nonce = Objects.requireNonNull(nonce, "nonce");
			this.leaseId = Objects.requireNonNull(leaseId, "leaseId");
			this.sourceGoalId = Objects.requireNonNull(sourceGoalId, "sourceGoalId");
			this.sourceProcessId = Objects.requireNonNull(sourceProcessId, "sourceProcessId");
			this.sourceClusterId = Objects.requireNonNull(sourceClusterId, "sourceClusterId");
			this.receiver = Objects.requireNonNull(receiver, "receiver");
			this.receiverGeneration = receiverGeneration;
			this.acknowledgedRevision = acknowledgedRevision;
		}

		public UUID leaseId() { return leaseId; }
		public UUID sourceGoalId() { return sourceGoalId; }
		public UUID sourceProcessId() { return sourceProcessId; }
		public UUID sourceClusterId() { return sourceClusterId; }
		public RadioEndpoint.Key receiver() { return receiver; }
		public long receiverGeneration() { return receiverGeneration; }
		public long acknowledgedRevision() { return acknowledgedRevision; }
	}


	private static final class PendingRoute {
		final RadioEnvelope original;
		final List<RadioEndpoint.Hop> path;
		RadioEnvelope current;
		int nextHopIndex = 1;
		RouteStatus status = RouteStatus.QUEUED;
		RadioEndpoint.Key heldRelayListener;

		PendingRoute(RadioEnvelope original, List<RadioEndpoint.Hop> path) {
			this.original = original;
			this.current = original;
			this.path = path;
		}
	}

	private static final class LeaseState {
		final RemoteProcessLease lease;
		final RadioEndpoint.Hop source;
		final RadioEndpoint.Hop receiver;
		final Map<Long, RemoteExpansionRevision> revisions = new HashMap<>();
		final Set<Long> acknowledgedRevisions = new HashSet<>();
		final Map<Long, RemoteExpansionResult> results = new HashMap<>();
		final Set<Long> consumedMaterialRevisions = new HashSet<>();
		final Set<UUID> waitingRouteMessages = new HashSet<>();
		int appliedOffers;

		LeaseState(RemoteProcessLease lease, RadioEndpoint.Hop source,
			RadioEndpoint.Hop receiver) {
			this.lease = lease;
			this.source = source;
			this.receiver = receiver;
		}

		private LeaseState copy() {
			LeaseState copied = new LeaseState(lease, source, receiver);
			copied.revisions.putAll(revisions);
			copied.acknowledgedRevisions.addAll(acknowledgedRevisions);
			copied.results.putAll(results);
			copied.consumedMaterialRevisions.addAll(consumedMaterialRevisions);
			copied.waitingRouteMessages.addAll(waitingRouteMessages);
			copied.appliedOffers = appliedOffers;
			return copied;
		}

		private DurableLeaseState snapshot() {
			return new DurableLeaseState(lease, source, receiver, revisions,
				acknowledgedRevisions, results, consumedMaterialRevisions,
				waitingRouteMessages, appliedOffers);
		}

		private static LeaseState restore(DurableLeaseState durable) {
			LeaseState restored = new LeaseState(durable.lease(), durable.source(),
				durable.receiver());
			restored.revisions.putAll(durable.revisions());
			restored.acknowledgedRevisions.addAll(durable.acknowledgedRevisions());
			restored.results.putAll(durable.results());
			restored.consumedMaterialRevisions.addAll(durable.consumedMaterialRevisions());
			restored.waitingRouteMessages.addAll(durable.waitingRouteMessages());
			restored.appliedOffers = durable.appliedOffers();
			return restored;
		}
	}
}
