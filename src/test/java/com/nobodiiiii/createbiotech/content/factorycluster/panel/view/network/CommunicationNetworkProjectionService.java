package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationCommandService;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService.PanelPublicationFence;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService.PanelListenerCapture;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioLinkPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyFrame;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyPublicationAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelAuthorityValidator;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ClusterCardProjection.FormationState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ClusterCardProjection.LinkType;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ClusterCardProjection.OperationalState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjection.Status;

/** Traverses one immutable topology publication and emits public Cluster-level cards only. */
public final class CommunicationNetworkProjectionService {
	private final SnapshotLookup snapshots;
	private final RadioLinkPolicy linkPolicy;
	private final int rowLimit;

	public CommunicationNetworkProjectionService(SnapshotLookup snapshots,
		RadioLinkPolicy linkPolicy, int rowLimit) {
		this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
		this.linkPolicy = Objects.requireNonNull(linkPolicy, "linkPolicy");
		if (rowLimit <= 0
			|| rowLimit > CommunicationNetworkProjection.MAX_WIRE_ROWS)
			throw new IllegalArgumentException("invalid communication projection bounds");
		this.rowLimit = rowLimit;
	}

	public CommunicationNetworkProjection project(
		FactoryPanelAuthorityValidator.ViewValidation authority) {
		FactoryPanelAuthorityValidator.ViewRequest request = Objects.requireNonNull(authority,
			"authority").request();
		if (request.view() != FactoryPanelView.NETWORK_COMMUNICATION || request.limit() > rowLimit
			|| request.limit() == 0 || request.selection().isPresent())
			throw new IllegalArgumentException("wrong communication projection authority");
		FactoryPanelOpening opening = request.opening();
		PublishedSnapshot snapshot = snapshots.capture(opening.clusterId()).orElse(null);
		if (snapshot == null) return CommunicationNetworkProjection.stale(opening.clusterId(),
			opening.epochId(), 0, 0, rowLimit);
		if (!snapshot.isCurrentPublication()
			|| !snapshot.localClusterId().equals(opening.clusterId())
			|| !snapshot.localEpochId().equals(opening.epochId())
			|| request.knownRevision().isPresent()
				&& request.knownRevision().orElseThrow() != snapshot.revision())
			return CommunicationNetworkProjection.stale(opening.clusterId(), opening.epochId(),
				snapshot.generation(), snapshot.revision(), rowLimit);

		List<ClusterCardProjection> all = cards(snapshot);
		if (!snapshot.isCurrentPublication())
			return CommunicationNetworkProjection.stale(opening.clusterId(), opening.epochId(),
				snapshot.generation(), snapshot.revision(), rowLimit);
		ClusterCardProjection local = all.stream().filter(card -> !card.remote()).findFirst()
			.orElseThrow(() -> new IllegalStateException("communication publication lacks local card"));
		List<ClusterCardProjection> remote = all.stream().filter(ClusterCardProjection::remote).toList();
		List<ListenerEditorProjection> editors = snapshot.listenerEditors();
		CommunicationPage lane = request.communicationPage();
		int limit = Math.min(request.limit(), rowLimit);
		boolean listenerEditing = !editors.isEmpty();
		int total = lane == CommunicationPage.LISTENERS ? editors.size() : remote.size();
		int from = Math.min(request.offset(), total);
		int capacity = limit - (lane == CommunicationPage.OVERVIEW ? 1 : 0);
		int to = Math.min(total, Math.addExact(from, capacity));
		ArrayList<ClusterCardProjection> page = new ArrayList<>();
		if (lane == CommunicationPage.OVERVIEW) page.add(local);
		if (lane != CommunicationPage.LISTENERS) page.addAll(remote.subList(from, to));
		return new CommunicationNetworkProjection(opening.clusterId(), opening.epochId(),
			snapshot.generation(), snapshot.revision(), Status.CURRENT, from, all.size(),
			page, rowLimit, Optional.of(new LocalCommunicationEditProjection(
				snapshot.declarationRevision(), lane == CommunicationPage.LISTENERS
					? editors.subList(from, to) : List.of(), editors.size(), listenerEditing)), lane);
	}


	private List<ClusterCardProjection> cards(PublishedSnapshot snapshot) {
		Map<UUID, List<RadioEndpoint>> endpointsByCluster = new LinkedHashMap<>();
		Set<UUID> privateSubnetClusters = snapshot.subnets().stream()
			.map(publication -> publication.binding().sourceClusterId())
			.filter(clusterId -> !clusterId.equals(snapshot.localClusterId()))
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		for (RadioEndpoint endpoint : snapshot.endpoints()) {
			if (privateSubnetClusters.contains(endpoint.clusterId())) continue;
			endpointsByCluster.computeIfAbsent(endpoint.clusterId(), ignored -> new ArrayList<>())
				.add(endpoint);
		}

		Map<UUID, Reach> reached = new HashMap<>();
		ArrayDeque<UUID> queue = new ArrayDeque<>();
		reached.put(snapshot.localClusterId(), new Reach(0, LinkType.ADJACENT));
		queue.add(snapshot.localClusterId());
		while (!queue.isEmpty()) {
			UUID sourceCluster = queue.removeFirst();
			Reach sourceReach = reached.get(sourceCluster);
			for (RadioEndpoint source : endpointsByCluster.getOrDefault(sourceCluster, List.of())) {
				for (Map.Entry<UUID, List<RadioEndpoint>> targetEntry : endpointsByCluster.entrySet()) {
					UUID targetCluster = targetEntry.getKey();
					if (sourceCluster.equals(targetCluster)) continue;
					Reach candidate = null;
					for (RadioEndpoint target : targetEntry.getValue()) {
						RadioLinkPolicy.EdgeResult edge = linkPolicy.edge(source, target,
							snapshot.topology());
						if (!edge.reachable() || edge.virtual()) continue;
						int hops = Math.addExact(sourceReach.radioHops(), 1);
						LinkType type = hops > 1 ? LinkType.RELAY
							: edge == RadioLinkPolicy.EdgeResult.ORDINARY
								? LinkType.ORDINARY : LinkType.EXTENDED;
						Reach next = new Reach(hops, type);
						if (candidate == null || next.compareTo(candidate) < 0) candidate = next;
					}
					if (candidate == null) continue;
					Reach prior = reached.get(targetCluster);
					if (prior == null || candidate.compareTo(prior) < 0) {
						reached.put(targetCluster, candidate);
						queue.addLast(targetCluster);
					}
				}
			}
		}

		ArrayList<Map.Entry<UUID, Reach>> ordered = new ArrayList<>(reached.entrySet());
		ordered.removeIf(entry -> !snapshot.clusters().containsKey(entry.getKey()));
		ordered.sort(Map.Entry.<UUID, Reach>comparingByValue()
			.thenComparing(entry -> ClusterCardProjection.shortId(entry.getKey()))
			.thenComparing(Map.Entry::getKey));
		ArrayList<ClusterCardProjection> cards = new ArrayList<>();
		for (Map.Entry<UUID, Reach> entry : ordered) {
			UUID clusterId = entry.getKey();
			ClusterPublicState state = snapshot.clusters().get(clusterId);
			cards.add(ClusterCardProjection.cluster(clusterId, state.configuration().role(),
				state.formationState(), state.operationalState(), state.usedSlots(), state.totalSlots(),
				state.activeTasks(), state.waitingTasks(), entry.getValue().linkType(),
				state.configuration().primaryFrequency(), state.logisticsAliases(),
				!clusterId.equals(snapshot.localClusterId()), state.listenerEditing()
					|| clusterId.equals(snapshot.localClusterId()) && !snapshot.listenerEditors().isEmpty()));
		}

		snapshot.subnets().stream().filter(publication -> reached.containsKey(
			publication.binding().targetClusterId())).sorted(Comparator.comparing((SubnetPublication publication) ->
			ClusterCardProjection.shortId(publication.boundaryId())).thenComparing(
				SubnetPublication::boundaryId)).forEach(publication -> {
			SubnetBoundaryService.BoundaryCard boundary = publication.projection().cards().getFirst();
			cards.add(ClusterCardProjection.subnet(publication.boundaryId(),
				boundary.listenerFrequency(), publication.projection().serviceKinds()));
		});
		Set<String> publicIds = new HashSet<>();
		for (ClusterCardProjection card : cards)
			if (!publicIds.add(card.clusterId()))
				throw new IllegalStateException("communication public identity collision");
		return List.copyOf(cards);
	}

	@FunctionalInterface
	public interface SnapshotLookup {
		Optional<PublishedSnapshot> capture(UUID localClusterId);
	}

	/** One unpublished, immutable capture returned while the owning runtime lock is held. */
	public record CaptureFacts(ClusterEngineState localEngine, long directoryGeneration,
		RadioTopologyPublicationAuthority.Publication topologyPublication,
		Map<UUID, ClusterPublicState> clusters, List<SubnetPublication> subnets,
		Map<UUID, ClusterCommunicationCommandService.CommandAuthority> localPanelAuthorities,
		Set<com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority>
			loadedLocalBindingAuthorities) {
		public CaptureFacts {
			localEngine = Objects.requireNonNull(localEngine, "local engine");
			topologyPublication = Objects.requireNonNull(topologyPublication,
				"topology publication");
			if (!topologyPublication.isCurrent())
				throw new IllegalArgumentException("communication topology publication is stale");
			List<RadioEndpoint> endpoints = topologyPublication.endpoints();
			clusters = Map.copyOf(Objects.requireNonNull(clusters, "clusters"));
			subnets = List.copyOf(Objects.requireNonNull(subnets, "subnets"));
			localPanelAuthorities = Map.copyOf(Objects.requireNonNull(localPanelAuthorities,
				"local Panel authorities"));
			loadedLocalBindingAuthorities = Set.copyOf(Objects.requireNonNull(
				loadedLocalBindingAuthorities, "loaded local binding authorities"));
			if (directoryGeneration < 0
				|| endpoints.size() > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES
				|| clusters.size() > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES
				|| subnets.size() > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES
				|| !clusters.containsKey(localEngine.clusterId()))
				throw new IllegalArgumentException("invalid communication capture bounds");
			Set<RadioEndpoint.Key> endpointKeys = new HashSet<>();
			Map<RadioEndpoint.Hop, RadioEndpoint> byHop = new HashMap<>();
			for (RadioEndpoint endpoint : endpoints) {
				if (!endpointKeys.add(endpoint.key()))
					throw new IllegalArgumentException("duplicate communication endpoint");
				byHop.put(endpoint.hop(), endpoint);
			}
			for (Map.Entry<UUID, ClusterPublicState> entry : clusters.entrySet())
				if (!entry.getKey().equals(entry.getValue().configuration().clusterId()))
					throw new IllegalArgumentException("communication Cluster index differs from config");
			Set<UUID> boundaryIds = new HashSet<>();
			for (SubnetPublication subnet : subnets) {
				if (!boundaryIds.add(subnet.boundaryId()))
					throw new IllegalArgumentException("duplicate subnet publication");
				if (!topologyPublication.owns(subnet.binding()))
					throw new IllegalArgumentException("subnet publication is not router-authoritative");
				RadioEndpoint source = byHop.get(subnet.binding().sourceHop());
				RadioEndpoint target = byHop.get(subnet.binding().targetHop());
				if (source == null || target == null
					|| !source.frequency().equals(subnet.binding().listenerFrequency())
					|| !target.frequency().equals(subnet.binding().listenerFrequency()))
					throw new IllegalArgumentException("subnet publication lacks exact endpoint authority");
			}
			for (Map.Entry<UUID, ClusterCommunicationCommandService.CommandAuthority> entry :
				localPanelAuthorities.entrySet()) {
				ClusterCommunicationCommandService.CommandAuthority authority = entry.getValue();
				if (!entry.getKey().equals(authority.localMemberId())
					|| !localEngine.clusterId().equals(authority.clusterId())
					|| !localEngine.epochId().equals(authority.epochId())
					|| !loadedLocalBindingAuthorities.contains(authority.authority()))
					throw new IllegalArgumentException("Panel command authority is not local and loaded");
			}
		}
		private CaptureFingerprint fingerprint(List<PanelListenerCapture> localListeners) {
			return new CaptureFingerprint(localEngine, directoryGeneration, topologyPublication.semanticKey(),
				clusters, subnets, localPanelAuthorities, List.copyOf(localListeners));
		}
		public RadioTopologyFrame topology() { return topologyPublication.frame(); }
		public List<RadioEndpoint> endpoints() { return topologyPublication.endpoints(); }
	}

	/** Opaque result whose monotonically increasing revision belongs to one runtime owner. */
	public static final class PublishedSnapshot {
		private final PublicationAuthority authority;
		private final ClusterPublicationOwner owner;
		private final PanelPublicationFence fence;
		private final long revision;
		private final CaptureFacts facts;
		private final Map<String, PanelListenerCapture> localListenerTargets;
		private PublishedSnapshot(PublicationAuthority authority, ClusterPublicationOwner owner,
			PanelPublicationFence fence, long revision, CaptureFacts facts,
			List<PanelListenerCapture> localListeners) {
			this.authority = authority;
			this.owner = owner;
			this.fence = fence;
			this.revision = revision;
			this.facts = facts;
			LinkedHashMap<String, PanelListenerCapture> targets = new LinkedHashMap<>();
			for (PanelListenerCapture listener : localListeners) {
				String target = listenerTarget(facts.localEngine().clusterId(),
					facts.localEngine().epochId(), listener.owner());
				if (targets.putIfAbsent(target, listener) != null)
					throw new IllegalArgumentException("opaque listener target collision");
			}
			localListenerTargets = Map.copyOf(targets);
		}
		public long revision() { return revision; }
		public UUID localClusterId() { return facts.localEngine().clusterId(); }
		public UUID localEpochId() { return facts.localEngine().epochId(); }
		public long generation() { return facts.localEngine().generation(); }
		public long directoryGeneration() { return facts.directoryGeneration(); }
		public RadioTopologyFrame topology() { return facts.topology(); }
		public List<RadioEndpoint> endpoints() { return facts.endpoints(); }
		public Map<UUID, ClusterPublicState> clusters() { return facts.clusters(); }
		public List<SubnetPublication> subnets() { return facts.subnets(); }
		public ClusterEngineState localEngine() { return facts.localEngine(); }
		public boolean isCurrentPublication() {
			return authority.isCurrent(this) && facts.topologyPublication().isCurrent()
				&& fence.isCurrent(facts.localEngine(),
				facts.clusters().get(localClusterId()).configuration().role());
		}
		/** Exact owner identity check used while a local listener CAS intentionally changes facts. */
		public boolean isCurrentOwnerPublication() { return authority.isCurrent(this); }
		public long declarationRevision() {
			return facts.clusters().get(localClusterId()).configuration().revision();
		}
		public List<ListenerEditorProjection> listenerEditors() {
			return localListenerTargets.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.map(entry -> new ListenerEditorProjection(entry.getKey(),
					entry.getValue().configuration().frequencies(),
					entry.getValue().configuration().revision())).toList();
		}
		public ClusterCommunicationCommandService.CommandAuthority commandAuthority(UUID panelId) {
			ClusterCommunicationCommandService.CommandAuthority authority =
				facts.localPanelAuthorities().get(Objects.requireNonNull(panelId, "panel id"));
			if (!isCurrentPublication() || authority == null)
				throw new IllegalStateException("Panel member is not in the current local publication");
			return authority;
		}
		public ExternalListenerService.EditResult replaceListener(String opaqueTarget,
			long expectedConfigurationRevision, List<CommunicationFrequency> frequencies) {
			PanelListenerCapture capture = localListenerTargets.get(Objects.requireNonNull(
				opaqueTarget, "opaque listener target"));
			if (capture == null) return ExternalListenerService.EditResult.NOT_FORMED;
			return fence.replaceCommunicationListener(this, capture,
				expectedConfigurationRevision, frequencies);
		}
	}

	/** Runtime-owned owner map; publication additionally requires an opaque current runtime fence. */
	public static final class PublicationAuthority implements AutoCloseable {
		private final Map<UUID, ClusterPublicationOwner> owners = new HashMap<>();
		private boolean closed;
		private PublicationAuthority() {}
		public static PublicationAuthority runtimeAuthority() { return new PublicationAuthority(); }
		public synchronized PublishedSnapshot publish(PanelPublicationFence fence, CaptureFacts facts,
			List<PanelListenerCapture> localListeners) {
			facts = Objects.requireNonNull(facts, "facts");
			localListeners = List.copyOf(Objects.requireNonNull(localListeners, "local listeners"));
			Objects.requireNonNull(fence, "fence").requireCommunicationFacts(facts);
			if (closed) throw new IllegalStateException("communication publication authority is closed");
			return owners.computeIfAbsent(facts.localEngine().clusterId(), ClusterPublicationOwner::new)
				.publish(this, fence, facts, localListeners);
		}
		public synchronized Optional<PublishedSnapshot> capture(UUID clusterId) {
			if (closed) return Optional.empty();
			ClusterPublicationOwner owner = owners.get(Objects.requireNonNull(clusterId, "cluster id"));
			return owner == null ? Optional.empty() : Optional.ofNullable(owner.current);
		}
		public synchronized void retireEpoch(UUID clusterId, UUID epochId) {
			ClusterPublicationOwner owner = owners.get(Objects.requireNonNull(clusterId, "cluster id"));
			if (owner != null) owner.retire(Objects.requireNonNull(epochId, "epoch id"));
		}
		public synchronized void revokeCluster(UUID clusterId) {
			ClusterPublicationOwner owner = owners.remove(Objects.requireNonNull(clusterId, "cluster id"));
			if (owner != null) owner.revoke();
		}
		public synchronized void retireAll() {
			if (closed) return;
			owners.values().forEach(ClusterPublicationOwner::retireCurrent);
		}
		private synchronized boolean isCurrent(PublishedSnapshot candidate) {
			return !closed && candidate.owner != null && candidate.owner.isCurrent(candidate);
		}
		@Override public synchronized void close() {
			if (closed) return;
			closed = true;
			owners.values().forEach(ClusterPublicationOwner::revoke);
			owners.clear();
		}
	}

	private static final class ClusterPublicationOwner {
		private final UUID clusterId;
		private final Set<UUID> retiredEpochs = new HashSet<>();
		private UUID epochId;
		private long generation;
		private long revision;
		private CaptureFingerprint fingerprint;
		private PublishedSnapshot current;
		private boolean revoked;
		private boolean revisionExhausted;
		private ClusterPublicationOwner(UUID clusterId) { this.clusterId = clusterId; }
		private PublishedSnapshot publish(PublicationAuthority authority, PanelPublicationFence fence,
			CaptureFacts facts, List<PanelListenerCapture> localListeners) {
			ClusterEngineState engine = facts.localEngine();
			if (revoked || revisionExhausted || !clusterId.equals(engine.clusterId()))
				throw new IllegalStateException("communication publication owner is revoked");
			if (epochId != null && !epochId.equals(engine.epochId())) {
				if (retiredEpochs.contains(engine.epochId()))
					throw new IllegalArgumentException("communication publication reopens retired epoch");
				retiredEpochs.add(epochId);
				epochId = null;
				current = null;
				fingerprint = null;
			}
			if (epochId == null) {
				epochId = engine.epochId();
				generation = engine.generation();
				revision = 0;
			} else if (engine.generation() < generation) {
				throw new IllegalArgumentException("communication publication generation regressed");
			}
			CaptureFingerprint next = facts.fingerprint(localListeners);
			if (current != null && fingerprint.equals(next)
				&& current.facts.topologyPublication() == facts.topologyPublication()
				&& current.fence.samePublicationFence(fence)) return current;
			if (current != null && !fingerprint.equals(next)) revision = nextRevision(revision);
			generation = engine.generation();
			fingerprint = next;
			current = new PublishedSnapshot(authority, this, fence, revision, facts, localListeners);
			return current;
		}
		private boolean isCurrent(PublishedSnapshot candidate) {
			return !revoked && current == candidate;
		}
		private void retire(UUID exactEpochId) {
			if (revoked || epochId == null || !epochId.equals(exactEpochId)) return;
			retiredEpochs.add(epochId);
			current = null;
			fingerprint = null;
			if (revision == Long.MAX_VALUE) revisionExhausted = true;
			else revision++;
		}
		private void retireCurrent() {
			if (epochId != null) retire(epochId);
		}
		private void revoke() {
			revoked = true;
			current = null;
			fingerprint = null;
			retiredEpochs.clear();
			epochId = null;
		}
	}

	private record CaptureFingerprint(ClusterEngineState localEngine, long directoryGeneration,
		Object topologySemantics,
		Map<UUID, ClusterPublicState> clusters, List<SubnetPublication> subnets,
		Map<UUID, ClusterCommunicationCommandService.CommandAuthority> localPanelAuthorities,
		List<PanelListenerCapture> localListeners) {}

	private static long nextRevision(long current) {
		if (current == Long.MAX_VALUE)
			throw new IllegalStateException("communication view revision exhausted");
		return current + 1;
	}

	private static String listenerTarget(UUID clusterId, UUID epochId,
		ExternalListenerService.ListenerOwner owner) {
		UUID value = UUID.nameUUIDFromBytes(("communication-listener:" + clusterId + ":" + epochId
			+ ":" + owner.computerId() + ":" + owner.ownerToken())
			.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return "listener-" + value.toString().replace("-", "").substring(0, 16);
	}

	/** Aggregate-only runtime facts: this type has no Computer or Goal identity fields. */
	public record ClusterPublicState(ClusterCommunicationConfiguration configuration,
		FormationState formationState, OperationalState operationalState, int usedSlots,
		int totalSlots, int activeTasks, int waitingTasks, List<String> logisticsAliases,
		boolean listenerEditing) {
		public ClusterPublicState {
			configuration = Objects.requireNonNull(configuration, "configuration");
			formationState = Objects.requireNonNull(formationState, "formationState");
			operationalState = Objects.requireNonNull(operationalState, "operationalState");
			logisticsAliases = List.copyOf(Objects.requireNonNull(logisticsAliases,
				"logisticsAliases"));
			if (formationState == FormationState.BOUNDARY || usedSlots < 0 || totalSlots < 0
				|| usedSlots > totalSlots || activeTasks < 0 || waitingTasks < 0)
				throw new IllegalArgumentException("invalid public Cluster aggregate");
		}

		public static ClusterPublicState fromEngine(ClusterEngineState engine,
			ClusterCommunicationConfiguration configuration, List<String> logisticsAliases,
			boolean listenerEditing) {
			engine = Objects.requireNonNull(engine, "engine");
			configuration = Objects.requireNonNull(configuration, "configuration");
			if (!engine.clusterId().equals(configuration.clusterId()))
				throw new IllegalArgumentException("runtime and communication Cluster differ");
			int total = 0;
			int loaded = 0;
			for (ClusterEngineState.MemberSnapshot member : engine.members()) {
				total = Math.addExact(total, member.profile().processSlots());
				if (member.loaded()) loaded++;
			}
			int used = Math.toIntExact(engine.processes().values().stream()
				.filter(process -> process.state() != ProcessState.TERMINAL).count());
			int active = Math.toIntExact(engine.goals().values().stream()
				.filter(goal -> goal.state() == GoalState.RUNNABLE).count());
			int waiting = Math.toIntExact(engine.goals().values().stream()
				.filter(goal -> goal.state() == GoalState.WAITING).count());
			OperationalState operational = loaded == 0 ? OperationalState.OFFLINE
				: loaded == engine.members().size() ? OperationalState.ONLINE
					: OperationalState.DEGRADED;
			return new ClusterPublicState(configuration, FormationState.FORMED, operational,
				used, total, active, waiting, logisticsAliases, listenerEditing);
		}
	}

	/** Exact current router binding paired with the public boundary service projection. */
	public record SubnetPublication(UUID boundaryId, SubnetBoundaryBinding binding,
		SubnetBoundaryService.SubnetBoundaryProjection projection) {
		public SubnetPublication {
			boundaryId = Objects.requireNonNull(boundaryId, "boundaryId");
			binding = Objects.requireNonNull(binding, "binding");
			projection = Objects.requireNonNull(projection, "projection");
			if (!projection.cards().getFirst().listenerFrequency().equals(binding.listenerFrequency()))
				throw new IllegalArgumentException("subnet projection differs from router binding");
		}
	}

	private record Reach(int radioHops, LinkType linkType) implements Comparable<Reach> {
		private Reach {
			if (radioHops < 0) throw new IllegalArgumentException("negative communication hop count");
			linkType = Objects.requireNonNull(linkType, "linkType");
		}
		@Override public int compareTo(Reach other) {
			int distance = Integer.compare(radioHops, other.radioHops);
			return distance != 0 ? distance : Integer.compare(linkType.ordinal(), other.linkType.ordinal());
		}
	}
}
