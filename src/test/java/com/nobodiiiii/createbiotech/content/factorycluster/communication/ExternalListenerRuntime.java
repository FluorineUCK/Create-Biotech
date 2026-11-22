package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryBinding;

/** Server-owned composition root for durable Trader listener configuration. */
public final class ExternalListenerRuntime {
	private final FactoryRuntimeService runtime;
	private final RadioRouter router;
	private final TopologySink topology;
	private final ExternalListenerService listeners;
	private final FactoryRuntimeService.PanelListenerBinding panelListenerBinding;
	private final Map<UUID, Host> hosts = new LinkedHashMap<>();
	private final Map<ExternalListenerService.ListenerOwner, Host> retirementHosts =
		new LinkedHashMap<>();
	private final Map<RadioEndpoint.Key, TopologyRegistration> topologyEndpoints =
		new LinkedHashMap<>();
	/** Active relay-only candidates, fenced by the loaded listener owner and exact endpoint. */
	private final Map<RadioEndpoint.Key, RelayCandidate> relayCandidates = new LinkedHashMap<>();
	private final Map<UUID, FactoryRuntimeService.ExternalListenerProcessReservation> reservations =
		new LinkedHashMap<>();

	public ExternalListenerRuntime(FactoryRuntimeService runtime, RadioRouter router) {
		this(runtime, router, TopologySink.NONE);
	}

	/** A topology sink couples activated source/relay endpoints to the shared frozen frame. */
	public ExternalListenerRuntime(FactoryRuntimeService runtime, RadioRouter router,
		TopologySink topology) {
		this(runtime, router, topology, ExternalListenerService.RetirementStore.NONE);
	}

	public ExternalListenerRuntime(FactoryRuntimeService runtime, RadioRouter router,
		TopologySink topology, ExternalListenerService.RetirementStore retirementStore) {
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		this.router = Objects.requireNonNull(router, "router");
		this.topology = Objects.requireNonNull(topology, "topology");
		listeners = new ExternalListenerService(new RuntimeScheduler(), router, this::publish,
			this::authorizedIngress, Objects.requireNonNull(retirementStore, "retirement store"));
		panelListenerBinding = runtime.installPanelListenerRuntime(
			new InstallationCapability(this, listeners));
	}

	/** Called whenever a formed Computer is observed or its topology/primary frequency refreshes. */
	public void observe(ProductionComputer computer, CommunicationFrequency primaryFrequency,
		SpaceAddress address) {
		computer = Objects.requireNonNull(computer, "computer");
		if (computer.owner().isRemoved()) { unload(computer); return; }
		if (!computer.canConfigureExternalListeners()) { unload(computer); return; }
		// A block entity can still receive one queued observation after its formed epoch was
		// retired.  Treat that stale callback like unload: it must never recreate a listener
		// host or make a normal topology transition throw.
		var epoch = computer.epoch().orElse(null);
		if (epoch == null) { unload(computer); return; }
		LoadedComputerCapability capability = new LoadedComputerCapability(this, computer,
			epoch.groupId(), epoch.epochId(), computer.listenerOwner());
		if (!panelListenerBinding.accepts(capability)) { unload(computer); return; }
		Host host = new Host(computer.listenerOwner(), computer.id(), epoch.groupId(), epoch.epochId(),
			Objects.requireNonNull(primaryFrequency, "primary frequency"),
			Objects.requireNonNull(address, "address"), capability);
		Host previous = hosts.put(host.computerId(), host);
		if (previous != null && !previous.samePublicationInputs(host)) listeners.refresh(computer);
		else listeners.reconcile(computer);
		panelListenerBinding.observe(capability);
		applyCommittedTopologyTransitions();
		synchronizeRelayCandidates(host);
		project(computer, host);
	}

	/** Durable edit plus immediate authoritative slot/publication reconciliation. */
	public ExternalListenerService.EditResult replace(ProductionComputer computer,
		long expectedRevision, List<CommunicationFrequency> frequencies) {
		Host host = hosts.get(Objects.requireNonNull(computer, "computer").id());
		if (host == null || !host.owner().ownerToken().equals(computer.listenerOwnerToken()))
			return ExternalListenerService.EditResult.NOT_FORMED;
		ExternalListenerService.EditResult result = listeners.replace(Objects.requireNonNull(computer,
			"computer"), expectedRevision, frequencies);
		if (result.applied()) panelListenerBinding.configurationChanged(host.capability());
		applyCommittedTopologyTransitions();
		synchronizeRelayCandidates(host);
		project(computer, host);
		return result;
	}

	/** Removes only this loaded owner token's endpoints/binding/process records. */
	public void unload(ProductionComputer computer) {
		computer = Objects.requireNonNull(computer, "computer");
		UUID computerId = computer.computerId().orElse(null);
		if (computerId == null) return;
		Host host = hosts.get(computerId);
		if (host == null) return;
		if (!host.owner().ownerToken().equals(computer.listenerOwnerToken())) return;
		unload(host.owner());
	}

	/** Exact captured owner for loaded-availability reconciliation before a deferred BE callback. */
	public void unload(ExternalListenerService.ListenerOwner owner) {
		owner = Objects.requireNonNull(owner, "owner");
		UUID computerId = owner.computerId();
		Host host = hosts.get(computerId);
		if (host == null || !host.owner().equals(owner)) return;
		panelListenerBinding.retire(host.capability());
		listeners.unload(host.owner());
		applyCommittedTopologyTransitions();
		synchronizeRelayCandidates(host);
		hosts.remove(computerId);
		List<ExternalListenerService.ListenerProjection> projection = listeners.projection(host.owner());
		if (projection.isEmpty()) {
			retirementHosts.remove(host.owner());
			runtime.projectExternalListeners(host.clusterId(), host.epochId(), computerId, List.of());
		} else {
			retirementHosts.put(host.owner(), host);
			runtime.projectExternalListeners(host.clusterId(), host.epochId(), computerId, projection);
		}
	}

	/** Bounded server-tick retry for exact retirements whose aggregate authority CAS failed. */
	public void tick() {
		listeners.retryPendingRetirements();
		applyCommittedTopologyTransitions();
		for (Map.Entry<ExternalListenerService.ListenerOwner, Host> entry :
			List.copyOf(retirementHosts.entrySet())) {
			Host host = entry.getValue();
			List<ExternalListenerService.ListenerProjection> projection = listeners.projection(entry.getKey());
			Host current = hosts.get(host.computerId());
			if (current == null) runtime.projectExternalListeners(host.clusterId(), host.epochId(),
				host.computerId(), projection);
			if (projection.isEmpty()) retirementHosts.remove(entry.getKey(), host);
		}
	}

	/** Production ingress path: Goal/lease authority is validated before forwardBoundary can run. */
	public Optional<RadioEnvelope> forwardIngress(ProductionComputer computer,
		CommunicationFrequency frequency, RadioEnvelope envelope,
		ExternalListenerService.IngressAuthority authority) {
		return listeners.forwardIngress(Objects.requireNonNull(computer, "computer"), frequency,
			envelope, authority);
	}

	public List<ExternalListenerService.ListenerProjection> projection(UUID computerId) {
		return listeners.projection(computerId);
	}

	/** Exact active router publications, exposed only through the server's test read seam. */
	public List<ExternalListenerService.Publication> publications(UUID computerId) {
		return listeners.publications(Objects.requireNonNull(computerId, "computer id"));
	}

	/** Resolves only an exact active relay publication; callers cannot synthesize a boundary identity. */
	public Optional<ExternalListenerService.Publication> activePublication(
		RadioEndpoint.Key activeListenerRelayKey) {
		RadioEndpoint.Key relayKey = Objects.requireNonNull(activeListenerRelayKey,
			"active listener relay key");
		TopologyRegistration registration = topologyEndpoints.get(relayKey);
		if (registration == null) return Optional.empty();
		Host host = hosts.get(registration.owner().computerId());
		if (host == null || !host.owner().equals(registration.owner())) return Optional.empty();
		return listeners.publications(host.computerId()).stream().filter(publication ->
			publication.relayEndpoint().key().equals(relayKey)
				&& publication.relayEndpoint().equals(registration.endpoint())
				&& router.registeredEndpoint(publication.endpoint().key())
					.filter(publication.endpoint()::equals).isPresent()
				&& router.registeredEndpoint(publication.relayEndpoint().key())
					.filter(publication.relayEndpoint()::equals).isPresent()
				&& router.boundaryBinding(publication.boundaryBinding().sourceClusterId())
					.filter(publication.boundaryBinding()::equals).isPresent())
			.findFirst();
	}

	/**
	 * Exact active listener relays eligible for automatic production route discovery.  This is
	 * deliberately separate from source gateways: only the internal relay side can bridge a
	 * normal Computer route, and stale owner/generation publications cannot survive replacement.
	 */
	public List<RelayCandidate> relayCandidates() {
		return List.copyOf(relayCandidates.values());
	}

	/** Resolver fence: a listener endpoint is valid only while its exact host publication remains active. */
	public boolean ownsTopologyEndpoint(RadioEndpoint endpoint, ProductionComputer computer) {
		endpoint = Objects.requireNonNull(endpoint, "endpoint");
		computer = Objects.requireNonNull(computer, "computer");
		TopologyRegistration registration = topologyEndpoints.get(endpoint.key());
		if (registration == null || !registration.endpoint().equals(endpoint)) return false;
		Host host = hosts.get(computer.id());
		return host != null && registration.owner().equals(host.owner())
			&& registration.owner().computerId().equals(computer.id())
			&& registration.owner().ownerToken().equals(computer.listenerOwnerToken())
			&& computer.epoch().map(epoch -> epoch.epochId().equals(registration.owner().epochId()))
				.orElse(false)
			&& computer.canConfigureExternalListeners();
	}

	private ExternalListenerService.Publication publish(ExternalListenerService.ListenerComputer computer,
		CommunicationFrequency frequency, ExternalListenerService.Reservation reservation) {
		Host host = requireHost(computer.id());
		if (!host.primaryFrequency().configured())
			throw new IllegalStateException("formed Cluster has no primary frequency");
		String sourceIdentity = "slot:" + reservation.slot() + ":source";
		String relayIdentity = "slot:" + reservation.slot() + ":relay";
		UUID externalCluster = named("external-listener-cluster", host.computerId(), sourceIdentity);
		UUID sourceMember = named("external-listener-member", host.computerId(), sourceIdentity);
		UUID relayMember = named("external-listener-member", host.computerId(), relayIdentity);
		RadioEndpoint.Key sourceKey = new RadioEndpoint.Key(externalCluster, sourceMember);
		RadioEndpoint.Key relayKey = new RadioEndpoint.Key(host.clusterId(), relayMember);
		RadioEndpoint sourcePrototype = new RadioEndpoint(externalCluster, sourceMember, host.address(), 0,
			frequency, 1, java.util.Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint relayPrototype = new RadioEndpoint(host.clusterId(), relayMember, host.address(), 0,
			frequency, 1, java.util.Set.of(ProductionCapability.EXTENDED_RADIO));
		long sourceGeneration = router.endpointGenerationForPublication(sourcePrototype).orElseThrow(() ->
			new IllegalStateException("listener source endpoint generation exhausted"));
		long relayGeneration = router.endpointGenerationForPublication(relayPrototype).orElseThrow(() ->
			new IllegalStateException("listener relay endpoint generation exhausted"));
		RadioEndpoint source = new RadioEndpoint(externalCluster, sourceMember, host.address(), sourceGeneration,
			frequency, 1, java.util.Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint relay = new RadioEndpoint(host.clusterId(), relayMember, host.address(), relayGeneration,
			frequency, 1, java.util.Set.of(ProductionCapability.EXTENDED_RADIO));
		return new ExternalListenerService.Publication(source, relay, new SubnetBoundaryBinding(
			externalCluster, named("external-listener-source-network", host.computerId(), sourceIdentity),
			named("external-listener-target-network", host.clusterId(), host.primaryFrequency().value()),
			host.clusterId(), frequency, source.hop(), relay.hop()));
	}

	private boolean authorizedIngress(ExternalListenerService.ListenerComputer computer,
		CommunicationFrequency frequency, ExternalListenerService.IngressAuthority authority) {
		Host host = hosts.get(computer.id());
		if (host == null || !computer.listenerOwner().epochId().equals(host.epochId())) return false;
		return switch (authority) {
			case ExternalListenerService.IngressAuthority.LocalGoal localGoal -> runtime
				.snapshot(host.clusterId()).map(state -> {
					var goal = state.goals().get(localGoal.goalId());
					return goal != null && goal.owningClusterId().equals(host.clusterId())
						&& goal.state() != GoalState.TERMINAL;
				}).orElse(false);
			case ExternalListenerService.IngressAuthority.ExistingLease lease ->
				router.hasExactLease(lease.leaseId(), lease.revision())
					&& router.goalOwner(lease.leaseId()).filter(host.clusterId()::equals).isPresent();
		};
	}

	private void project(ProductionComputer computer, Host host) {
		runtime.projectExternalListeners(host.clusterId(), host.epochId(), computer.id(),
			listeners.projection(computer.id()));
	}

	/** Applies only the aggregate listener CAS results; unrelated failed siblings leave topology alone. */
	private void applyCommittedTopologyTransitions() {
		for (ExternalListenerService.ListenerCommit transition :
			listeners.drainCommittedTransitions()) {
			retireCommittedTopology(transition.owner(), transition.predecessor(), transition.replacement());
			observeCommittedTopology(transition.owner(), transition.replacement());
		}
	}

	private void retireCommittedTopology(ExternalListenerService.ListenerOwner owner,
		Optional<ExternalListenerService.Publication> predecessor,
		Optional<ExternalListenerService.Publication> replacement) {
		if (predecessor.isEmpty()) return;
		Map<RadioEndpoint.Key, RadioEndpoint> replacementEndpoints = replacement.map(
			ExternalListenerRuntime::topologyEndpoints).orElseGet(Map::of);
		for (RadioEndpoint endpoint : topologyEndpoints(predecessor.orElseThrow()).values()) {
			TopologyRegistration current = topologyEndpoints.get(endpoint.key());
			if (current == null || !current.owner().equals(owner) || !current.endpoint().equals(endpoint)
				|| endpoint.equals(replacementEndpoints.get(endpoint.key()))) continue;
			topology.retire(owner, endpoint);
			topologyEndpoints.remove(endpoint.key(), current);
		}
	}

	private void observeCommittedTopology(ExternalListenerService.ListenerOwner owner,
		Optional<ExternalListenerService.Publication> replacement) {
		if (replacement.isEmpty()) return;
		for (RadioEndpoint endpoint : topologyEndpoints(replacement.orElseThrow()).values()) {
			TopologyRegistration current = topologyEndpoints.get(endpoint.key());
			if (current != null && current.owner().equals(owner) && current.endpoint().equals(endpoint))
				continue;
			if (current != null) continue;
			topology.observe(owner, endpoint);
			topologyEndpoints.put(endpoint.key(), new TopologyRegistration(owner, endpoint));
		}
	}

	private static Map<RadioEndpoint.Key, RadioEndpoint> topologyEndpoints(
		ExternalListenerService.Publication publication) {
		Map<RadioEndpoint.Key, RadioEndpoint> endpoints = new LinkedHashMap<>();
		endpoints.put(publication.endpoint().key(), publication.endpoint());
		endpoints.put(publication.relayEndpoint().key(), publication.relayEndpoint());
		return Map.copyOf(endpoints);
	}

	/** Admission tracks active reservations, rather than all retained Router authorities. */
	private void synchronizeRelayCandidates(Host host) {
		Map<RadioEndpoint.Key, RelayCandidate> desiredRelays = new LinkedHashMap<>();
		for (ExternalListenerService.Publication publication : listeners.publications(host.computerId())) {
			desiredRelays.put(publication.relayEndpoint().key(), new RelayCandidate(host.owner(),
				publication.relayEndpoint()));
		}
		for (Map.Entry<RadioEndpoint.Key, RelayCandidate> entry :
			List.copyOf(relayCandidates.entrySet())) {
			RelayCandidate prior = entry.getValue();
			if (!prior.owner().computerId().equals(host.computerId())) continue;
			RelayCandidate replacement = desiredRelays.get(entry.getKey());
			if (prior.equals(replacement)) continue;
			relayCandidates.remove(entry.getKey(), prior);
		}
		for (RelayCandidate candidate : desiredRelays.values())
			relayCandidates.put(candidate.endpoint().key(), candidate);
	}
	private Host requireHost(UUID computerId) {
		Host host = hosts.get(computerId);
		if (host == null) throw new IllegalStateException("listener host is not live");
		return host;
	}
	private static UUID named(String prefix, UUID id, String value) {
		return UUID.nameUUIDFromBytes((prefix + ":" + id + ":" + value)
			.getBytes(StandardCharsets.UTF_8));
	}

	private final class RuntimeScheduler implements ExternalListenerService.Scheduler {
		@Override public Optional<ExternalListenerService.Reservation> reserve(UUID computerId,
			CommunicationFrequency frequency, int slotLimit) {
			Host host = hosts.get(computerId);
			if (host == null) return Optional.empty();
			UUID leaseId = named("external-listener-lease", computerId, frequency.value());
			Optional<FactoryRuntimeService.ExternalListenerProcessReservation> allocated = runtime
				.reserveExternalListenerProcess(host.clusterId(), host.epochId(), computerId, leaseId);
			if (allocated.isEmpty()) return Optional.empty();
			FactoryRuntimeService.ExternalListenerProcessReservation process = allocated.orElseThrow();
			reservations.put(leaseId, process);
			return Optional.of(new ExternalListenerService.Reservation(leaseId, computerId, process.slot()));
		}
		@Override public void release(ExternalListenerService.Reservation reservation) {
			FactoryRuntimeService.ExternalListenerProcessReservation process = reservations.remove(
				Objects.requireNonNull(reservation, "reservation").leaseId());
			if (process != null) runtime.releaseExternalListenerProcess(process);
		}
		@Override public int occupancy(UUID computerId, ProcessKind kind) {
			if (kind != ProcessKind.RADIO_LISTENER) return 0;
			Host host = hosts.get(computerId);
			return host == null ? 0 : runtime.snapshot(host.clusterId()).map(state -> (int) state.processes()
				.values().stream().filter(process -> process.kind() == ProcessKind.RADIO_LISTENER
					&& process.owningComputerId().equals(computerId)).count()).orElse(0);
		}
	}

	/** Constructor-private proof joining this exact production owner to its private service. */
	public static final class InstallationCapability {
		private final ExternalListenerRuntime runtime;
		private final ExternalListenerService service;
		private InstallationCapability(ExternalListenerRuntime runtime,
			ExternalListenerService service) {
			this.runtime = runtime;
			this.service = service;
		}
		public ExternalListenerRuntime runtime() { return runtime; }
		public ExternalListenerService service() { return service; }
	}

	/** Constructor-private proof for one exact loaded production Computer instance. */
	public static final class LoadedComputerCapability {
		private final ExternalListenerRuntime runtime;
		private final ProductionComputer computer;
		private final UUID clusterId;
		private final UUID epochId;
		private final ExternalListenerService.ListenerOwner owner;
		private LoadedComputerCapability(ExternalListenerRuntime runtime,
			ProductionComputer computer, UUID clusterId, UUID epochId,
			ExternalListenerService.ListenerOwner owner) {
			this.runtime = runtime;
			this.computer = computer;
			this.clusterId = clusterId;
			this.epochId = epochId;
			this.owner = owner;
		}
		public boolean belongsTo(ExternalListenerRuntime expected) { return runtime == expected; }
		public boolean matchesCurrentComputer() {
			try {
				return computer.hasExactEpoch(clusterId, epochId) && computer.listenerOwner().equals(owner)
					&& computer.id().equals(owner.computerId());
			} catch (RuntimeException unavailable) {
				return false;
			}
		}
		public ProductionComputer computer() { return computer; }
		public UUID clusterId() { return clusterId; }
		public UUID epochId() { return epochId; }
		public ExternalListenerService.ListenerOwner owner() { return owner; }
	}

	private record Host(ExternalListenerService.ListenerOwner owner, UUID computerId, UUID clusterId,
		UUID epochId, CommunicationFrequency primaryFrequency, SpaceAddress address,
		LoadedComputerCapability capability) {
		private boolean samePublicationInputs(Host other) {
			return owner.equals(other.owner) && computerId.equals(other.computerId)
				&& clusterId.equals(other.clusterId) && epochId.equals(other.epochId)
				&& primaryFrequency.equals(other.primaryFrequency) && address.equals(other.address);
		}
	}

	private record TopologyRegistration(ExternalListenerService.ListenerOwner owner,
		RadioEndpoint endpoint) {}

	/** The registry retains only exact current owner/generation relay publications. */
	public record RelayCandidate(ExternalListenerService.ListenerOwner owner, RadioEndpoint endpoint) {
		public RelayCandidate {
			owner = Objects.requireNonNull(owner, "owner");
			endpoint = Objects.requireNonNull(endpoint, "endpoint");
			if (!endpoint.supports(ProductionCapability.EXTENDED_RADIO))
				throw new IllegalArgumentException("listener relay must support extended radio");
		}
	}

	/** Kept narrow: publication activation, replacement and release are the only topology events. */
	public interface TopologySink {
		TopologySink NONE = new TopologySink() {
			@Override public void observe(ExternalListenerService.ListenerOwner owner,
				RadioEndpoint endpoint) {}
			@Override public void retire(ExternalListenerService.ListenerOwner owner,
				RadioEndpoint endpoint) {}
		};
		void observe(ExternalListenerService.ListenerOwner owner, RadioEndpoint endpoint);
		void retire(ExternalListenerService.ListenerOwner owner, RadioEndpoint endpoint);
	}
}
