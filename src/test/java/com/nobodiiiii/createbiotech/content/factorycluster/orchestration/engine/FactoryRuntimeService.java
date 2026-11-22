package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ChildExecutionRevision;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerRuntime;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentPendingRunQueue;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentNewTaskDefaults;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentNewTaskDefaults.FormationPublication;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentFormation;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentFormation.AgentServiceIdentity;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentProcess;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionRecipeCatalog;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryDirectory;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryRegistration;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibrarySearchCoordinator;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.DefaultRoutePolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.CoordinationAssociation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionCoordinator;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRevision;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteProcessLease;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyPublicationAuthority;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternReply;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.PatternPriorityPolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskProjectionSnapshot;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskProjectionService;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjectionService;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Server-owned coordination boundary. All methods are called from the server thread. */
public final class FactoryRuntimeService implements AutoCloseable {
	private static final int MAX_READY_LIBRARY_REPLIES = 4096;
	private final FactoryRuntimeConfig config;
	private final FactoryComputeWorker worker;
	private final AgentPendingRunQueue pendingAgentRuns;
	private final int routeSelectionScopeCapacity;
	private final TaskProjectionSnapshot.PublicationAuthority taskPublicationAuthority =
		TaskProjectionSnapshot.PublicationAuthority.runtimeAuthority();
	private final AgentNewTaskDefaults.PublicationAuthority agentPublicationAuthority =
		AgentNewTaskDefaults.PublicationAuthority.runtimeAuthority();
	private final CommunicationNetworkProjectionService.PublicationAuthority
		communicationPublicationAuthority =
		CommunicationNetworkProjectionService.PublicationAuthority.runtimeAuthority();
	private final LinkedHashMap<UUID, PanelRolePublication> panelRolePublications =
		new LinkedHashMap<>();
	private final LinkedHashMap<PanelEpochKey, PanelRoleTombstone> panelRoleTombstones =
		new LinkedHashMap<>();
	@Nullable private PanelCommunicationSnapshotSource panelCommunicationSnapshotSource;
	@Nullable private PanelCommunicationSnapshotScope panelCommunicationSnapshotOwner;
	private long panelCommunicationSnapshotVersion;
	@Nullable private PanelCommunicationNetworkSource panelCommunicationNetworkSource;
	@Nullable private PanelCommunicationNetworkScope panelCommunicationNetworkOwner;
	private long panelCommunicationNetworkVersion;
	@Nullable private RadioTopologyPublicationAuthority panelCommunicationTopologyAuthority;
	@Nullable private PanelProductionComposition panelProductionComposition;
	private long panelCommunicationTopologyVersion;
	@Nullable private PanelListenerRegistry panelListenerRegistry;
	private long panelListenerRegistryVersion;
	private boolean panelListenerCasInProgress;
	private final LinkedHashMap<UUID, ClusterEngineState> states = new LinkedHashMap<>();
	private final LinkedHashMap<UUID, ExternalListenerClusterProjection> externalListenerProjections =
		new LinkedHashMap<>();
	private final LinkedHashMap<UUID, ExternalListenerProcessReservation>
		pendingExternalListenerReleases = new LinkedHashMap<>();
	private final LinkedHashMap<UUID, ArrayBlockingQueue<WorldFact>> facts = new LinkedHashMap<>();
	private final LinkedHashMap<UUID, ArrayBlockingQueue<WorldFact.IntentResult>> confirmations = new LinkedHashMap<>();
	private final HashSet<UUID> inFlight = new HashSet<>();
	private final LinkedHashMap<UUID, Long> inFlightSinceNanos = new LinkedHashMap<>();
	private final HashSet<UUID> continuations = new HashSet<>();
	private final LinkedHashMap<RemotePureKey, FactoryComputeWorker.RemotePureWork>
		remotePureInFlight = new LinkedHashMap<>();
	private final LinkedHashMap<RemotePureKey, RemotePureCompletion> readyRemotePureResults =
		new LinkedHashMap<>();
	private final LinkedHashMap<UUID, Long> panelMutationReservations = new LinkedHashMap<>();
	private final LinkedHashMap<GoalWaitKey, DeferredGoalWait> deferredGoalWaits =
		new LinkedHashMap<>();
	private final LinkedHashMap<UUID, AgentPendingRunQueue.PriorityLease> pendingPriorityLeaseReleases =
		new LinkedHashMap<>();
	private final LinkedHashMap<UUID, LinkedBlockingDeque<ProductionEffect>> readyIntents = new LinkedHashMap<>();
	private final ArrayDeque<FactoryComputeWorker.Diagnostic> diagnostics = new ArrayDeque<>();
	private final LinkedHashMap<UUID, String> panelAdmissionDiagnostics = new LinkedHashMap<>();
	private final LinkedHashMap<UUID, List<ProductionEffect>> scheduledIntents = new LinkedHashMap<>();
	private final LinkedHashMap<UUID, LinkedHashSet<UUID>> replayedPendingIntents =
		new LinkedHashMap<>();
	private final LinkedHashMap<UUID, ProductionRecipeCatalog> libraryCatalogs = new LinkedHashMap<>();
	private final LinkedHashMap<UUID, ProductionLibraryAccess> productionLibraryAccess =
		new LinkedHashMap<>();
	private final LinkedHashMap<ProductionSearchKey, ProductionSearchSession> productionSearches =
		new LinkedHashMap<>();
	private final LinkedHashMap<ProductionSearchKey, ProductionSearchSnapshot> productionSearchSnapshots =
		new LinkedHashMap<>();
	private final LinkedHashSet<UUID> productionReleaseDispatches = new LinkedHashSet<>();
	private final LinkedHashMap<UUID, ArrayDeque<PatternQuery>> libraryQueries = new LinkedHashMap<>();
	private final LinkedHashMap<UUID, ArrayDeque<PatternReply>> readyLibraryReplies = new LinkedHashMap<>();
	private final LinkedHashSet<UUID> inFlightLibraryWork = new LinkedHashSet<>();
	private final LinkedHashMap<UUID, RouteSelectionCoordinator> routeSelectionContexts = new LinkedHashMap<>();
	private ProductionRouteCollector productionRouteCollector = request ->
		new ProductionRouteSources(request.patternSources(), List.of(), List.of(), List.of());
	@Nullable private ProductionResolverScope productionRouteCollectorOwner;
	private long productionRouteCollectorVersion;
	private ProductionProviderResolver productionProviderResolver = request -> Optional.empty();
	@Nullable private ProductionResolverScope productionProviderResolverOwner;
	private long productionProviderResolverVersion;
	private boolean productionProviderResolverConfigured;
	private java.util.function.Function<UUID, CommunicationFrequency> productionRequesterFrequency =
		ignored -> CommunicationFrequency.UNCONFIGURED;
	@Nullable private ProductionResolverScope productionRequesterFrequencyOwner;
	private long productionRequesterFrequencyVersion;
	private long productionResolverVersion;
	private long scheduledGameTick = Long.MIN_VALUE;
	private long legacyDrainTick;
	private UUID lastScheduledCluster;
	private boolean started;
	private boolean closed;

	public FactoryRuntimeService(FactoryRuntimeConfig config) {
		this(config, new FactoryComputeWorker(new ClusterEngine(), config), RouteSelectionCoordinator.MAX_SCOPE_CAPACITY,
			null);
	}

	public FactoryRuntimeService(FactoryRuntimeConfig config,
		AgentPendingRunQueue.PriorityLeaseAllocator priorityLeaseAllocator) {
		this(config, new FactoryComputeWorker(new ClusterEngine(), config), RouteSelectionCoordinator.MAX_SCOPE_CAPACITY,
			priorityLeaseAllocator);
	}

	FactoryRuntimeService(FactoryRuntimeConfig config, FactoryComputeWorker worker) {
		this(config, worker, RouteSelectionCoordinator.MAX_SCOPE_CAPACITY, null);
	}

	FactoryRuntimeService(FactoryRuntimeConfig config, int routeSelectionScopeCapacity) {
		this(config, new FactoryComputeWorker(new ClusterEngine(), config), routeSelectionScopeCapacity,
			null);
	}

	private FactoryRuntimeService(FactoryRuntimeConfig config, FactoryComputeWorker worker,
		int routeSelectionScopeCapacity,
		AgentPendingRunQueue.PriorityLeaseAllocator priorityLeaseAllocator) {
		this.config = Objects.requireNonNull(config, "config");
		this.worker = Objects.requireNonNull(worker, "worker");
		this.pendingAgentRuns = AgentPendingRunQueue.createServerGlobal(config,
			priorityLeaseAllocator == null ? new RuntimePriorityLeaseAllocator() : priorityLeaseAllocator);
		if (routeSelectionScopeCapacity <= 0 || routeSelectionScopeCapacity > RouteSelectionCoordinator.MAX_SCOPE_CAPACITY)
			throw new IllegalArgumentException("invalid route-selection scope capacity");
		this.routeSelectionScopeCapacity = routeSelectionScopeCapacity;
	}

	/**
	 * Installs the server lifecycle adapter that captures one coherent communication declaration and
	 * Agent-directory generation. Panel publishers never accept a caller-selected role label.
	 */
	public synchronized AutoCloseable installPanelCommunicationSnapshotSource(
		PanelCommunicationSnapshotSource source) {
		requireRunning();
		source = Objects.requireNonNull(source, "source");
		if (panelCommunicationSnapshotSource != null) retireAllPanelRolePublications();
		long version = Math.incrementExact(panelCommunicationSnapshotVersion);
		PanelCommunicationSnapshotScope scope = new PanelCommunicationSnapshotScope(version);
		panelCommunicationSnapshotSource = source;
		panelCommunicationSnapshotOwner = scope;
		return scope;
	}

	/** Installs the sole same-lock source for complete communication topology publications. */
	public synchronized AutoCloseable installPanelCommunicationNetworkSource(
		PanelCommunicationNetworkSource source) {
		requireRunning();
		if (panelCommunicationTopologyAuthority == null)
			throw new IllegalStateException("Panel topology authority must be bound first");
		source = Objects.requireNonNull(source, "source");
		if (panelCommunicationNetworkSource != null) retireAllCommunicationPublications();
		long version = panelCommunicationNetworkVersion = Math.incrementExact(
			panelCommunicationNetworkVersion);
		PanelCommunicationNetworkScope scope = new PanelCommunicationNetworkScope(version);
		panelCommunicationNetworkSource = source;
		panelCommunicationNetworkOwner = scope;
		return scope;
	}

	/**
	 * Binds the real lifecycle/router owner before the network source is installed. This production
	 * composition identity is permanent for the runtime lifetime and cannot be replaced by a source
	 * callback.
	 */
	public synchronized PanelProductionComposition beginPanelProductionComposition() {
		requireRunning();
		if (panelCommunicationTopologyAuthority != null || panelCommunicationNetworkSource != null)
			throw new IllegalStateException("Panel production composition is already bound");
		if (panelProductionComposition != null)
			throw new IllegalStateException("Panel production composition is already issued");
		panelProductionComposition = new PanelProductionComposition();
		return panelProductionComposition;
	}

	/** Package-private topology-owner seam for isolated projection tests. */
	synchronized void installPanelCommunicationTopologyAuthorityForTest(
		RadioTopologyPublicationAuthority authority) {
		requireRunning();
		if (panelProductionComposition != null)
			throw new IllegalStateException("production Panel composition was already issued");
		bindPanelCommunicationTopologyAuthority(authority);
	}

	private void bindPanelCommunicationTopologyAuthority(
		RadioTopologyPublicationAuthority authority) {
		if (panelCommunicationTopologyAuthority != null || panelCommunicationNetworkSource != null)
			throw new IllegalStateException("Panel topology authority is already bound");
		panelCommunicationTopologyVersion = Math.incrementExact(panelCommunicationTopologyVersion);
		panelCommunicationTopologyAuthority = Objects.requireNonNull(authority, "topology authority");
	}

	/**
	 * One-time production composition binding. An {@link ExternalListenerRuntime} can only register
	 * constructor-private loaded-Computer capabilities minted by that exact runtime instance.
	 */
	public synchronized PanelListenerBinding installPanelListenerRuntime(
		ExternalListenerRuntime.InstallationCapability installation) {
		requireRunning();
		installation = Objects.requireNonNull(installation, "listener installation");
		if (panelListenerRegistry != null)
			throw new IllegalStateException("Panel listener runtime is already bound");
		long version = panelListenerRegistryVersion = Math.incrementExact(
			panelListenerRegistryVersion);
		panelListenerRegistry = new PanelListenerRegistry(version,
			Objects.requireNonNull(installation.runtime(), "listener runtime"),
			Objects.requireNonNull(installation.service(), "listener service"));
		return new PanelListenerBinding(panelListenerRegistry);
	}

	/** Package-private isolated seam for command-unit tests; production never exposes raw stores. */
	synchronized PanelListenerTestRegistry installPanelListenerTestRegistry(
		ExternalListenerService service) {
		requireRunning();
		if (panelListenerRegistry != null)
			throw new IllegalStateException("Panel listener runtime is already bound");
		long version = panelListenerRegistryVersion = Math.incrementExact(
			panelListenerRegistryVersion);
		panelListenerRegistry = new PanelListenerRegistry(version, null,
			Objects.requireNonNull(service, "listener service"));
		return new PanelListenerTestRegistry(panelListenerRegistry);
	}

	private synchronized void installPanelRoleSnapshot(ClusterEngineState engine,
		ClusterCommunicationConfiguration declaration, long directoryGeneration) {
		requireRunning();
		engine = requireCurrentPanelEngine(engine);
		declaration = Objects.requireNonNull(declaration, "declaration");
		if (!engine.clusterId().equals(declaration.clusterId()) || directoryGeneration < 0)
			throw new IllegalArgumentException("Panel declaration differs from current Cluster");
		PanelRolePublication prior = panelRolePublications.get(engine.clusterId());
		if (prior != null && prior.epochId.equals(engine.epochId())) {
			if (declaration.revision() < prior.declarationRevision
				|| directoryGeneration < prior.directoryGeneration)
				throw new IllegalArgumentException("Panel declaration or directory generation regressed");
			boolean changed = !prior.declaration.equals(declaration)
				|| prior.directoryGeneration != directoryGeneration;
			if (declaration.revision() == prior.declarationRevision
				&& !prior.declaration.equals(declaration))
				throw new IllegalArgumentException("Panel declaration changed without its revision");
			if (changed) {
				taskPublicationAuthority.retireEpoch(engine.clusterId(), engine.epochId());
				communicationPublicationAuthority.retireEpoch(engine.clusterId(), engine.epochId());
				if (prior.role == ClusterServiceRole.AGENT) {
					agentPublicationAuthority.revoke(engine.clusterId(), engine.epochId());
					recordPanelRoleTombstone(engine.clusterId(), engine.epochId(),
						prior.declaration, prior.directoryGeneration);
				}
			}
		}
		PanelRoleTombstone revoked = panelRoleTombstones.get(new PanelEpochKey(
			engine.clusterId(), engine.epochId()));
		if (revoked != null && !coherentlyAdvances(revoked, declaration,
			directoryGeneration))
			throw new IllegalStateException("Panel declaration must advance after its exact publication was revoked");
		panelRolePublications.put(engine.clusterId(), new PanelRolePublication(engine.epochId(),
			declaration, declaration.role(), declaration.revision(), directoryGeneration));
		if (revoked != null) panelRoleTombstones.remove(new PanelEpochKey(engine.clusterId(),
			engine.epochId()));
	}

	private synchronized PanelRolePublication capturePanelRole(ClusterEngineState engine,
		@Nullable ClusterServiceRole expectedRole) {
		PanelCommunicationSnapshotSource source = panelCommunicationSnapshotSource;
		if (source == null)
			throw new IllegalStateException("Panel communication snapshot source is not installed");
		long sourceVersion = panelCommunicationSnapshotVersion;
		Optional<PanelCommunicationSnapshot> captured;
		try {
			captured = source.capture(new PanelCommunicationSnapshotRequest(engine.clusterId(),
				engine.epochId(), engine.generation()));
		} catch (RuntimeException exception) {
			revokeCurrentPanelRole(engine.clusterId(), engine.epochId());
			throw exception;
		}
		if (panelCommunicationSnapshotSource != source
			|| panelCommunicationSnapshotVersion != sourceVersion) {
			revokeCurrentPanelRole(engine.clusterId(), engine.epochId());
			throw new IllegalStateException("Panel communication snapshot source was replaced during capture");
		}
		if (captured == null) {
			revokeCurrentPanelRole(engine.clusterId(), engine.epochId());
			throw new IllegalStateException("Panel communication snapshot source returned null");
		}
		PanelCommunicationSnapshot snapshot = captured.orElse(null);
		if (snapshot == null) {
			revokeCurrentPanelRole(engine.clusterId(), engine.epochId());
			throw new IllegalStateException("Authoritative Panel communication declaration is unavailable");
		}
		if (!snapshot.declaration().clusterId().equals(engine.clusterId())) {
			revokeCurrentPanelRole(engine.clusterId(), engine.epochId());
			throw new IllegalArgumentException("Panel communication snapshot crossed Clusters");
		}
		installPanelRoleSnapshot(engine, snapshot.declaration(), snapshot.directoryGeneration());
		PanelRolePublication role = panelRolePublications.get(engine.clusterId());
		if (role == null || expectedRole != null && role.role != expectedRole
			|| !role.epochId.equals(engine.epochId()))
			throw new IllegalStateException("Authoritative Panel role differs from requested publication");
		return role;
	}

	/** Runtime-owned NORMAL capture. No caller-selectable role label is accepted. */
	public synchronized TaskProjectionSnapshot.PublishedSnapshot publishPanelStandardSnapshot(
		ClusterEngineState engine, Map<UUID, TaskProjectionService.GoalDisplay> displays,
		AgentNewTaskDefaults defaults, Set<CreateRouteKey> localEntrances) {
		requireRunning();
		engine = requireCurrentPanelEngine(engine);
		capturePanelRole(engine, ClusterServiceRole.STANDARD);
		PanelPublicationFence fence = mintPanelFence(engine, ClusterServiceRole.STANDARD);
		TaskProjectionSnapshot.CaptureFacts facts = new TaskProjectionSnapshot.CaptureFacts(engine,
			displays, TaskProjectionSnapshot.standardDefaults(fence, defaults), localEntrances);
		return taskPublicationAuthority.publish(fence, facts);
	}

	/** Missing submission defaults must not hide existing Goals or block their transition policy. */
	public synchronized TaskProjectionSnapshot.PublishedSnapshot publishPanelTasksWithoutDefaults(
		ClusterEngineState engine, Map<UUID, TaskProjectionService.GoalDisplay> displays,
		Set<CreateRouteKey> entrances,
		com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.NewTaskContextProjection.Status reason) {
		requireRunning(); engine = requireCurrentPanelEngine(engine);
		PanelRolePublication role = capturePanelRole(engine, null);
		PanelPublicationFence fence = mintPanelFence(engine, role.role);
		return taskPublicationAuthority.publish(fence, new TaskProjectionSnapshot.CaptureFacts(engine,
			displays, TaskProjectionSnapshot.unavailableDefaults(fence, role.role, reason), entrances));
	}

	/** Returns only the runtime-owned, still-current Task/New Task publication. */
	public synchronized Optional<TaskProjectionSnapshot.PublishedSnapshot>
		capturePanelTaskSnapshot(UUID clusterId) {
		if (closed) return Optional.empty();
		return taskPublicationAuthority.capture(Objects.requireNonNull(clusterId, "cluster id"))
			.filter(TaskProjectionSnapshot.PublishedSnapshot::isCurrentPublication);
	}

	/** Captures and privately publishes all communication facts during this runtime lock hold. */
	public synchronized CommunicationNetworkProjectionService.PublishedSnapshot
		publishPanelCommunicationNetwork(UUID clusterId) {
		requireRunning();
		ClusterEngineState engine = states.get(Objects.requireNonNull(clusterId, "cluster id"));
		if (engine == null) throw new IllegalArgumentException("unknown communication Cluster");
		PanelRolePublication role = capturePanelRole(engine, null);
		PanelCommunicationNetworkSource source = panelCommunicationNetworkSource;
		if (source == null)
			throw new IllegalStateException("Panel communication network source is not installed");
		long sourceVersion = panelCommunicationNetworkVersion;
		RadioTopologyPublicationAuthority topologyAuthority = panelCommunicationTopologyAuthority;
		if (topologyAuthority == null)
			throw new IllegalStateException("Panel topology authority is not bound");
		long topologyVersion = panelCommunicationTopologyVersion;
		CommunicationNetworkProjectionService.CaptureFacts facts;
		RadioTopologyPublicationAuthority.Publication topologyPublication;
		try {
			topologyPublication = topologyAuthority.capture();
			facts = source.capture(new PanelCommunicationNetworkRequest(engine.clusterId(),
				engine.epochId(), engine.generation(), role.declaration,
				role.directoryGeneration, topologyPublication)).orElseThrow(() -> new IllegalStateException(
					"authoritative communication network capture is unavailable"));
		} catch (RuntimeException exception) {
			communicationPublicationAuthority.retireEpoch(engine.clusterId(), engine.epochId());
			throw exception;
		}
		if (panelCommunicationNetworkSource != source
			|| panelCommunicationNetworkVersion != sourceVersion
			|| panelCommunicationTopologyAuthority != topologyAuthority
			|| panelCommunicationTopologyVersion != topologyVersion) {
			communicationPublicationAuthority.retireEpoch(engine.clusterId(), engine.epochId());
			throw new IllegalStateException("communication publication owner changed during capture");
		}
		if (facts.topologyPublication() != topologyPublication
			|| !topologyAuthority.isCurrent(topologyPublication)) {
			communicationPublicationAuthority.retireEpoch(engine.clusterId(), engine.epochId());
			throw new IllegalArgumentException("communication capture lacks current topology authority");
		}
		if (!facts.localEngine().equals(engine)
			|| facts.directoryGeneration() != role.directoryGeneration
			|| !facts.clusters().get(engine.clusterId()).configuration().equals(role.declaration)) {
			communicationPublicationAuthority.retireEpoch(engine.clusterId(), engine.epochId());
			throw new IllegalArgumentException("communication capture differs from runtime declaration");
		}
		List<PanelListenerCapture> localListeners = capturePanelListeners(engine);
		PanelPublicationFence fence = mintPanelFence(engine, role.role);
		return communicationPublicationAuthority.publish(fence, facts, localListeners);
	}

	private List<PanelListenerCapture> capturePanelListeners(ClusterEngineState engine) {
		PanelListenerRegistry registry = panelListenerRegistry;
		if (registry == null || !registry.current()) return List.of();
		ArrayList<PanelListenerCapture> captures = new ArrayList<>();
		for (RegisteredPanelListener registered : registry.entries.values()) {
			if (!registered.clusterId.equals(engine.clusterId())
				|| !registered.epochId.equals(engine.epochId())) continue;
			if (!registered.computer.listenerPersistenceAuthorized()
				|| registered.computer.profile().profession()
					!= com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession
						.WANDERING_TRADER
				|| engine.members().stream().noneMatch(member -> member.loaded()
					&& member.computerId().equals(registered.computer.id())
					&& member.profile().equals(registered.computer.profile()))) continue;
			captures.add(new PanelListenerCapture(this, registry.version, registered.clusterId,
				registered.epochId, registered.computer.listenerOwner(),
				registered.computer.configuration(), registered.computer));
		}
		return List.copyOf(captures);
	}

	private synchronized ExternalListenerService.EditResult replaceCommunicationListener(
		PanelPublicationFence fence,
		CommunicationNetworkProjectionService.PublishedSnapshot publication,
		PanelListenerCapture capture,
		long expectedConfigurationRevision, List<CommunicationFrequency> frequencies) {
		if (closed || !publication.isCurrentPublication())
			return ExternalListenerService.EditResult.STALE;
		PanelListenerRegistry registry = panelListenerRegistry;
		RegisteredPanelListener registered = registry == null ? null : registry.entries.get(
			capture.owner());
		ClusterEngineState engine = states.get(publication.localClusterId());
		if (registry == null || !registry.current() || registered == null || engine == null
			|| !fence.isCurrent(engine,
				publication.clusters().get(publication.localClusterId()).configuration().role())
			|| !capture.matches(this, registry, registered)
			|| engine.members().stream().noneMatch(member -> member.loaded()
				&& member.computerId().equals(capture.owner().computerId())
				&& member.profile().profession()
					== com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession
						.WANDERING_TRADER))
			return ExternalListenerService.EditResult.STALE;
		if (!publication.isCurrentPublication()) return ExternalListenerService.EditResult.STALE;
		if (panelListenerCasInProgress)
			throw new IllegalStateException("nested listener configuration CAS");
		ExternalListenerService.EditResult result;
		panelListenerCasInProgress = true;
		try {
			result = registry.service.replace(registered.computer,
				expectedConfigurationRevision, frequencies);
		} finally {
			panelListenerCasInProgress = false;
		}
		RegisteredPanelListener after = registry.entries.get(capture.owner());
		if (!registry.current() || after != registered || !capture.matchesIdentity(this, registry,
			after) || !publication.isCurrentOwnerPublication())
			return ExternalListenerService.EditResult.STALE;
		if (result.applied()) communicationPublicationAuthority.retireEpoch(
			publication.localClusterId(), publication.localEpochId());
		return result;
	}

	public synchronized Optional<CommunicationNetworkProjectionService.PublishedSnapshot>
		capturePanelCommunicationNetwork(UUID clusterId) {
		if (closed) return Optional.empty();
		return communicationPublicationAuthority.capture(Objects.requireNonNull(clusterId,
			"cluster id")).filter(CommunicationNetworkProjectionService.PublishedSnapshot::isCurrentPublication);
	}

	/**
	 * Captures formed Agent facts under a runtime-minted exact role/directory proof. The returned
	 * token is deliberately opaque: a delayed capture cannot be republished after declaration,
	 * directory, epoch, or runtime replacement.
	 */
	public synchronized PanelAgentFormationCapture capturePanelAgentFormation(ClusterEngineState engine,
		AgentFormation formation, AgentServiceIdentity declared,
		Map<UUID, String> aliases, int normalPriority) {
		requireRunning();
		engine = requireCurrentPanelEngine(engine);
		PanelRolePublication role = capturePanelRole(engine, ClusterServiceRole.AGENT);
		long directoryGeneration = role.directoryGeneration;
		PanelPublicationFence fence = mintPanelFence(engine, ClusterServiceRole.AGENT);
		if (!currentPanelAgentCapture(fence, engine, directoryGeneration))
			throw new IllegalStateException("Agent formation capture must advance after revocation");
		fence.requireAgentFormation(engine.epochId(), directoryGeneration, formation, declared);
		return new PanelAgentFormationCapture(this, fence, engine, directoryGeneration, formation,
			declared, aliases, normalPriority);
	}

	/** Publishes only an exact, still-current Agent formation capture minted by this runtime. */
	public synchronized FormationPublication publishPanelAgentFormation(PanelAgentFormationCapture capture) {
		requireRunning();
		capture = Objects.requireNonNull(capture, "capture");
		capture.requireCurrent(this);
		return agentPublicationAuthority.publish(capture.fence, capture.engine.epochId(),
			capture.directoryGeneration, capture.formation, capture.declared, capture.aliases,
			capture.normalPriority);
	}
	/** Runtime-owned AGENT capture that always carries the exact current formation publication. */
	public synchronized TaskProjectionSnapshot.PublishedSnapshot publishPanelAgentSnapshot(
		ClusterEngineState engine, Map<UUID, TaskProjectionService.GoalDisplay> displays,
		FormationPublication publication, Optional<CreateRouteKey> explicitOverride,
		Set<CreateRouteKey> localEntrances) {
		requireRunning();
		engine = requireCurrentPanelEngine(engine);
		capturePanelRole(engine, ClusterServiceRole.AGENT);
		PanelPublicationFence fence = mintPanelFence(engine, ClusterServiceRole.AGENT);
		AgentNewTaskDefaults defaults = AgentNewTaskDefaults.resolve(publication, explicitOverride);
		TaskProjectionSnapshot.CaptureFacts facts = new TaskProjectionSnapshot.CaptureFacts(engine,
			displays, TaskProjectionSnapshot.agentDefaults(fence, defaults, publication), localEntrances);
		return taskPublicationAuthority.publish(fence, facts);
	}

	/** Exact-epoch lifecycle callbacks for declaration revoke, unformation, and Cluster unload. */
	public synchronized boolean revokePanelAgentFormation(UUID clusterId, UUID epochId) {
		if (closed) return false;
		ClusterEngineState current = states.get(Objects.requireNonNull(clusterId, "clusterId"));
		if (current == null || !current.epochId().equals(Objects.requireNonNull(epochId, "epochId")))
			return false;
		PanelRolePublication role = panelRolePublications.get(clusterId);
		if (role != null && role.epochId.equals(epochId))
			recordPanelRoleTombstone(clusterId, epochId, role.declaration,
				role.directoryGeneration);
		taskPublicationAuthority.retireEpoch(clusterId, epochId);
		communicationPublicationAuthority.retireEpoch(clusterId, epochId);
		agentPublicationAuthority.revoke(clusterId, epochId); return true;
	}
	public synchronized boolean revokePanelDeclaration(UUID clusterId, UUID epochId) {
		if (!revokePanelAgentFormation(clusterId, epochId)) return false;
		panelRolePublications.remove(clusterId); taskPublicationAuthority.retireEpoch(clusterId, epochId);
		return true;
	}
	public synchronized boolean revokePanelCluster(UUID clusterId, UUID epochId) {
		if (!revokePanelAgentFormation(clusterId, epochId)) return false;
		taskPublicationAuthority.retireEpoch(clusterId, epochId); panelRolePublications.remove(clusterId);
		return true;
	}
	public synchronized PanelPublicationCounts panelPublicationCountsForTest() {
		return new PanelPublicationCounts(taskPublicationAuthority.publicationCountForTest(),
			agentPublicationAuthority.publicationCountForTest());
	}
	public record PanelPublicationCounts(int taskPublications, int agentPublications) {}

	private ClusterEngineState requireCurrentPanelEngine(ClusterEngineState candidate) {
		candidate = Objects.requireNonNull(candidate, "engine");
		ClusterEngineState current = states.get(candidate.clusterId());
		if (current == null || !current.equals(candidate))
			throw new IllegalArgumentException("Panel capture is not the current Cluster engine snapshot");
		return current;
	}
	private void recordPanelRoleTombstone(UUID clusterId, UUID epochId,
		ClusterCommunicationConfiguration declaration, long directoryGeneration) {
		PanelEpochKey key = new PanelEpochKey(clusterId, epochId);
		PanelRoleTombstone next = new PanelRoleTombstone(declaration, directoryGeneration);
		PanelRoleTombstone previous = panelRoleTombstones.get(key);
		if (previous == null || coherentlyAdvances(previous, declaration, directoryGeneration))
			panelRoleTombstones.put(key, next);
	}
	private static boolean coherentlyAdvances(PanelRoleTombstone revoked,
		ClusterCommunicationConfiguration declaration, long directoryGeneration) {
		long declarationRevision = declaration.revision();
		long revokedDeclarationRevision = revoked.declaration.revision();
		return declarationRevision >= revokedDeclarationRevision
			&& directoryGeneration >= revoked.directoryGeneration
			&& (declarationRevision != revokedDeclarationRevision
				|| declaration.equals(revoked.declaration))
			&& (declarationRevision > revokedDeclarationRevision
				|| directoryGeneration > revoked.directoryGeneration);
	}
	private void removePanelEpochFences(UUID clusterId) {
		panelRoleTombstones.keySet().removeIf(key -> key.clusterId.equals(clusterId));
	}
	private void revokeCurrentPanelRole(UUID clusterId, UUID epochId) {
		PanelRolePublication role = panelRolePublications.get(clusterId);
		if (role != null && role.epochId.equals(epochId)) {
			recordPanelRoleTombstone(clusterId, epochId, role.declaration,
				role.directoryGeneration);
			panelRolePublications.remove(clusterId);
		}
		taskPublicationAuthority.retireEpoch(clusterId, epochId);
		communicationPublicationAuthority.retireEpoch(clusterId, epochId);
		agentPublicationAuthority.revoke(clusterId, epochId);
	}
	private void retireAllPanelRolePublications() {
		for (Map.Entry<UUID, PanelRolePublication> entry :
			List.copyOf(panelRolePublications.entrySet())) {
			PanelRolePublication role = entry.getValue();
			recordPanelRoleTombstone(entry.getKey(), role.epochId, role.declaration,
				role.directoryGeneration);
			taskPublicationAuthority.retireEpoch(entry.getKey(), role.epochId);
			communicationPublicationAuthority.retireEpoch(entry.getKey(), role.epochId);
			agentPublicationAuthority.revoke(entry.getKey(), role.epochId);
		}
		panelRolePublications.clear();
	}
	private void retireAllCommunicationPublications() {
		communicationPublicationAuthority.retireAll();
	}
	private PanelPublicationFence mintPanelFence(ClusterEngineState engine,
		ClusterServiceRole expectedRole) {
		PanelRolePublication role = panelRolePublications.get(engine.clusterId());
		if (role == null || role.role != expectedRole || !role.epochId.equals(engine.epochId()))
			throw new IllegalStateException("Panel role proof is not current for this Cluster epoch");
		return new PanelPublicationFence(this, engine.clusterId(), engine.epochId(),
			engine.generation(), expectedRole, role.declarationRevision, role.directoryGeneration);
	}
	private synchronized boolean currentPanelFence(PanelPublicationFence fence,
		ClusterEngineState candidate, ClusterServiceRole expectedRole) {
		if (candidate == null || closed || fence.runtime != this || fence.role != expectedRole
			|| !fence.clusterId.equals(candidate.clusterId()) || !fence.epochId.equals(candidate.epochId()))
			return false;
		ClusterEngineState current = states.get(fence.clusterId);
		if (current == null || !current.equals(candidate) || current.generation() != fence.generation)
			return false;
		PanelRolePublication role = panelRolePublications.get(fence.clusterId);
		return role != null && role.role == expectedRole && role.epochId.equals(fence.epochId)
			&& role.declarationRevision == fence.declarationRevision
			&& role.directoryGeneration == fence.directoryGeneration;
	}
	private synchronized boolean currentPanelAgentFence(PanelPublicationFence fence,
		UUID publishedEpoch, long publishedDirectoryGeneration) {
		return fence.role == ClusterServiceRole.AGENT && fence.epochId.equals(publishedEpoch)
			&& fence.directoryGeneration == publishedDirectoryGeneration
			&& currentPanelFence(fence, states.get(fence.clusterId), ClusterServiceRole.AGENT);
	}
	/** Same-lock check used by both raw-capture and capture-token publication. */
	private synchronized boolean currentPanelAgentCapture(PanelPublicationFence fence,
		ClusterEngineState engine, long directoryGeneration) {
		if (fence.directoryGeneration != directoryGeneration
			|| !currentPanelFence(fence, engine, ClusterServiceRole.AGENT)) return false;
		PanelRoleTombstone revoked = panelRoleTombstones.get(new PanelEpochKey(
			fence.clusterId, fence.epochId));
		PanelRolePublication role = panelRolePublications.get(fence.clusterId);
		return revoked == null || coherentlyAdvances(revoked, role.declaration,
			directoryGeneration);
	}
	/** Opaque raw-Agent capture bound to one current runtime role/directory publication. */
	public static final class PanelAgentFormationCapture {
		private final FactoryRuntimeService runtime;
		private final PanelPublicationFence fence;
		private final ClusterEngineState engine;
		private final long directoryGeneration;
		private final AgentFormation formation;
		private final AgentServiceIdentity declared;
		private final Map<UUID, String> aliases;
		private final int normalPriority;
		private PanelAgentFormationCapture(FactoryRuntimeService runtime, PanelPublicationFence fence,
			ClusterEngineState engine, long directoryGeneration, AgentFormation formation,
			AgentServiceIdentity declared, Map<UUID, String> aliases, int normalPriority) {
			this.runtime = runtime;
			this.fence = fence;
			this.engine = engine;
			this.directoryGeneration = directoryGeneration;
			this.formation = formation;
			this.declared = declared;
			this.aliases = Map.copyOf(Objects.requireNonNull(aliases, "aliases"));
			this.normalPriority = normalPriority;
		}
		private void requireCurrent(FactoryRuntimeService expectedRuntime) {
			if (runtime != expectedRuntime || !runtime.currentPanelAgentCapture(fence, engine,
				directoryGeneration))
				throw new IllegalStateException("Agent formation capture is stale");
		}
	}
	/** Runtime-minted, constructor-private proof accepted by the two panel authorities only. */
	public static final class PanelPublicationFence {
		private final FactoryRuntimeService runtime;
		private final UUID clusterId, epochId;
		private final long generation, declarationRevision, directoryGeneration;
		private final ClusterServiceRole role;
		private PanelPublicationFence(FactoryRuntimeService runtime, UUID clusterId, UUID epochId,
			long generation, ClusterServiceRole role, long declarationRevision, long directoryGeneration) {
			this.runtime = runtime; this.clusterId = clusterId; this.epochId = epochId;
			this.generation = generation; this.role = role;
			this.declarationRevision = declarationRevision; this.directoryGeneration = directoryGeneration;
		}
		public boolean isCurrent(ClusterEngineState engine, ClusterServiceRole expectedRole) {
			return runtime.currentPanelFence(this, Objects.requireNonNull(engine, "engine"), expectedRole);
		}
		public boolean isCurrentAgentPublication(UUID publishedEpoch,
			long publishedDirectoryGeneration) {
			return runtime.currentPanelAgentFence(this, publishedEpoch, publishedDirectoryGeneration);
		}
		public long declarationRevision() { return declarationRevision; }
		public boolean sameRuntime(PanelPublicationFence other) {
			return runtime == Objects.requireNonNull(other, "other").runtime;
		}
		public boolean samePublicationFence(PanelPublicationFence other) {
			other = Objects.requireNonNull(other, "other");
			return runtime == other.runtime && clusterId.equals(other.clusterId)
				&& epochId.equals(other.epochId) && generation == other.generation
				&& role == other.role && declarationRevision == other.declarationRevision
				&& directoryGeneration == other.directoryGeneration;
		}
		public void requireTaskFacts(TaskProjectionSnapshot.CaptureFacts facts) {
			if (!isCurrent(facts.engine(), role))
				throw new IllegalArgumentException("Task publication fence is stale");
		}
		public void requireCommunicationFacts(
			CommunicationNetworkProjectionService.CaptureFacts facts) {
			Objects.requireNonNull(facts, "facts");
			var local = facts.clusters().get(clusterId);
			if (local == null || facts.directoryGeneration() != directoryGeneration
				|| local.configuration().revision() != declarationRevision
				|| local.configuration().role() != role
				|| !isCurrent(facts.localEngine(), role))
				throw new IllegalArgumentException("communication publication fence is stale");
		}
		public ExternalListenerService.EditResult replaceCommunicationListener(
			CommunicationNetworkProjectionService.PublishedSnapshot publication,
			PanelListenerCapture capture, long expectedConfigurationRevision,
			List<CommunicationFrequency> frequencies) {
			return runtime.replaceCommunicationListener(this,
				Objects.requireNonNull(publication, "publication"),
				Objects.requireNonNull(capture, "listener capability"),
				expectedConfigurationRevision, List.copyOf(frequencies));
		}
		public void requireAgentFormation(UUID publishedEpoch, long publishedDirectoryGeneration,
			AgentFormation formation, AgentServiceIdentity declared) {
			if (role != ClusterServiceRole.AGENT || !epochId.equals(publishedEpoch)
				|| directoryGeneration != publishedDirectoryGeneration || formation == null || declared == null
				|| !clusterId.equals(formation.ownerClusterId())
				|| !formation.agentId().equals(declared.agentId())
				|| formation.agentGeneration() != declared.generation()
				|| !runtime.currentPanelAgentFence(this, publishedEpoch, publishedDirectoryGeneration))
				throw new IllegalArgumentException("Agent formation publication fence is stale");
		}
	}
	private record PanelRolePublication(UUID epochId, ClusterCommunicationConfiguration declaration,
		ClusterServiceRole role, long declarationRevision, long directoryGeneration) {}
	private record PanelEpochKey(UUID clusterId, UUID epochId) {}
	private record PanelRoleTombstone(ClusterCommunicationConfiguration declaration,
		long directoryGeneration) {}

	public record PanelCommunicationSnapshotRequest(UUID clusterId, UUID epochId,
		long engineGeneration) {
		public PanelCommunicationSnapshotRequest {
			clusterId = Objects.requireNonNull(clusterId, "clusterId");
			epochId = Objects.requireNonNull(epochId, "epochId");
			if (engineGeneration < 0) throw new IllegalArgumentException("negative engine generation");
		}
	}
	public record PanelCommunicationSnapshot(ClusterCommunicationConfiguration declaration,
		long directoryGeneration) {
		public PanelCommunicationSnapshot {
			declaration = Objects.requireNonNull(declaration, "declaration");
			if (directoryGeneration < 0)
				throw new IllegalArgumentException("negative Agent directory generation");
		}
	}
	@FunctionalInterface
	public interface PanelCommunicationSnapshotSource {
		Optional<PanelCommunicationSnapshot> capture(PanelCommunicationSnapshotRequest request);
	}
	public record PanelCommunicationNetworkRequest(UUID clusterId, UUID epochId,
		long engineGeneration, ClusterCommunicationConfiguration declaration,
		long directoryGeneration,
		RadioTopologyPublicationAuthority.Publication topologyPublication) {
		public PanelCommunicationNetworkRequest {
			clusterId = Objects.requireNonNull(clusterId, "cluster id");
			epochId = Objects.requireNonNull(epochId, "epoch id");
			declaration = Objects.requireNonNull(declaration, "declaration");
			topologyPublication = Objects.requireNonNull(topologyPublication,
				"topology publication");
			if (engineGeneration < 0 || directoryGeneration < 0
				|| !clusterId.equals(declaration.clusterId()))
				throw new IllegalArgumentException("invalid communication network request");
		}
	}
	@FunctionalInterface
	public interface PanelCommunicationNetworkSource {
		Optional<CommunicationNetworkProjectionService.CaptureFacts> capture(
			PanelCommunicationNetworkRequest request);
	}
	private final class PanelCommunicationSnapshotScope implements AutoCloseable {
		private final long version;
		private boolean closed;
		private PanelCommunicationSnapshotScope(long version) { this.version = version; }
		@Override public void close() {
			synchronized (FactoryRuntimeService.this) {
				if (closed) return;
				closed = true;
				if (panelCommunicationSnapshotOwner != this
					|| panelCommunicationSnapshotVersion != version) return;
				retireAllPanelRolePublications();
				panelCommunicationSnapshotSource = null;
				panelCommunicationSnapshotOwner = null;
			}
		}
	}
	private final class PanelCommunicationNetworkScope implements AutoCloseable {
		private final long version;
		private boolean closed;
		private PanelCommunicationNetworkScope(long version) { this.version = version; }
		@Override public void close() {
			synchronized (FactoryRuntimeService.this) {
				if (closed) return;
				closed = true;
				if (panelCommunicationNetworkOwner != this
					|| panelCommunicationNetworkVersion != version) return;
				retireAllCommunicationPublications();
				panelCommunicationNetworkSource = null;
				panelCommunicationNetworkOwner = null;
			}
		}
	}
	/** Opaque one-shot handle retained only by the real server composition root. */
	public final class PanelProductionComposition implements AutoCloseable {
		private PanelProductionComposition() {}
		public void bindTopology(RadioTopologyPublicationAuthority authority) {
			synchronized (FactoryRuntimeService.this) {
				requireRunning();
				if (panelProductionComposition != this)
					throw new IllegalStateException("Panel production composition is stale");
				bindPanelCommunicationTopologyAuthority(authority);
			}
		}
		@Override public void close() {
			synchronized (FactoryRuntimeService.this) {
				if (panelProductionComposition != this) return;
				if (panelListenerRegistry != null) panelListenerRegistry.closeInternal();
				panelCommunicationTopologyAuthority = null;
				panelProductionComposition = null;
				retireAllCommunicationPublications();
			}
		}
	}

	/** Constructor-private exact loaded-instance capability; only the runtime can mint it. */
	public static final class PanelListenerCapture {
		private final FactoryRuntimeService runtime;
		private final long registryVersion;
		private final UUID clusterId;
		private final UUID epochId;
		private final ExternalListenerService.ListenerOwner owner;
		private final com.nobodiiiii.createbiotech.content.factorycluster.communication
			.ExternalListenerConfiguration configuration;
		private final ExternalListenerService.ListenerComputer exactComputer;
		private PanelListenerCapture(FactoryRuntimeService runtime, long registryVersion,
			UUID clusterId, UUID epochId, ExternalListenerService.ListenerOwner owner,
			com.nobodiiiii.createbiotech.content.factorycluster.communication
				.ExternalListenerConfiguration configuration,
			ExternalListenerService.ListenerComputer exactComputer) {
			this.runtime = runtime;
			this.registryVersion = registryVersion;
			this.clusterId = clusterId;
			this.epochId = epochId;
			this.owner = owner;
			this.configuration = configuration;
			this.exactComputer = exactComputer;
		}
		public ExternalListenerService.ListenerOwner owner() { return owner; }
		public com.nobodiiiii.createbiotech.content.factorycluster.communication
			.ExternalListenerConfiguration configuration() { return configuration; }
		@Override public boolean equals(Object candidate) {
			return candidate instanceof PanelListenerCapture other
				&& runtime == other.runtime && registryVersion == other.registryVersion
				&& clusterId.equals(other.clusterId) && epochId.equals(other.epochId)
				&& owner.equals(other.owner) && configuration.equals(other.configuration)
				&& exactComputer == other.exactComputer;
		}
		@Override public int hashCode() {
			return Objects.hash(System.identityHashCode(runtime), registryVersion, clusterId,
				epochId, owner, configuration, System.identityHashCode(exactComputer));
		}
		private boolean matches(FactoryRuntimeService expectedRuntime,
			PanelListenerRegistry registry, RegisteredPanelListener registered) {
			return matchesIdentity(expectedRuntime, registry, registered)
				&& exactComputer.configuration().equals(configuration);
		}
		private boolean matchesIdentity(FactoryRuntimeService expectedRuntime,
			PanelListenerRegistry registry, RegisteredPanelListener registered) {
			return runtime == expectedRuntime && registry != null
				&& registry.version == registryVersion && registry.current()
				&& registered != null && registered.clusterId.equals(clusterId)
				&& registered.epochId.equals(epochId) && registered.computer == exactComputer
				&& exactComputer.listenerOwner().equals(owner);
		}
	}

	/** Production handle: useful only with a capability minted by its exact listener runtime. */
	public final class PanelListenerBinding {
		private final PanelListenerRegistry registry;
		private PanelListenerBinding(PanelListenerRegistry registry) { this.registry = registry; }
		public boolean accepts(ExternalListenerRuntime.LoadedComputerCapability capability) {
			synchronized (FactoryRuntimeService.this) {
				capability = requireProductionCapability(registry, capability, true);
				return registry.accepts(capability.clusterId(), capability.epochId(),
					capability.computer());
			}
		}
		public void observe(ExternalListenerRuntime.LoadedComputerCapability capability) {
			synchronized (FactoryRuntimeService.this) {
				capability = requireProductionCapability(registry, capability, true);
				registry.observe(capability.clusterId(), capability.epochId(), capability.computer());
			}
		}
		public void retire(ExternalListenerRuntime.LoadedComputerCapability capability) {
			synchronized (FactoryRuntimeService.this) {
				capability = requireProductionCapability(registry, capability, false);
				RegisteredPanelListener registered = registry.entries.get(capability.owner());
				if (registered != null && registered.computer != capability.computer())
					throw new IllegalArgumentException("listener capability differs from registered instance");
				registry.retire(capability.owner());
			}
		}
		public void configurationChanged(
			ExternalListenerRuntime.LoadedComputerCapability capability) {
			synchronized (FactoryRuntimeService.this) {
				capability = requireProductionCapability(registry, capability, true);
				registry.configurationChanged(capability.owner());
			}
		}
	}

	private ExternalListenerRuntime.LoadedComputerCapability requireProductionCapability(
		PanelListenerRegistry registry,
		ExternalListenerRuntime.LoadedComputerCapability capability, boolean requireCurrentComputer) {
		capability = Objects.requireNonNull(capability, "loaded listener capability");
		if (!registry.current() || registry.productionOwner == null
			|| !capability.belongsTo(registry.productionOwner)
			|| requireCurrentComputer && !capability.matchesCurrentComputer())
			throw new IllegalArgumentException("listener capability is not owned by this runtime");
		return capability;
	}

	/** Package-private adapter used only by the separately compiled test lifecycle harness. */
	final class PanelListenerTestRegistry {
		private final PanelListenerRegistry registry;
		private PanelListenerTestRegistry(PanelListenerRegistry registry) { this.registry = registry; }
		void observe(UUID clusterId, UUID epochId,
			ExternalListenerService.ListenerComputer computer) {
			synchronized (FactoryRuntimeService.this) {
				if (registry.productionOwner != null)
					throw new IllegalStateException("production listener registry rejects raw stores");
				registry.observe(clusterId, epochId, computer);
			}
		}
		void retire(ExternalListenerService.ListenerOwner owner) {
			synchronized (FactoryRuntimeService.this) { registry.retire(owner); }
		}
		void configurationChanged(ExternalListenerService.ListenerOwner owner) {
			synchronized (FactoryRuntimeService.this) { registry.configurationChanged(owner); }
		}
	}

	/** Private exact-instance registry used by Panel listener edit capabilities. */
	private final class PanelListenerRegistry {
		private final long version;
		@Nullable private final ExternalListenerRuntime productionOwner;
		private final ExternalListenerService service;
		private final LinkedHashMap<ExternalListenerService.ListenerOwner, RegisteredPanelListener>
			entries = new LinkedHashMap<>();
		private boolean closed;
		private PanelListenerRegistry(long version, @Nullable ExternalListenerRuntime productionOwner,
			ExternalListenerService service) {
			this.version = version;
			this.productionOwner = productionOwner;
			this.service = service;
		}
		private void observe(UUID clusterId, UUID epochId,
			ExternalListenerService.ListenerComputer computer) {
			if (!current()) throw new IllegalStateException("listener registry is stale");
			clusterId = Objects.requireNonNull(clusterId, "cluster id");
			epochId = Objects.requireNonNull(epochId, "epoch id");
			ExternalListenerService.ListenerComputer exactComputer = Objects.requireNonNull(computer,
				"listener computer");
			RegisteredPanelListener existing = entries.get(exactComputer.listenerOwner());
			if (panelListenerCasInProgress) {
				if (existing != null && existing.clusterId.equals(clusterId)
					&& existing.epochId.equals(epochId) && existing.computer == exactComputer) return;
				throw new IllegalStateException("listener lifecycle is locked by a configuration CAS");
			}
			if (!accepts(clusterId, epochId, exactComputer))
				throw new IllegalArgumentException("listener instance is not a loaded local member");
			entries.entrySet().removeIf(entry -> entry.getKey().computerId().equals(exactComputer.id())
				&& !entry.getKey().equals(exactComputer.listenerOwner()));
			entries.put(exactComputer.listenerOwner(), new RegisteredPanelListener(clusterId, epochId,
				exactComputer));
			retireAllCommunicationPublications();
		}
		private boolean accepts(UUID clusterId, UUID epochId,
			ExternalListenerService.ListenerComputer computer) {
			ClusterEngineState engine = states.get(clusterId);
			return engine != null && engine.epochId().equals(epochId)
				&& computer.listenerOwner().epochId().equals(epochId)
				&& computer.listenerPersistenceAuthorized()
				&& computer.profile().profession()
					== com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession
						.WANDERING_TRADER
				&& engine.members().stream().anyMatch(member -> member.loaded()
					&& member.computerId().equals(computer.id())
					&& member.profile().equals(computer.profile()));
		}
		private void retire(ExternalListenerService.ListenerOwner owner) {
			if (!current()) return;
			requireListenerLifecycleUnlocked();
			if (entries.remove(Objects.requireNonNull(owner, "listener owner")) != null)
				retireAllCommunicationPublications();
		}
		private void configurationChanged(ExternalListenerService.ListenerOwner owner) {
			if (!current()) return;
			requireListenerLifecycleUnlocked();
			if (entries.containsKey(Objects.requireNonNull(owner, "listener owner")))
				retireAllCommunicationPublications();
		}
		private boolean current() {
			return !closed && panelListenerRegistry == this
				&& panelListenerRegistryVersion == version;
		}
		private void closeInternal() {
			if (closed) return;
			closed = true;
			entries.clear();
			if (panelListenerRegistry == this) panelListenerRegistry = null;
			retireAllCommunicationPublications();
		}
	}
	private void requireListenerLifecycleUnlocked() {
		if (panelListenerCasInProgress)
			throw new IllegalStateException("listener lifecycle is locked by a configuration CAS");
	}
	private record RegisteredPanelListener(UUID clusterId, UUID epochId,
		ExternalListenerService.ListenerComputer computer) {}
	public synchronized void start() {
		if (closed) throw new IllegalStateException("runtime service is closed");
		if (!started) { worker.start(); started = true; }
	}

	public synchronized boolean publishFact(WorldFact fact) {
		requireRunning(); Objects.requireNonNull(fact, "fact");
		pumpResults();
		ClusterEngineState current = states.get(fact.clusterId());
		if (current == null) states.put(fact.clusterId(), ClusterEngineState.empty(fact.clusterId(), fact.epochId()));
		else if (!current.epochId().equals(fact.epochId())) {
			if (fact instanceof WorldFact.ClusterEpochFact) replaceEpoch(fact.clusterId(), fact.epochId());
			else return false;
		}
		if (fact instanceof WorldFact.IntentResult confirmation) {
			ArrayBlockingQueue<WorldFact.IntentResult> evidence = confirmations.computeIfAbsent(fact.clusterId(),
				ignored -> new ArrayBlockingQueue<>(ClusterEngineState.MAX_PENDING_INTENTS));
			if (!evidence.offer(confirmation)) return false;
			dispatch(fact.clusterId()); return true;
		}
		ArrayBlockingQueue<WorldFact> mailbox = facts.computeIfAbsent(fact.clusterId(),
			ignored -> new ArrayBlockingQueue<>(config.pendingRunCapacity()));
		if (fact instanceof WorldFact.MemberLoadState member) {
			ClusterEngineState.MemberSnapshot installed = states.get(fact.clusterId()).member(member.computerId());
			if (sameMemberSnapshot(installed, member)) { dispatch(fact.clusterId()); return true; }
			mailbox.removeIf(queued -> queued instanceof WorldFact.MemberLoadState prior
				&& prior.computerId().equals(member.computerId()));
		}
		if (fact instanceof WorldFact.PlayerCommand next
			&& next.command() instanceof WorldFact.Command.CreditActualStock credit) {
			WorldFact.PlayerCommand queuedCredit = mailbox.stream()
				.filter(WorldFact.PlayerCommand.class::isInstance)
				.map(WorldFact.PlayerCommand.class::cast)
				.filter(queued -> queued.command()
					instanceof WorldFact.Command.CreditActualStock prior
						&& prior.goalId().equals(credit.goalId()))
				.findFirst().orElse(null);
			if (queuedCredit != null) {
				long queuedCount = ((WorldFact.Command.CreditActualStock)
					queuedCredit.command()).actualCount();
				if (queuedCount >= credit.actualCount()) {
					dispatch(fact.clusterId());
					return true;
				}
				mailbox.remove(queuedCredit);
			}
		}
		if (!mailbox.offer(fact)) return false;
		dispatch(fact.clusterId());
		return true;
	}

	public synchronized void publishLibraryCatalog(ProductionRecipeCatalog catalog) {
		requireRunning(); catalog = Objects.requireNonNull(catalog);
		ProductionRecipeCatalog prior = libraryCatalogs.get(catalog.libraryId());
		if (prior == null || catalog.formationGeneration() > prior.formationGeneration()
			|| catalog.formationGeneration() == prior.formationGeneration()
			&& catalog.catalogGeneration() >= prior.catalogGeneration())
			libraryCatalogs.put(catalog.libraryId(), catalog);
	}

	/** Publishes the Cluster/logistics bindings that may select this Library for production. */
	public synchronized void publishProductionLibraryAccess(UUID clusterId,
		Set<UUID> logisticsIds, long bindingRevision, ProductionRecipeCatalog catalog) {
		SpaceAddress address = catalog.patterns().stream().findFirst()
			.map(pattern -> new SpaceAddress(pattern.source().shelf().dimension(),
				pattern.source().shelf().subLevelId(), pattern.source().shelf().localPos()))
			.orElse(new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO));
		publishProductionLibraryAccess(clusterId, logisticsIds, bindingRevision,
			CommunicationFrequency.UNCONFIGURED, 0,
			address, LibraryRegistration.Reachability.REACHABLE, catalog);
	}

	/** Publishes the complete bounded directory entry used by production priority waves. */
	public synchronized void publishProductionLibraryAccess(UUID clusterId,
		Set<UUID> logisticsIds, long bindingRevision, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency, int libraryPriority,
		SpaceAddress address, LibraryRegistration.Reachability reachability,
		ProductionRecipeCatalog catalog) {
		requireRunning(); Objects.requireNonNull(clusterId, "clusterId");
		frequency = Objects.requireNonNull(frequency, "frequency");
		logisticsIds = Set.copyOf(Objects.requireNonNull(logisticsIds, "logisticsIds"));
		if (bindingRevision < 0)
			throw new IllegalArgumentException("invalid production Library access");
		catalog = Objects.requireNonNull(catalog, "catalog");
		ProductionRecipeCatalog catalogBefore = libraryCatalogs.get(catalog.libraryId());
		publishLibraryCatalog(catalog);
		boolean catalogChanged = !Objects.equals(catalogBefore,
			libraryCatalogs.get(catalog.libraryId()));
		ProductionLibraryAccess prior = productionLibraryAccess.get(catalog.libraryId());
		ProductionLibraryAccess next = new ProductionLibraryAccess(clusterId, logisticsIds,
			bindingRevision, frequency, libraryPriority, Objects.requireNonNull(address, "address"),
			Objects.requireNonNull(reachability, "reachability"));
		boolean accessChanged = false;
		if (prior == null || bindingRevision >= prior.bindingRevision()) {
			accessChanged = !next.equals(prior);
			if (accessChanged) productionLibraryAccess.put(catalog.libraryId(), next);
		}
		if (catalogChanged || accessChanged) {
			LinkedHashSet<UUID> affected = new LinkedHashSet<>();
			if (prior != null) affected.addAll(prior.logisticsIds());
			ProductionLibraryAccess installed = productionLibraryAccess.get(catalog.libraryId());
			if (installed != null) affected.addAll(installed.logisticsIds());
			invalidateProductionSearches(affected);
		}
	}

	private void invalidateProductionSearches(Set<UUID> affectedLogistics) {
		productionSearches.forEach((key, session) -> {
			if (affectedLogistics.contains(key.logisticsId())) session.invalidated = true;
		});
		productionSearchSnapshots.replaceAll((key, snapshot) ->
			affectedLogistics.contains(key.logisticsId())
				&& snapshot.status() != ProductionSearchStatus.SELECTED
				? new ProductionSearchSnapshot(snapshot.searchId(), snapshot.clusterId(),
					snapshot.epochId(), snapshot.logisticsId(), snapshot.requestedOutput(),
					snapshot.searchGeneration(), ProductionSearchStatus.SEARCHING,
					Optional.empty(), false, false)
				: snapshot);
	}

	/**
	 * Server-thread admission poll. It never traverses a catalog: it only snapshots directory
	 * state, submits bounded immutable Library work, advances the priority-wave coordinator from
	 * completed replies, and finally arbitrates the closed wave through the production route table.
	 */
	public synchronized Optional<SelectedPattern> selectProductionPattern(UUID clusterId,
		UUID logisticsId, StackKey requestedOutput) {
		try {
			return selectProductionPatternTransaction(clusterId, logisticsId, requestedOutput);
		} finally {
			flushProductionReleaseDispatches();
		}
	}

	private Optional<SelectedPattern> selectProductionPatternTransaction(UUID clusterId,
		UUID logisticsId, StackKey requestedOutput) {
		requireRunning();
		Objects.requireNonNull(clusterId, "clusterId");
		Objects.requireNonNull(logisticsId, "logisticsId");
		Objects.requireNonNull(requestedOutput, "requestedOutput");
		// A production reply may free a query slot and immediately request speculative work.  Do
		// not redispatch the just-completed immutable snapshot before those authoritative process
		// mutations have been installed.
		pumpResults(false);
		ClusterEngineState state = states.get(clusterId);
		if (state == null || state.members().stream().noneMatch(
			ClusterEngineState.MemberSnapshot::loaded)) return Optional.empty();
		ProductionSearchKey key = new ProductionSearchKey(clusterId, logisticsId, requestedOutput);
		ProductionSearchSnapshot durable = productionSearchSnapshots.get(key);
		Optional<SelectedPattern> restoredWinner = resolveDurableWinner(key, state.epochId(), durable);
		if (restoredWinner.isPresent()) return restoredWinner;

		ProductionSearchSession session = productionSearches.get(key);
		if (session == null || session.invalidated || !session.epochId.equals(state.epochId())
			|| !session.request.frequency().equals(productionRequesterFrequency.apply(clusterId))) {
			if (session != null && !releaseProductionSessionArtifacts(session, false))
				return Optional.empty();
			if (session == null && durable != null) {
				removeQueuedProductionSearchWork(durable.searchId());
				if (!releaseProcessesBySearchId(durable.searchId(), Set.of()))
					return Optional.empty();
				retireRouteSelectionQuery(key.clusterId(), durable.searchId());
			}
			session = beginProductionSearch(key, state, durable);
			productionSearches.put(key, session);
			trimProductionSearches();
			applyProductionSearchUpdate(session, session.opened);
			submitPendingProductionQueries(session);
			return Optional.empty();
		}
		if (session.selected != null) {
			Optional<SelectedPattern> current = resolveExactProvider(key, state,
				session.selected.provider());
			if (current.isPresent()) {
				session.selected = current.orElseThrow();
				return current;
			}
			invalidateSelectedProvider(session);
			return Optional.empty();
		}
		if (session.routeUpdate != null) {
			applyProductionSearchUpdate(session, session.routeUpdate);
			return Optional.ofNullable(session.selected);
		}

		applyProductionSearchUpdate(session, session.coordinator.refresh(
			remainingAlchemistCapacity(clusterId)));
		ArrayDeque<PatternReply> ready = readyLibraryReplies.get(clusterId);
		int accepted = 0;
		if (ready != null) {
			for (Iterator<PatternReply> iterator = ready.iterator(); iterator.hasNext()
				&& accepted < Math.max(1, config.libraryReplyBatch());) {
				PatternReply reply = iterator.next();
				if (!reply.queryId().equals(session.searchId)
					|| !reply.requesterComputerId().equals(session.requesterComputerId)) continue;
				iterator.remove(); accepted++;
				LibrarySearchCoordinator.Update acceptedUpdate = session.coordinator.accept(reply,
					remainingAlchemistCapacity(clusterId));
				if (reply.status() != PatternReply.Status.MATCH_BATCH
					&& !acceptedUpdate.staleReplyRejected())
					releaseProductionQuery(session, reply);
				applyProductionSearchUpdate(session, acceptedUpdate);
			}
		}
		submitPendingProductionQueries(session);
		return Optional.ofNullable(session.selected);
	}

	private record ProductionLibraryAccess(UUID clusterId, Set<UUID> logisticsIds,
		long bindingRevision, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency, int priority, SpaceAddress address,
		LibraryRegistration.Reachability reachability) {
		private ProductionLibraryAccess {
			clusterId = Objects.requireNonNull(clusterId, "clusterId");
			logisticsIds = Set.copyOf(Objects.requireNonNull(logisticsIds, "logisticsIds"));
			address = Objects.requireNonNull(address, "address");
			frequency = Objects.requireNonNull(frequency, "frequency");
			reachability = Objects.requireNonNull(reachability, "reachability");
		}
	}

	private ProductionSearchSession beginProductionSearch(ProductionSearchKey key,
		ClusterEngineState state, ProductionSearchSnapshot durable) {
		LibraryDirectory directory = new LibraryDirectory();
		for (ProductionRecipeCatalog catalog : libraryCatalogs.values()) {
			ProductionLibraryAccess access = productionLibraryAccess.get(catalog.libraryId());
			if (access == null || !access.logisticsIds().contains(key.logisticsId())) continue;
			directory.publish(new LibraryRegistration(catalog.libraryId(), access.clusterId(),
				new RecipeSourceLocation(access.address().dimension(), access.address().subLevelId(), access.address().localPos()), catalog.formationGeneration(), access.frequency(), access.priority(),
				catalog.catalogGeneration(), catalog.loaded()
					? LibraryRegistration.LoadState.LOADED
					: LibraryRegistration.LoadState.UNLOADED, access.reachability()));
		}
		long generation = durable == null ? 0 : Math.addExact(durable.searchGeneration(), 1);
		UUID searchId = durable == null ? UUID.randomUUID() : durable.searchId();
		UUID requester = state.members().stream()
			.filter(ClusterEngineState.MemberSnapshot::loaded)
			.map(ClusterEngineState.MemberSnapshot::computerId).sorted().findFirst()
			.orElseThrow();
		long attemptSequence = state.nextRouteSelectionSequence();
		UUID attemptId = UUID.nameUUIDFromBytes((searchId + "/" + generation + "/"
			+ attemptSequence).getBytes(StandardCharsets.UTF_8));
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory);
		LibrarySearchCoordinator.SearchRequest request = new LibrarySearchCoordinator.SearchRequest(
			searchId, requester, key.clusterId(), state.epochId(), key.logisticsId(),
			productionRequesterFrequency.apply(key.clusterId()),
			key.requestedOutput(), generation, attemptSequence, attemptId,
			Math.min(config.libraryReplyBatch(), PatternQuery.MAX_REPLY_BATCH),
			remainingAlchemistCapacity(key.clusterId()),
			PatternPriorityAuthority.fromAuthoritativeState(state));
		LibrarySearchCoordinator.Update opened = coordinator.begin(request);
		ProductionSearchSession session = new ProductionSearchSession(key, state.epochId(), searchId,
			requester, generation, coordinator, request, opened);
		productionSearchSnapshots.put(key, session.snapshot(ProductionSearchStatus.SEARCHING));
		return session;
	}

	private void applyProductionSearchUpdate(ProductionSearchSession session,
		LibrarySearchCoordinator.Update update) {
		if (session.selected != null) return;
		if (session.pendingWinner != null) {
			finalizeProductionSearchWinner(session, session.pendingWinner);
			return;
		}
		for (PatternQuery query : update.queries()) {
			boolean alreadyPending = session.pendingQueries.stream().anyMatch(candidate ->
				candidate.libraryId().equals(query.libraryId())
					&& candidate.attemptId().equals(query.attemptId()));
			ProductionQueryAllocation active = session.activeQueries.get(query.libraryId());
			if (!alreadyPending && (active == null
				|| !active.query().attemptId().equals(query.attemptId())))
				session.pendingQueries.addLast(query);
		}
		handleSpeculationUpdate(session, update);
		List<ProductionRouteTable.ProviderSource> priorProviderSources =
			session.pendingProviderSources;
		List<ProductionRouteTable.ProviderSource> currentProviderSources =
			update.providerSources().isEmpty() ? priorProviderSources : update.providerSources();
		if (!currentProviderSources.isEmpty()) {
			List<ProductionRouteTable.ProviderSource> resolvedProviderSources =
				activateFinePatternPriorities(session, currentProviderSources);
			if (session.patternCollectorComplete && !priorProviderSources.isEmpty()
				&& !priorProviderSources.equals(resolvedProviderSources)) {
				session.invalidated = true;
				session.patternCollectorComplete = false;
				session.agentCollectorComplete = false;
				session.gaugeCollectorComplete = false;
				session.ordinaryCollectorComplete = false;
				productionSearchSnapshots.put(session.key,
					session.snapshot(ProductionSearchStatus.SEARCHING));
				return;
			}
			session.pendingProviderSources = resolvedProviderSources;
		}
		if (update.status() == LibrarySearchCoordinator.Status.SELECTING_ROUTE)
			session.routeUpdate = update;
		if (update.status() == LibrarySearchCoordinator.Status.NOT_FOUND) {
			productionSearchSnapshots.put(session.key,
				session.snapshot(ProductionSearchStatus.NOT_FOUND));
			return;
		}
		if (update.status() != LibrarySearchCoordinator.Status.SELECTING_ROUTE) {
			productionSearchSnapshots.put(session.key,
				session.snapshot(update.status() == LibrarySearchCoordinator.Status.WAITING_LIBRARY
					? ProductionSearchStatus.WAITING_LIBRARY : ProductionSearchStatus.SEARCHING));
			return;
		}
		RouteSelectionScope scope = session.scope();
		productionSearchSnapshots.put(session.key,
			session.snapshot(ProductionSearchStatus.SELECTING_ROUTE));
		if (!session.routeBegun) {
			session.routeBegun = beginRouteSelection(session.key.clusterId(),
				RouteSelectionRequest.patternOnly(scope));
			if (!session.routeBegun) return;
		}
		if (session.optionalRouteSources == null && !session.pendingProviderSources.isEmpty())
			session.optionalRouteSources = productionRouteCollector.collect(
				new ProductionCollectionRequest(session.key.clusterId(),
					session.key.logisticsId(), scope, session.pendingProviderSources));
		if (!session.patternCollectorComplete && session.optionalRouteSources != null) {
			List<ProductionRouteTable.ProviderSource> patterns =
				session.optionalRouteSources.patterns().isEmpty()
					? session.pendingProviderSources : withAuthoritativePatternPriorities(
						session.optionalRouteSources.patterns(), session.pendingProviderSources);
			if (replaceRouteSources(session.key.clusterId(), scope,
				RouteCandidate.ProviderKind.PATTERN, patterns))
				session.patternCollectorComplete = completeRouteCollector(session.key.clusterId(), scope,
					RouteCandidate.ProviderKind.PATTERN);
		}
		if (session.patternCollectorComplete && !session.agentCollectorComplete) {
			session.agentCollectorComplete = replaceRouteSources(session.key.clusterId(), scope,
				RouteCandidate.ProviderKind.AGENT, session.optionalRouteSources.agents())
				&& completeRouteCollector(session.key.clusterId(), scope,
					RouteCandidate.ProviderKind.AGENT);
			if (session.agentCollectorComplete)
				productionSearchSnapshots.put(session.key,
					session.snapshot(ProductionSearchStatus.SELECTING_ROUTE));
		}
		if (session.patternCollectorComplete && !session.gaugeCollectorComplete) {
			session.gaugeCollectorComplete = replaceRouteSources(session.key.clusterId(), scope,
				RouteCandidate.ProviderKind.FACTORY_GAUGE, session.optionalRouteSources.gauges())
				&& completeRouteCollector(session.key.clusterId(), scope,
					RouteCandidate.ProviderKind.FACTORY_GAUGE);
			if (session.gaugeCollectorComplete)
				productionSearchSnapshots.put(session.key,
					session.snapshot(ProductionSearchStatus.SELECTING_ROUTE));
		}
		if (session.patternCollectorComplete && !session.ordinaryCollectorComplete) {
			session.ordinaryCollectorComplete = replaceRouteSources(session.key.clusterId(), scope,
				RouteCandidate.ProviderKind.ORDINARY_PROVIDER,
				session.optionalRouteSources.ordinaryProviders())
				&& completeRouteCollector(session.key.clusterId(), scope,
					RouteCandidate.ProviderKind.ORDINARY_PROVIDER);
			if (session.ordinaryCollectorComplete)
				productionSearchSnapshots.put(session.key,
					session.snapshot(ProductionSearchStatus.SELECTING_ROUTE));
		}
		if (!session.patternCollectorComplete || !session.agentCollectorComplete
			|| !session.gaugeCollectorComplete || !session.ordinaryCollectorComplete) return;
		Optional<DefaultRoutePolicy.Decision> decision = trySelectRoute(session.key.clusterId(), scope);
		if (decision.isEmpty()) return;
		LibrarySearchCoordinator.Update completed = session.coordinator.completeRouteSelection(
			decision.orElseThrow());
		handleSpeculationUpdate(session, completed);
		RouteCandidate winner = completed.winner().orElse(null);
		if (winner == null) {
			if (!releaseFinePriorityLeasesExcept(session, null)) return;
			session.routeUpdate = null;
			session.pendingProviderSources = List.of();
			session.optionalRouteSources = null;
			session.routeBegun = false;
			session.patternCollectorComplete = false;
			session.agentCollectorComplete = false;
			session.gaugeCollectorComplete = false;
			session.ordinaryCollectorComplete = false;
			session.invalidated = true;
			productionSearchSnapshots.put(session.key,
				session.snapshot(ProductionSearchStatus.SEARCHING));
			return;
		}
		session.pendingWinner = winner;
		finalizeProductionSearchWinner(session, winner);
	}

	private static List<ProductionRouteTable.ProviderSource> withAuthoritativePatternPriorities(
		List<ProductionRouteTable.ProviderSource> collected,
		List<ProductionRouteTable.ProviderSource> authoritative) {
		ArrayList<ProductionRouteTable.ProviderSource> aligned = new ArrayList<>(collected.size());
		for (ProductionRouteTable.ProviderSource source : collected) {
			ProductionRouteTable.ProviderSource match = authoritative.stream().filter(candidate ->
				candidate.libraryId().equals(source.libraryId())
					&& candidate.patternId().equals(source.patternId())
					&& candidate.providerKey().equals(source.providerKey()))
				.findFirst().orElse(null);
			if (match == null || match.effectivePriority() == source.effectivePriority()) {
				aligned.add(source); continue;
			}
			aligned.add(new ProductionRouteTable.ProviderSource(source.scope(), source.libraryId(),
				source.patternId(), source.providerKey(), source.providerKind(),
				match.effectivePriority(), source.availability(), source.blocker()));
		}
		return List.copyOf(aligned);
	}

	/** Publishes SELECTED only after every losing admission lease has actually been released. */
	private boolean finalizeProductionSearchWinner(ProductionSearchSession session,
		RouteCandidate winner) {
		if (!releaseFinePriorityLeasesExcept(session, winner.patternId())) return false;
		RouteSelectionScope scope = session.scope();
		ProductionRecipeCatalog catalog = libraryCatalogs.get(winner.libraryId());
		PatternRecord pattern = catalog == null ? null : catalog.patterns().stream()
			.filter(candidate -> candidate.patternId().equals(winner.patternId())).findFirst().orElse(null);
		if (pattern == null) {
			throw new IllegalStateException("production route winner vanished: " + winner
				+ " catalogs=" + libraryCatalogs.keySet());
		}
		SelectedProviderDescriptor provider = SelectedProviderDescriptor.selected(scope, winner);
		SelectedPattern selected = resolveExactProvider(session.key,
			Objects.requireNonNull(states.get(session.key.clusterId()), "unknown Cluster"), provider)
			.orElse(null);
		if (selected == null) {
			session.pendingWinner = null;
			invalidateSelectedProvider(session);
			return false;
		}
		session.selected = selected;
		session.pendingWinner = null;
		session.pendingProviderSources = List.of();
		session.routeUpdate = null;
		productionSearchSnapshots.put(session.key,
			session.snapshot(ProductionSearchStatus.SELECTED));
		return true;
	}

	private void submitPendingProductionQueries(ProductionSearchSession session) {
		ArrayDeque<PatternQuery> queue = libraryQueries.computeIfAbsent(session.key.clusterId(),
			ignored -> new ArrayDeque<>());
		if (!inFlight.contains(session.key.clusterId())) {
			boolean progressed;
			do {
				progressed = false;
				for (Iterator<PatternQuery> iterator = session.pendingQueries.iterator();
					iterator.hasNext() && queue.size() < config.pendingRunCapacity();) {
					PatternQuery query = iterator.next();
					if (session.activeQueries.containsKey(query.libraryId())) continue;
					Optional<ProductionQueryAllocation> allocation = allocateProductionQuery(
						session, query);
					if (allocation.isEmpty()) continue;
					iterator.remove();
					session.activeQueries.put(query.libraryId(), allocation.orElseThrow());
					queue.addLast(query);
					progressed = true;
				}
			} while (progressed && !session.pendingQueries.isEmpty()
				&& queue.size() < config.pendingRunCapacity());
		}
		dispatch(session.key.clusterId());
	}

	private Optional<ProductionQueryAllocation> allocateProductionQuery(
		ProductionSearchSession session, PatternQuery query) {
		ProductionLibraryAccess access = productionLibraryAccess.get(query.libraryId());
		if (access == null) return Optional.empty();
		List<SearchProcessRequest> requested = new ArrayList<>();
		requested.add(new SearchProcessRequest(searchProcessIdentity("library-query", session,
			query.libraryId(), query.attemptId()), ProcessKind.LIBRARY_QUERY));
		if (!access.clusterId().equals(session.key.clusterId()))
			requested.add(new SearchProcessRequest(searchProcessIdentity("library-listener", session,
				query.libraryId(), query.attemptId()), ProcessKind.REMOTE_LISTENER));
		Optional<List<SearchProcessAllocation>> processes = installSearchProcesses(
			session.key.clusterId(), session.searchId, session.generation, requested);
		return processes.map(value -> new ProductionQueryAllocation(query, value));
	}

	private void releaseProductionQuery(ProductionSearchSession session, PatternReply reply) {
		ProductionQueryAllocation allocation = session.activeQueries.get(reply.libraryId());
		if (allocation == null || !allocation.query().attemptId().equals(reply.attemptId())) return;
		if (releaseSearchProcesses(session.searchId, allocation.processes()))
			session.activeQueries.remove(reply.libraryId());
	}

	private int remainingAlchemistCapacity(UUID clusterId) {
		ClusterEngineState state = states.get(clusterId);
		if (state == null) return 0;
		Set<Map.Entry<UUID, Integer>> occupied = new HashSet<>();
		state.processes().values().stream()
			.filter(process -> process.state() != ProcessState.TERMINAL)
			.forEach(process -> occupied.add(Map.entry(process.owningComputerId(),
				process.owningSlot())));
		int remaining = 0;
		for (ClusterEngineState.MemberSnapshot member : state.members()) {
			if (!member.loaded() || !member.profile().supports(ProductionCapability.ALCHEMIST)) continue;
			for (int slot = 0; slot < member.profile().processSlots(); slot++)
				if (!occupied.contains(Map.entry(member.computerId(), slot))) remaining++;
		}
		return remaining;
	}

	private Optional<List<SearchProcessAllocation>> installSearchProcesses(UUID clusterId,
		UUID searchId, long searchGeneration, List<SearchProcessRequest> requested) {
		ClusterEngineState state = states.get(clusterId);
		if (state == null || requested.isEmpty() || inFlight.contains(clusterId)
			|| state.generation() == Long.MAX_VALUE) return Optional.empty();
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		Set<Map.Entry<UUID, Integer>> occupied = new HashSet<>();
		processes.values().stream().filter(process -> process.state() != ProcessState.TERMINAL)
			.forEach(process -> occupied.add(Map.entry(process.owningComputerId(), process.owningSlot())));
		ArrayList<SearchProcessAllocation> allocations = new ArrayList<>(requested.size());
		int newProcesses = 0;
		int cursor = state.memberCursor();
		for (SearchProcessRequest request : requested) {
			ProcessRecord existing = processes.get(request.processId());
			if (existing != null) {
				if (!existing.goalId().equals(searchId) || existing.kind() != request.kind()
					|| existing.state() == ProcessState.TERMINAL
					|| request.kind().requiresAlchemist()
						&& !existing.owningComputer().supports(ProductionCapability.ALCHEMIST))
					return Optional.empty();
				allocations.add(new SearchProcessAllocation(clusterId, existing.processId(),
					existing.kind()));
				continue;
			}
			if (processes.size() >= ClusterEngineState.MAX_PROCESSES
				|| state.members().isEmpty()) return Optional.empty();
			ClusterEngineState.MemberSnapshot selected = null;
			int selectedSlot = -1, selectedIndex = -1;
			for (int offset = 0; offset < state.members().size() && selected == null; offset++) {
				int index = (cursor + offset) % state.members().size();
				ClusterEngineState.MemberSnapshot member = state.members().get(index);
				if (!member.loaded() || request.kind().requiresAlchemist()
					&& !member.profile().supports(ProductionCapability.ALCHEMIST)) continue;
				for (int slot = 0; slot < member.profile().processSlots(); slot++) {
					if (occupied.contains(Map.entry(member.computerId(), slot))) continue;
					selected = member; selectedSlot = slot; selectedIndex = index; break;
				}
			}
			if (selected == null) return Optional.empty();
			ProcessRecord process = new ProcessRecord(request.processId(), searchId,
				selected.computerId(), selectedSlot, selected.profile(), request.kind(),
				ProcessState.RUNNABLE, null, searchGeneration, Map.of());
			processes.put(process.processId(), process);
			occupied.add(Map.entry(selected.computerId(), selectedSlot));
			allocations.add(new SearchProcessAllocation(clusterId, process.processId(),
				process.kind()));
			newProcesses++;
			cursor = (selectedIndex + 1) % state.members().size();
		}
		if (newProcesses > 0)
			states.put(clusterId, stateWithProcessTables(state, processes,
				state.coordinationAssociations(), cursor));
		return Optional.of(List.copyOf(allocations));
	}

	private void handleSpeculationUpdate(ProductionSearchSession session,
		LibrarySearchCoordinator.Update update) {
		for (UUID cancelled : update.cancelledSpeculativeBranches()) {
			SearchProcessAllocation process = session.speculativeProcesses.get(cancelled);
			if (process != null && releaseSearchProcesses(session.searchId, List.of(process)))
				session.speculativeProcesses.remove(cancelled);
		}
		for (LibrarySearchCoordinator.SpeculativeAllocation allocation :
			update.speculativeAllocations()) {
			if (session.speculativeProcesses.containsKey(allocation.branchId())) continue;
			SearchProcessRequest request = new SearchProcessRequest(allocation.branchId(),
				ProcessKind.SPECULATIVE_EXPANSION);
			Optional<List<SearchProcessAllocation>> installed = installSearchProcesses(
				session.key.clusterId(), session.searchId, session.generation, List.of(request));
			if (installed.isEmpty()) {
				session.coordinator.rejectSpeculation(allocation.branchId());
				continue;
			}
			session.speculativeProcesses.put(allocation.branchId(), installed.orElseThrow().getFirst());
			session.coordinator.acknowledgeSpeculation(allocation.branchId());
		}
		update.retainedSpeculativeBranch().ifPresent(branch ->
			session.retainedSpeculativeBranch = branch);
	}

	private static UUID searchProcessIdentity(String role, ProductionSearchSession session,
		UUID subjectId, UUID attemptId) {
		return UUID.nameUUIDFromBytes((role + ":" + session.searchId + ":" + session.generation
			+ ":" + subjectId + ":" + attemptId).getBytes(StandardCharsets.UTF_8));
	}

	private List<ProductionRouteTable.ProviderSource> activateFinePatternPriorities(
		ProductionSearchSession session,
		List<ProductionRouteTable.ProviderSource> sources) {
		List<ProductionRouteTable.ProviderSource> stable = sources.stream()
			.sorted(Comparator.comparing(ProductionRouteTable.ProviderSource::libraryId)
				.thenComparing(ProductionRouteTable.ProviderSource::patternId))
			.toList();
		for (ProductionRouteTable.ProviderSource source : stable) {
			ProductionRecipeCatalog catalog = libraryCatalogs.get(source.libraryId());
			PatternRecord pattern = catalog == null ? null : catalog.patterns().stream()
				.filter(candidate -> candidate.patternId().equals(source.patternId()))
				.findFirst().orElse(null);
			if (pattern != null && pattern.priorityOverride().isPresent())
				acquireFinePriorityLease(session, source.libraryId(), pattern, catalog);
		}
		ArrayList<ProductionRouteTable.ProviderSource> resolved = new ArrayList<>(sources.size());
		for (ProductionRouteTable.ProviderSource source : sources) {
			ProductionLibraryAccess access = productionLibraryAccess.get(source.libraryId());
			ProductionRecipeCatalog catalog = libraryCatalogs.get(source.libraryId());
			PatternRecord pattern = catalog == null ? null : catalog.patterns().stream()
				.filter(candidate -> candidate.patternId().equals(source.patternId()))
				.findFirst().orElse(null);
			if (access == null || catalog == null || pattern == null) {
				resolved.add(source); continue;
			}
			LibraryRegistration registration = registration(access, catalog);
			ClusterEngineState owner = states.get(access.clusterId());
			boolean coordinated = !inFlight.contains(access.clusterId()) && owner != null
				&& PatternPriorityAuthority.fromAuthoritativeState(owner)
				.authorizes(registration.clusterId(), registration.libraryId(), pattern.patternId(), registration.publicCatalogGeneration());
			int priority = PatternPriorityPolicy.resolve(pattern.priorityOverride(), access.priority(), coordinated)
				.effectivePriority();
			resolved.add(new ProductionRouteTable.ProviderSource(source.scope(), source.libraryId(),
				source.patternId(), source.providerKey(), source.providerKind(), priority,
				source.availability(), source.blocker()));
		}
		return List.copyOf(resolved);
	}

	private void acquireFinePriorityLease(ProductionSearchSession session, UUID libraryId,
		PatternRecord pattern, ProductionRecipeCatalog catalog) {
		ProductionLibraryAccess access = productionLibraryAccess.get(libraryId);
		if (access == null) return;
		SearchProcessAllocation existing = session.finePriorityProcesses.get(pattern.patternId());
		if (existing != null) {
			if (finePriorityLeaseActive(access, catalog, pattern, existing)) return;
			if (!releaseSearchProcesses(session.searchId, List.of(existing))) return;
			session.finePriorityProcesses.remove(pattern.patternId(), existing);
		}
		UUID processId = finePriorityProcessIdentity(session.searchId, session.generation, libraryId,
			pattern.patternId(), catalog.catalogGeneration());
		SearchProcessRequest request = new SearchProcessRequest(processId,
			ProcessKind.PATTERN_PRIORITY_LEASE);
		Optional<List<SearchProcessAllocation>> installed = installSearchProcesses(access.clusterId(),
			session.searchId, session.generation, List.of(request));
		if (installed.isEmpty()) return;
		SearchProcessAllocation allocation = installed.orElseThrow().getFirst();
		ClusterEngineState owner = states.get(access.clusterId());
		if (owner == null || owner.coordinationAssociations().size()
			>= PatternPriorityAuthority.MAX_LEASES) {
			releaseSearchProcesses(session.searchId, List.of(allocation));
			return;
		}
		CoordinationAssociation association = new CoordinationAssociation(processId, libraryId,
			pattern.patternId(), catalog.catalogGeneration());
		LinkedHashMap<UUID, CoordinationAssociation> associations =
			new LinkedHashMap<>(owner.coordinationAssociations());
		CoordinationAssociation prior = associations.put(processId, association);
		if (prior != null && !prior.equals(association)) {
			releaseSearchProcesses(session.searchId, List.of(allocation));
			return;
		}
		states.put(access.clusterId(), stateWithProcessTables(owner, owner.processes(), associations,
			owner.memberCursor()));
		session.finePriorityProcesses.put(pattern.patternId(), allocation);
	}

	private boolean finePriorityLeaseActive(ProductionLibraryAccess access,
		ProductionRecipeCatalog catalog, PatternRecord pattern, SearchProcessAllocation allocation) {
		if (!allocation.clusterId().equals(access.clusterId())
			|| allocation.kind() != ProcessKind.PATTERN_PRIORITY_LEASE
			|| inFlight.contains(access.clusterId())) return false;
		ClusterEngineState owner = states.get(access.clusterId());
		return owner != null && owner.processes().containsKey(allocation.processId())
			&& PatternPriorityAuthority.fromAuthoritativeState(owner).authorizes(access.clusterId(),
				catalog.libraryId(), pattern.patternId(), catalog.catalogGeneration());
	}

	private static UUID finePriorityProcessIdentity(UUID searchId, long searchGeneration,
		UUID libraryId, UUID patternId, long catalogGeneration) {
		return UUID.nameUUIDFromBytes(("pattern-priority:" + searchId + ":" + searchGeneration
			+ ":" + libraryId + ":" + patternId + ":" + catalogGeneration)
			.getBytes(StandardCharsets.UTF_8));
	}

	private boolean releaseFinePriorityLeasesExcept(ProductionSearchSession session,
		UUID retainedPatternId) {
		List<SearchProcessAllocation> cancelled = session.finePriorityProcesses.entrySet().stream()
			.filter(entry -> retainedPatternId == null || !entry.getKey().equals(retainedPatternId))
			.map(Map.Entry::getValue).toList();
		if (!cancelled.isEmpty() && !releaseSearchProcesses(session.searchId, cancelled)) return false;
		session.finePriorityProcesses.entrySet().removeIf(entry -> retainedPatternId == null
			|| !entry.getKey().equals(retainedPatternId));
		session.retainedFinePriorityPattern = retainedPatternId;
		return true;
	}

	private static LibraryRegistration registration(ProductionLibraryAccess access,
		ProductionRecipeCatalog catalog) {
		return new LibraryRegistration(catalog.libraryId(), access.clusterId(),
			new RecipeSourceLocation(access.address().dimension(), access.address().subLevelId(), access.address().localPos()),
			catalog.formationGeneration(), access.frequency(), access.priority(),
			catalog.catalogGeneration(), catalog.loaded() ? LibraryRegistration.LoadState.LOADED
				: LibraryRegistration.LoadState.UNLOADED, access.reachability());
	}

	private boolean releaseProductionSessionArtifacts(ProductionSearchSession session,
		boolean retainWinnerLeases) {
		LinkedHashSet<UUID> retainedProcessIds = new LinkedHashSet<>();
		if (retainWinnerLeases) {
			SearchProcessAllocation speculation = session.speculativeProcesses
				.get(session.retainedSpeculativeBranch);
			SearchProcessAllocation priority = session.finePriorityProcesses
				.get(session.retainedFinePriorityPattern);
			if (speculation != null) retainedProcessIds.add(speculation.processId());
			if (priority != null) retainedProcessIds.add(priority.processId());
		}
		if (!releaseProcessesBySearchId(session.searchId, retainedProcessIds)) return false;
		retireRouteSelectionQuery(session.key.clusterId(), session.searchId);
		ArrayDeque<PatternQuery> queued = libraryQueries.get(session.key.clusterId());
		if (queued != null) queued.removeIf(query -> query.queryId().equals(session.searchId));
		ArrayDeque<PatternReply> ready = readyLibraryReplies.get(session.key.clusterId());
		if (ready != null) ready.removeIf(reply -> reply.queryId().equals(session.searchId));
		session.activeQueries.clear();
		session.pendingQueries.clear();
		if (!retainWinnerLeases) {
			session.speculativeProcesses.clear();
			session.finePriorityProcesses.clear();
		}
		return true;
	}

	private boolean releaseSearchProcesses(UUID searchId,
		Collection<SearchProcessAllocation> allocations) {
		if (allocations.isEmpty()) return true;
		Set<UUID> clusters = allocations.stream().map(SearchProcessAllocation::clusterId)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		if (clusters.stream().anyMatch(clusterId -> inFlight.contains(clusterId)
			|| states.containsKey(clusterId)
				&& states.get(clusterId).generation() == Long.MAX_VALUE)) return false;
		LinkedHashSet<UUID> changedClusters = new LinkedHashSet<>();
		for (UUID clusterId : clusters) {
			ClusterEngineState state = states.get(clusterId);
			if (state == null) continue;
			Set<UUID> processIds = allocations.stream()
				.filter(allocation -> allocation.clusterId().equals(clusterId))
				.map(SearchProcessAllocation::processId).collect(java.util.stream.Collectors.toSet());
			LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
			boolean changed = processes.entrySet().removeIf(entry -> processIds.contains(entry.getKey())
				&& entry.getValue().goalId().equals(searchId));
			if (!changed) continue;
			LinkedHashMap<UUID, CoordinationAssociation> associations =
				new LinkedHashMap<>(state.coordinationAssociations());
			processIds.forEach(associations::remove);
			states.put(clusterId, stateAfterSpecialProcessRelease(state, processes, associations));
			changedClusters.add(clusterId);
		}
		productionReleaseDispatches.addAll(changedClusters);
		return true;
	}

	private boolean releaseProcessesBySearchId(UUID searchId, Set<UUID> retainedProcessIds) {
		List<UUID> clusters = states.entrySet().stream()
			.filter(entry -> entry.getValue().processes().values().stream().anyMatch(process ->
				process.goalId().equals(searchId)
					&& !retainedProcessIds.contains(process.processId())))
			.map(Map.Entry::getKey).toList();
		if (clusters.stream().anyMatch(clusterId -> inFlight.contains(clusterId)
			|| states.get(clusterId).generation() == Long.MAX_VALUE)) return false;
		LinkedHashSet<UUID> changedClusters = new LinkedHashSet<>();
		for (UUID clusterId : clusters) {
			ClusterEngineState state = states.get(clusterId);
			LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
			Set<UUID> removed = processes.values().stream()
				.filter(process -> process.goalId().equals(searchId)
					&& !retainedProcessIds.contains(process.processId()))
				.map(ProcessRecord::processId).collect(java.util.stream.Collectors.toSet());
			removed.forEach(processes::remove);
			LinkedHashMap<UUID, CoordinationAssociation> associations =
				new LinkedHashMap<>(state.coordinationAssociations());
			removed.forEach(associations::remove);
			states.put(clusterId, stateAfterSpecialProcessRelease(state, processes, associations));
			changedClusters.add(clusterId);
		}
		productionReleaseDispatches.addAll(changedClusters);
		return true;
	}

	private void flushProductionReleaseDispatches() {
		List<UUID> clusters = List.copyOf(productionReleaseDispatches);
		productionReleaseDispatches.clear();
		clusters.forEach(this::dispatch);
	}

	private Optional<SelectedPattern> resolveDurableWinner(ProductionSearchKey key,
		UUID currentEpochId, ProductionSearchSnapshot snapshot) {
		if (snapshot == null || snapshot.status() != ProductionSearchStatus.SELECTED
			|| !snapshot.epochId().equals(currentEpochId)
			|| snapshot.selectedProvider().isEmpty())
			return Optional.empty();
		SelectedProviderDescriptor provider = snapshot.selectedProvider().orElseThrow();
		if (!snapshot.matches(provider)) return Optional.empty();
		ClusterEngineState state = states.get(key.clusterId());
		if (state == null || !state.epochId().equals(currentEpochId)) return Optional.empty();
		return resolveExactProvider(key, state, provider);
	}

	private Optional<SelectedPattern> resolveExactProvider(ProductionSearchKey key,
		ClusterEngineState state, SelectedProviderDescriptor provider) {
		RouteSelectionScope scope = provider.selectionScope();
		if (!state.clusterId().equals(key.clusterId()) || !state.epochId().equals(scope.epochId())
			|| !scope.requestedStackKey().equals(key.requestedOutput())) return Optional.empty();
		UUID libraryId = provider.libraryId();
		ProductionLibraryAccess access = productionLibraryAccess.get(libraryId);
		ProductionRecipeCatalog catalog = libraryCatalogs.get(libraryId);
		com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency requesterFrequency = productionRequesterFrequency.apply(key.clusterId());
		boolean routeAvailable = access != null && (access.clusterId().equals(key.clusterId())
			|| requesterFrequency.configured() && access.frequency().equals(requesterFrequency)
				&& access.reachability() == LibraryRegistration.Reachability.REACHABLE);
		if (access == null || catalog == null || !catalog.loaded()
			|| !access.logisticsIds().contains(key.logisticsId())
			|| !routeAvailable)
			return Optional.empty();
		PatternRecord pattern = catalog.patterns().stream()
			.filter(candidate -> candidate.patternId().equals(provider.patternId())
				&& candidate.mainOutput().stack().equals(key.requestedOutput()))
			.findFirst().orElse(null);
		if (pattern == null) return Optional.empty();
		ProductionRouteTable.ProviderSource current;
		if (provider.providerKind() == RouteCandidate.ProviderKind.PATTERN
			&& !productionProviderResolverConfigured) {
			LibraryRegistration registration = registration(access, catalog);
			ClusterEngineState ownerState = states.get(access.clusterId());
			int priority = PatternPriorityPolicy.resolve(pattern.priorityOverride(), access.priority(),
				ownerState != null && PatternPriorityAuthority.fromAuthoritativeState(ownerState)
					.authorizes(registration.clusterId(), registration.libraryId(), pattern.patternId(), registration.publicCatalogGeneration()))
				.effectivePriority();
			current = new ProductionRouteTable.ProviderSource(scope, libraryId, pattern.patternId(),
				pattern.patternId().toString(), RouteCandidate.ProviderKind.PATTERN, priority,
				RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
		} else {
			ProductionProviderRevalidationRequest request =
				new ProductionProviderRevalidationRequest(key.clusterId(), key.logisticsId(),
					provider, pattern);
			current = Objects.requireNonNull(productionProviderResolver.resolve(request),
				"production provider resolver result").orElse(null);
		}
		return provider.matches(current) ? Optional.of(new SelectedPattern(provider, pattern))
			: Optional.empty();
	}

	private void invalidateSelectedProvider(ProductionSearchSession session) {
		releaseProductionSessionArtifacts(session, false);
		session.selected = null;
		session.invalidated = true;
		session.patternCollectorComplete = false;
		session.agentCollectorComplete = false;
		session.gaugeCollectorComplete = false;
		session.ordinaryCollectorComplete = false;
		productionSearchSnapshots.put(session.key,
			session.snapshot(ProductionSearchStatus.SEARCHING));
	}

	public synchronized Optional<ProductionSearchSnapshot> productionSearchSnapshot(UUID clusterId,
		UUID logisticsId, StackKey requestedOutput) {
		return Optional.ofNullable(productionSearchSnapshots.get(new ProductionSearchKey(clusterId,
			logisticsId, requestedOutput)));
	}

	/** Retires only the exact durable search cursor after its admission has moved past SEARCHING. */
	public synchronized boolean retireProductionSearch(UUID searchId) {
		try {
			return retireProductionSearchTransaction(searchId);
		} finally {
			flushProductionReleaseDispatches();
		}
	}

	private boolean retireProductionSearchTransaction(UUID searchId) {
		requireRunning(); searchId = Objects.requireNonNull(searchId, "searchId");
		UUID exactSearchId = searchId;
		pumpResults(false);
		List<ProductionSearchKey> matching = productionSearchSnapshots.entrySet().stream()
			.filter(entry -> entry.getValue().searchId().equals(exactSearchId))
			.map(Map.Entry::getKey).toList();
		if (!releaseProcessesBySearchId(exactSearchId, Set.of())) return false;
		removeQueuedProductionSearchWork(exactSearchId);
		LinkedHashSet<UUID> selectionOwners = matching.stream()
			.map(ProductionSearchKey::clusterId)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		productionSearches.values().stream()
			.filter(session -> session.searchId.equals(exactSearchId))
			.map(session -> session.key.clusterId()).forEach(selectionOwners::add);
		selectionOwners.forEach(clusterId -> retireRouteSelectionQuery(clusterId, exactSearchId));
		if (matching.isEmpty()) return true;
		matching.forEach(key -> {
			ProductionSearchSession session = productionSearches.get(key);
			if (session == null || session.searchId.equals(exactSearchId)) productionSearches.remove(key);
			productionSearchSnapshots.remove(key);
		});
		return true;
	}

	/**
	 * Transfers the winning pre-admission leases to the durable Goal before the search cursor is
	 * retired.  The process kind and exact slot are retained; only ownership lineage changes.
	 */
	public synchronized boolean adoptProductionSearchWinner(UUID searchId, UUID goalId) {
		requireRunning(); Objects.requireNonNull(searchId, "searchId");
		Objects.requireNonNull(goalId, "goalId"); pumpResults(false);
		ProductionSearchSession session = productionSearches.values().stream()
			.filter(candidate -> candidate.searchId.equals(searchId) && candidate.selected != null)
			.findFirst().orElse(null);
		LinkedHashMap<UUID, SearchProcessAllocation> retained = new LinkedHashMap<>();
		if (session == null) {
			ProductionSearchSnapshot durable = productionSearchSnapshots.values().stream()
				.filter(snapshot -> snapshot.searchId().equals(searchId)
					&& snapshot.status() == ProductionSearchStatus.SELECTED)
				.findFirst().orElse(null);
			if (durable == null) return false;
			SelectedProviderDescriptor provider = durable.selectedProvider().orElseThrow();
			int speculativeCount = 0;
			int priorityCount = 0;
			for (Map.Entry<UUID, ClusterEngineState> stateEntry : states.entrySet()) {
				ClusterEngineState state = stateEntry.getValue();
				for (ProcessRecord process : state.processes().values()) {
					boolean ownedByAdoption = process.goalId().equals(searchId)
						|| process.goalId().equals(goalId);
					if (process.kind() == ProcessKind.PATTERN_PRIORITY_LEASE) {
						CoordinationAssociation association = state.coordinationAssociations()
							.get(process.processId());
						boolean selectedAssociation = association != null
							&& association.libraryId().equals(provider.libraryId())
							&& association.patternId().equals(provider.patternId());
						UUID expectedProcessId = selectedAssociation ? finePriorityProcessIdentity(searchId,
							durable.searchGeneration(), association.libraryId(), association.patternId(),
							association.catalogGeneration()) : null;
						if (selectedAssociation && process.processId().equals(expectedProcessId)
							&& association.processId().equals(expectedProcessId)) {
							if (!ownedByAdoption || ++priorityCount > 1) return false;
							retained.put(process.processId(), new SearchProcessAllocation(
								stateEntry.getKey(), process.processId(), process.kind()));
						} else if (process.goalId().equals(searchId)) {
							return false;
						}
						continue;
					}
					if (!ownedByAdoption) continue;
					if (process.kind() == ProcessKind.SPECULATIVE_EXPANSION) {
						if (++speculativeCount > 1) return false;
						retained.put(process.processId(), new SearchProcessAllocation(stateEntry.getKey(),
							process.processId(), process.kind()));
						continue;
					}
				}
			}
		} else if (session.retainedSpeculativeBranch != null) {
			SearchProcessAllocation speculative = session.speculativeProcesses
				.get(session.retainedSpeculativeBranch);
			if (speculative != null) retained.put(speculative.processId(), speculative);
		}
		if (session != null && session.retainedFinePriorityPattern != null) {
			SearchProcessAllocation priority = session.finePriorityProcesses
				.get(session.retainedFinePriorityPattern);
			if (priority != null) retained.put(priority.processId(), priority);
		}
		if (retained.values().stream().map(SearchProcessAllocation::clusterId)
			.anyMatch(clusterId -> inFlight.contains(clusterId)
				|| states.containsKey(clusterId)
					&& states.get(clusterId).generation() == Long.MAX_VALUE))
			return false;
		LinkedHashMap<UUID, LinkedHashMap<UUID, ProcessRecord>> replacements = new LinkedHashMap<>();
		for (UUID clusterId : retained.values().stream().map(SearchProcessAllocation::clusterId)
			.distinct().toList()) {
			ClusterEngineState state = states.get(clusterId);
			if (state == null) return false;
			Set<UUID> processIds = retained.values().stream()
				.filter(allocation -> allocation.clusterId().equals(clusterId))
				.map(SearchProcessAllocation::processId).collect(java.util.stream.Collectors.toSet());
			LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
			boolean changed = false;
			for (UUID processId : processIds) {
				ProcessRecord process = processes.get(processId);
				if (process == null || !process.goalId().equals(searchId)
					&& !process.goalId().equals(goalId)) return false;
				if (process.goalId().equals(goalId)) continue;
				processes.put(processId, new ProcessRecord(process.processId(), goalId,
					process.owningComputerId(), process.owningSlot(), process.owningComputer(),
					process.kind(), process.state(), process.waitReason(), process.generation(),
					process.childRevisionStates()));
				changed = true;
			}
			if (changed) replacements.put(clusterId, processes);
		}
		replacements.forEach((clusterId, processes) -> {
			ClusterEngineState state = states.get(clusterId);
			states.put(clusterId, stateWithProcessTables(state, processes,
				state.coordinationAssociations(), state.memberCursor()));
		});
		return true;
	}

	/** Releases adopted fine-priority/speculation processes when their owning Goal is terminal. */
	public synchronized boolean releaseProductionGoalProcesses(UUID goalId) {
		try {
			requireRunning(); goalId = Objects.requireNonNull(goalId, "goalId"); pumpResults(false);
			return releaseProductionGoalProcessesNow(goalId);
		} finally {
			flushProductionReleaseDispatches();
		}
	}

	private boolean releaseProductionGoalProcessesNow(UUID goalId) {
		List<UUID> clusters = states.entrySet().stream().filter(entry ->
			entry.getValue().processes().values().stream().anyMatch(process ->
				process.goalId().equals(goalId) && (process.kind() == ProcessKind.SPECULATIVE_EXPANSION
					|| process.kind() == ProcessKind.PATTERN_PRIORITY_LEASE
						&& entry.getValue().coordinationAssociations()
							.containsKey(process.processId()))))
			.map(Map.Entry::getKey).toList();
		if (clusters.stream().anyMatch(clusterId -> inFlight.contains(clusterId)
			|| states.get(clusterId).generation() == Long.MAX_VALUE)) return false;
		LinkedHashSet<UUID> changedClusters = new LinkedHashSet<>();
		for (UUID clusterId : clusters) {
			ClusterEngineState state = states.get(clusterId);
			LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
			Set<UUID> removed = processes.values().stream().filter(process ->
				process.goalId().equals(goalId) && (process.kind() == ProcessKind.SPECULATIVE_EXPANSION
					|| process.kind() == ProcessKind.PATTERN_PRIORITY_LEASE
						&& state.coordinationAssociations().containsKey(process.processId())))
				.map(ProcessRecord::processId).collect(java.util.stream.Collectors.toSet());
			removed.forEach(processes::remove);
			LinkedHashMap<UUID, CoordinationAssociation> associations =
				new LinkedHashMap<>(state.coordinationAssociations());
			removed.forEach(associations::remove);
			states.put(clusterId, stateAfterSpecialProcessRelease(state, processes, associations));
			changedClusters.add(clusterId);
		}
		productionReleaseDispatches.addAll(changedClusters);
		return true;
	}

	public synchronized void restoreProductionSearchSnapshot(ProductionSearchSnapshot snapshot) {
		try {
			requireRunning(); snapshot = Objects.requireNonNull(snapshot, "snapshot");
			ProductionSearchKey key = new ProductionSearchKey(snapshot.clusterId(), snapshot.logisticsId(),
				snapshot.requestedOutput());
			ProductionSearchSnapshot prior = productionSearchSnapshots.get(key);
			if (prior == null || snapshot.searchGeneration() >= prior.searchGeneration())
				productionSearchSnapshots.put(key, snapshot);
			trimProductionSearches();
		} finally {
			flushProductionReleaseDispatches();
		}
	}

	/**
	 * Revalidates the exact selected provider against the current server-thread resolver.
	 * Matching only Library/Pattern identity is intentionally insufficient.
	 */
	public synchronized Optional<SelectedPattern> revalidateProductionProvider(UUID clusterId,
		UUID logisticsId, SelectedProviderDescriptor expected) {
		requireRunning(); clusterId = Objects.requireNonNull(clusterId, "clusterId");
		logisticsId = Objects.requireNonNull(logisticsId, "logisticsId");
		expected = Objects.requireNonNull(expected, "expected"); pumpResults();
		ProductionSearchKey key = new ProductionSearchKey(clusterId, logisticsId,
			expected.selectionScope().requestedStackKey());
		ClusterEngineState state = states.get(clusterId);
		// A FrozenProductionPlan is itself the durable owner of the selected descriptor.  Library
		// bootstrap/load publication intentionally invalidates admission-search cursors, so requiring
		// that auxiliary cursor here would strand an otherwise exact provider after every reload.
		// The descriptor's epoch/scope and a freshly reproduced full ProviderSource are the execution
		// fence; resolveExactProvider compares kind, key, priority, availability and scope exactly.
		if (state == null || !expected.selectionScope().epochId().equals(state.epochId()))
			return Optional.empty();
		return resolveExactProvider(key, state, expected);
	}

	private void trimProductionSearches() {
		int maximum = config.pendingRunCapacity();
		while (productionSearchSnapshots.size() > maximum) {
			ProductionSearchKey oldest = productionSearchSnapshots.keySet().iterator().next();
			ProductionSearchSnapshot snapshot = productionSearchSnapshots.get(oldest);
			if (snapshot == null || !releaseProcessesBySearchId(snapshot.searchId(), Set.of())) break;
			removeQueuedProductionSearchWork(snapshot.searchId());
			retireRouteSelectionQuery(oldest.clusterId(), snapshot.searchId());
			productionSearchSnapshots.remove(oldest);
			ProductionSearchSession session = productionSearches.get(oldest);
			if (session == null || session.searchId.equals(snapshot.searchId()))
				productionSearches.remove(oldest);
		}
	}

	public enum ProductionSearchStatus { SEARCHING, WAITING_LIBRARY, SELECTING_ROUTE, SELECTED, NOT_FOUND }

	@FunctionalInterface
	public interface ProductionRouteCollector {
		ProductionRouteSources collect(ProductionCollectionRequest request);
	}

	public record ProductionCollectionRequest(UUID clusterId, UUID logisticsId,
		RouteSelectionScope scope, List<ProductionRouteTable.ProviderSource> patternSources) {
		public ProductionCollectionRequest {
			clusterId = Objects.requireNonNull(clusterId, "clusterId");
			logisticsId = Objects.requireNonNull(logisticsId, "logisticsId");
			scope = Objects.requireNonNull(scope, "scope");
			patternSources = List.copyOf(Objects.requireNonNull(patternSources, "patternSources"));
			RouteSelectionScope exactScope = scope;
			if (patternSources.stream().anyMatch(source -> !source.scope().equals(exactScope)
				|| source.providerKind() != RouteCandidate.ProviderKind.PATTERN))
				throw new IllegalArgumentException("Invalid Pattern collector snapshot");
		}
	}

	public record ProductionRouteSources(List<ProductionRouteTable.ProviderSource> patterns,
		List<ProductionRouteTable.ProviderSource> agents,
		List<ProductionRouteTable.ProviderSource> gauges,
		List<ProductionRouteTable.ProviderSource> ordinaryProviders) {
		public ProductionRouteSources(List<ProductionRouteTable.ProviderSource> agents,
			List<ProductionRouteTable.ProviderSource> gauges) {
			this(List.of(), agents, gauges, List.of());
		}
		public ProductionRouteSources(List<ProductionRouteTable.ProviderSource> patterns,
			List<ProductionRouteTable.ProviderSource> agents,
			List<ProductionRouteTable.ProviderSource> gauges) {
			this(patterns, agents, gauges, List.of());
		}
		public ProductionRouteSources {
			patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
			agents = List.copyOf(Objects.requireNonNull(agents, "agents"));
			gauges = List.copyOf(Objects.requireNonNull(gauges, "gauges"));
			ordinaryProviders = List.copyOf(Objects.requireNonNull(ordinaryProviders,
				"ordinaryProviders"));
			if (patterns.stream().anyMatch(source -> source.providerKind()
				!= RouteCandidate.ProviderKind.PATTERN)
				|| agents.stream().anyMatch(source -> source.providerKind()
				!= RouteCandidate.ProviderKind.AGENT)
				|| gauges.stream().anyMatch(source -> source.providerKind()
					!= RouteCandidate.ProviderKind.FACTORY_GAUGE)
				|| ordinaryProviders.stream().anyMatch(source -> source.providerKind()
					!= RouteCandidate.ProviderKind.ORDINARY_PROVIDER))
				throw new IllegalArgumentException("Collector returned the wrong provider kind");
		}
	}

	/** Installs the production server-thread Agent/Gauge snapshotter for subsequent searches. */
	public synchronized void setProductionRouteCollector(ProductionRouteCollector collector) {
		requireRunning();
		collector = Objects.requireNonNull(collector, "collector");
		long version = nextProductionResolverVersion();
		productionRouteCollector = collector;
		productionRouteCollectorOwner = null;
		productionRouteCollectorVersion = version;
	}

	/** Installs the production server-thread exact Agent/Gauge provider resolver. */
	public synchronized void setProductionProviderResolver(ProductionProviderResolver resolver) {
		requireRunning();
		resolver = Objects.requireNonNull(resolver, "resolver");
		long version = nextProductionResolverVersion();
		productionProviderResolver = resolver;
		productionProviderResolverConfigured = true;
		productionProviderResolverOwner = null;
		productionProviderResolverVersion = version;
	}

	public synchronized void setProductionRequesterFrequencyResolver(
		java.util.function.Function<UUID, CommunicationFrequency> resolver) {
		requireRunning();
		resolver = Objects.requireNonNull(resolver, "resolver");
		long version = nextProductionResolverVersion();
		productionRequesterFrequency = resolver;
		productionRequesterFrequencyOwner = null;
		productionRequesterFrequencyVersion = version;
	}

	/**
	 * Installs the complete server-owned production resolver set as one reversible activation.
	 * A Runtime which fails before publication closes this scope so a later bootstrap observes the
	 * exact resolver state that preceded the failed activation.
	 */
	public synchronized AutoCloseable installProductionResolvers(ProductionRouteCollector collector,
		ProductionProviderResolver providerResolver,
		java.util.function.Function<UUID, CommunicationFrequency> requesterFrequency) {
		requireRunning();
		ProductionRouteCollector installedCollector = Objects.requireNonNull(collector, "collector");
		ProductionProviderResolver installedProviderResolver = Objects.requireNonNull(
			providerResolver, "providerResolver");
		java.util.function.Function<UUID, CommunicationFrequency> installedRequesterFrequency =
			Objects.requireNonNull(requesterFrequency, "requesterFrequency");
		long version = nextProductionResolverVersion();
		ProductionResolverScope scope = new ProductionResolverScope(version);
		productionRouteCollector = installedCollector;
		productionRouteCollectorOwner = scope;
		productionRouteCollectorVersion = version;
		productionProviderResolver = installedProviderResolver;
		productionProviderResolverConfigured = true;
		productionProviderResolverOwner = scope;
		productionProviderResolverVersion = version;
		productionRequesterFrequency = installedRequesterFrequency;
		productionRequesterFrequencyOwner = scope;
		productionRequesterFrequencyVersion = version;
		return scope;
	}

	private long nextProductionResolverVersion() {
		productionResolverVersion = Math.incrementExact(productionResolverVersion);
		return productionResolverVersion;
	}

	private final class ProductionResolverScope implements AutoCloseable {
		private final long version;
		private final ProductionRouteCollector priorCollector = productionRouteCollector;
		@Nullable private final ProductionResolverScope priorCollectorOwner =
			productionRouteCollectorOwner;
		private final long priorCollectorVersion = productionRouteCollectorVersion;
		private final ProductionProviderResolver priorProviderResolver = productionProviderResolver;
		@Nullable private final ProductionResolverScope priorProviderResolverOwner =
			productionProviderResolverOwner;
		private final long priorProviderResolverVersion = productionProviderResolverVersion;
		private final boolean priorProviderResolverConfigured =
			productionProviderResolverConfigured;
		private final java.util.function.Function<UUID, CommunicationFrequency>
			priorRequesterFrequency = productionRequesterFrequency;
		@Nullable private final ProductionResolverScope priorRequesterFrequencyOwner =
			productionRequesterFrequencyOwner;
		private final long priorRequesterFrequencyVersion = productionRequesterFrequencyVersion;
		private boolean closed;

		private ProductionResolverScope(long version) { this.version = version; }

		@Override public void close() {
			synchronized (FactoryRuntimeService.this) {
				if (closed) return;
				closed = true;
				if (productionRouteCollectorOwner == this
					&& productionRouteCollectorVersion == version) restoreCollector(this);
				if (productionProviderResolverOwner == this
					&& productionProviderResolverVersion == version) restoreProviderResolver(this);
				if (productionRequesterFrequencyOwner == this
					&& productionRequesterFrequencyVersion == version) restoreRequesterFrequency(this);
			}
		}
	}

	private void restoreCollector(ProductionResolverScope scope) {
		ProductionRouteCollector restored = scope.priorCollector;
		ProductionResolverScope owner = scope.priorCollectorOwner;
		long version = scope.priorCollectorVersion;
		while (owner != null && owner.closed) {
			restored = owner.priorCollector;
			version = owner.priorCollectorVersion;
			owner = owner.priorCollectorOwner;
		}
		productionRouteCollector = restored;
		productionRouteCollectorOwner = owner;
		productionRouteCollectorVersion = version;
	}

	private void restoreProviderResolver(ProductionResolverScope scope) {
		ProductionProviderResolver restored = scope.priorProviderResolver;
		ProductionResolverScope owner = scope.priorProviderResolverOwner;
		long version = scope.priorProviderResolverVersion;
		boolean configured = scope.priorProviderResolverConfigured;
		while (owner != null && owner.closed) {
			restored = owner.priorProviderResolver;
			version = owner.priorProviderResolverVersion;
			configured = owner.priorProviderResolverConfigured;
			owner = owner.priorProviderResolverOwner;
		}
		productionProviderResolver = restored;
		productionProviderResolverOwner = owner;
		productionProviderResolverVersion = version;
		productionProviderResolverConfigured = configured;
	}

	private void restoreRequesterFrequency(ProductionResolverScope scope) {
		java.util.function.Function<UUID, CommunicationFrequency> restored =
			scope.priorRequesterFrequency;
		ProductionResolverScope owner = scope.priorRequesterFrequencyOwner;
		long version = scope.priorRequesterFrequencyVersion;
		while (owner != null && owner.closed) {
			restored = owner.priorRequesterFrequency;
			version = owner.priorRequesterFrequencyVersion;
			owner = owner.priorRequesterFrequencyOwner;
		}
		productionRequesterFrequency = restored;
		productionRequesterFrequencyOwner = owner;
		productionRequesterFrequencyVersion = version;
	}

	/** Durable search cursor; optional collector acknowledgements are independent retry fences. */
	public record ProductionSearchSnapshot(UUID searchId, UUID clusterId, UUID epochId,
		UUID logisticsId, StackKey requestedOutput, long searchGeneration,
		ProductionSearchStatus status, Optional<SelectedProviderDescriptor> selectedProvider,
		boolean agentCollectorComplete, boolean gaugeCollectorComplete,
		boolean ordinaryCollectorComplete) {
		public ProductionSearchSnapshot(UUID searchId, UUID clusterId, UUID epochId,
			UUID logisticsId, StackKey requestedOutput, long searchGeneration,
			ProductionSearchStatus status, Optional<SelectedProviderDescriptor> selectedProvider) {
			this(searchId, clusterId, epochId, logisticsId, requestedOutput, searchGeneration,
				status, selectedProvider, false, false, false);
		}
		public ProductionSearchSnapshot(UUID searchId, UUID clusterId, UUID epochId,
			UUID logisticsId, StackKey requestedOutput, long searchGeneration,
			ProductionSearchStatus status, Optional<SelectedProviderDescriptor> selectedProvider,
			boolean agentCollectorComplete, boolean gaugeCollectorComplete) {
			this(searchId, clusterId, epochId, logisticsId, requestedOutput, searchGeneration,
				status, selectedProvider, agentCollectorComplete, gaugeCollectorComplete, false);
		}

		public ProductionSearchSnapshot {
			searchId = Objects.requireNonNull(searchId, "searchId");
			clusterId = Objects.requireNonNull(clusterId, "clusterId");
			epochId = Objects.requireNonNull(epochId, "epochId");
			logisticsId = Objects.requireNonNull(logisticsId, "logisticsId");
			requestedOutput = Objects.requireNonNull(requestedOutput, "requestedOutput");
			status = Objects.requireNonNull(status, "status");
			selectedProvider = Objects.requireNonNull(selectedProvider, "selectedProvider");
			if (searchGeneration < 0
				|| (status == ProductionSearchStatus.SELECTED) != selectedProvider.isPresent()
				|| ((status == ProductionSearchStatus.SEARCHING
					|| status == ProductionSearchStatus.WAITING_LIBRARY)
					&& (agentCollectorComplete || gaugeCollectorComplete
						|| ordinaryCollectorComplete))
				|| selectedProvider.isPresent() && !matches(selectedProvider.orElseThrow(), searchId,
					epochId, requestedOutput, searchGeneration))
				throw new IllegalArgumentException("invalid production search snapshot");
		}

		private boolean matches(SelectedProviderDescriptor provider) {
			return matches(provider, searchId, epochId, requestedOutput, searchGeneration);
		}

		private static boolean matches(SelectedProviderDescriptor provider, UUID searchId,
			UUID epochId, StackKey requestedOutput, long searchGeneration) {
			RouteSelectionScope scope = provider.selectionScope();
			return scope.queryId().equals(searchId) && scope.epochId().equals(epochId)
				&& scope.requestedStackKey().equals(requestedOutput)
				&& scope.searchGeneration() == searchGeneration;
		}

		/** Computed projection retained for diagnostics; it is not part of the persisted schema. */
		public Optional<UUID> selectedLibraryId() {
			return selectedProvider.map(SelectedProviderDescriptor::libraryId);
		}

		/** Computed projection retained for diagnostics; it is not part of the persisted schema. */
		public Optional<UUID> selectedPatternId() {
			return selectedProvider.map(SelectedProviderDescriptor::patternId);
		}
	}

	private record ProductionSearchKey(UUID clusterId, UUID logisticsId,
		StackKey requestedOutput) {
		private ProductionSearchKey {
			Objects.requireNonNull(clusterId); Objects.requireNonNull(logisticsId);
			Objects.requireNonNull(requestedOutput);
		}
	}

	private record SearchProcessRequest(UUID processId, ProcessKind kind) {
		private SearchProcessRequest {
			Objects.requireNonNull(processId); Objects.requireNonNull(kind);
		}
	}

	private record SearchProcessAllocation(UUID clusterId, UUID processId, ProcessKind kind) {
		private SearchProcessAllocation {
			Objects.requireNonNull(clusterId); Objects.requireNonNull(processId);
			Objects.requireNonNull(kind);
		}
	}

	private record ProductionQueryAllocation(PatternQuery query,
		List<SearchProcessAllocation> processes) {
		private ProductionQueryAllocation {
			Objects.requireNonNull(query); processes = List.copyOf(processes);
		}
	}

	private static final class ProductionSearchSession {
		private final ProductionSearchKey key;
		private final UUID epochId;
		private final UUID searchId;
		private final UUID requesterComputerId;
		private final long generation;
		private final LibrarySearchCoordinator coordinator;
		private final LibrarySearchCoordinator.SearchRequest request;
		private final LibrarySearchCoordinator.Update opened;
		private final ArrayDeque<PatternQuery> pendingQueries = new ArrayDeque<>();
		private final LinkedHashMap<UUID, ProductionQueryAllocation> activeQueries =
			new LinkedHashMap<>();
		private final LinkedHashMap<UUID, SearchProcessAllocation> speculativeProcesses =
			new LinkedHashMap<>();
		private final LinkedHashMap<UUID, SearchProcessAllocation> finePriorityProcesses =
			new LinkedHashMap<>();
		private UUID retainedSpeculativeBranch;
		private UUID retainedFinePriorityPattern;
		private RouteCandidate pendingWinner;
		private List<ProductionRouteTable.ProviderSource> pendingProviderSources = List.of();
		private LibrarySearchCoordinator.Update routeUpdate;
		private boolean routeBegun;
		private boolean patternCollectorComplete;
		private boolean agentCollectorComplete;
		private boolean gaugeCollectorComplete;
		private boolean ordinaryCollectorComplete;
		private ProductionRouteSources optionalRouteSources;
		private boolean invalidated;
		private SelectedPattern selected;

		private ProductionSearchSession(ProductionSearchKey key, UUID epochId, UUID searchId,
			UUID requesterComputerId, long generation, LibrarySearchCoordinator coordinator,
			LibrarySearchCoordinator.SearchRequest request, LibrarySearchCoordinator.Update opened) {
			this.key = key; this.epochId = epochId; this.searchId = searchId;
			this.requesterComputerId = requesterComputerId; this.generation = generation;
			this.coordinator = coordinator; this.request = request; this.opened = opened;
		}

		private RouteSelectionScope scope() {
			return new RouteSelectionScope(epochId, searchId, key.requestedOutput(), generation,
				request.selectionAttemptSequence(), request.selectionAttemptId());
		}

		private ProductionSearchSnapshot snapshot(ProductionSearchStatus status) {
			return new ProductionSearchSnapshot(searchId, key.clusterId(), epochId, key.logisticsId(),
				key.requestedOutput(), generation, status,
				selected == null ? Optional.empty() : Optional.of(selected.provider()),
				agentCollectorComplete, gaugeCollectorComplete, ordinaryCollectorComplete);
		}
	}

	public record SelectedPattern(SelectedProviderDescriptor provider, PatternRecord pattern) {
		public SelectedPattern {
			provider = Objects.requireNonNull(provider, "provider");
			pattern = Objects.requireNonNull(pattern, "pattern");
			if (!provider.patternId().equals(pattern.patternId())
				|| !provider.selectionScope().requestedStackKey().equals(pattern.mainOutput().stack()))
				throw new IllegalArgumentException("selected provider does not describe Pattern");
		}
		public UUID libraryId() { return provider.libraryId(); }
	}

	@FunctionalInterface
	public interface ProductionProviderResolver {
		/**
		 * Freshly captures the current Agent/Gauge source. {@code expectedProvider} is comparison
		 * evidence only; implementations must not manufacture a source by echoing it.
		 */
		Optional<ProductionRouteTable.ProviderSource> resolve(
			ProductionProviderRevalidationRequest request);
	}

	public record ProductionProviderRevalidationRequest(UUID clusterId, UUID logisticsId,
		SelectedProviderDescriptor expectedProvider, PatternRecord pattern) {
		public ProductionProviderRevalidationRequest {
			clusterId = Objects.requireNonNull(clusterId, "clusterId");
			logisticsId = Objects.requireNonNull(logisticsId, "logisticsId");
			expectedProvider = Objects.requireNonNull(expectedProvider, "expectedProvider");
			pattern = Objects.requireNonNull(pattern, "pattern");
			if (!expectedProvider.patternId().equals(pattern.patternId())
				|| !expectedProvider.selectionScope().requestedStackKey()
					.equals(pattern.mainOutput().stack()))
				throw new IllegalArgumentException("provider revalidation Pattern mismatch");
		}
	}

	public synchronized boolean enqueueLibraryQuery(UUID clusterId, UUID epochId, PatternQuery query) {
		requireRunning(); Objects.requireNonNull(clusterId); Objects.requireNonNull(epochId); Objects.requireNonNull(query);
		pumpResults();
		ClusterEngineState state = states.get(clusterId);
		if (state == null || !state.epochId().equals(epochId)) return false;
		ArrayDeque<PatternQuery> queue = libraryQueries.computeIfAbsent(clusterId, ignored -> new ArrayDeque<>());
		if (queue.size() >= config.pendingRunCapacity()) return false;
		queue.addLast(query); dispatch(clusterId); return true;
	}

	public synchronized List<PatternReply> drainLibraryReplies(UUID clusterId, int maximum) {
		requireRunning(); Objects.requireNonNull(clusterId);
		if (maximum < 0) throw new IllegalArgumentException("negative reply limit");
		pumpResults(); ArrayDeque<PatternReply> ready = readyLibraryReplies.get(clusterId);
		if (ready == null || maximum == 0) return List.of();
		ArrayList<PatternReply> drained = new ArrayList<>(Math.min(maximum, ready.size()));
		while (drained.size() < maximum && !ready.isEmpty()) drained.add(ready.removeFirst());
		dispatch(clusterId);
		return List.copyOf(drained);
	}

	public synchronized List<PatternReply> drainLibraryReplies(UUID clusterId,
		UUID requesterComputerId, int maximum) {
		requireRunning(); Objects.requireNonNull(clusterId); Objects.requireNonNull(requesterComputerId);
		if (maximum < 0) throw new IllegalArgumentException("negative reply limit");
		pumpResults(); ArrayDeque<PatternReply> ready = readyLibraryReplies.get(clusterId);
		if (ready == null || maximum == 0) return List.of();
		ArrayList<PatternReply> drained = new ArrayList<>(Math.min(maximum, ready.size()));
		for (Iterator<PatternReply> iterator = ready.iterator(); iterator.hasNext() && drained.size() < maximum;) {
			PatternReply reply = iterator.next();
			if (!reply.requesterComputerId().equals(requesterComputerId)) continue;
			drained.add(reply); iterator.remove();
		}
		dispatch(clusterId);
		return List.copyOf(drained);
	}

	/** Immutable, non-pumping observation of the production Library worker lanes. */
	public synchronized LibraryWorkloadDiagnostics libraryWorkloadDiagnostics(UUID clusterId) {
		Objects.requireNonNull(clusterId, "clusterId");
		ArrayDeque<PatternQuery> queued = libraryQueries.get(clusterId);
		ArrayDeque<PatternReply> ready = readyLibraryReplies.get(clusterId);
		return new LibraryWorkloadDiagnostics((queued == null ? 0 : queued.size())
			+ (inFlightLibraryWork.contains(clusterId) ? 1 : 0), ready == null ? 0 : ready.size());
	}

	public record LibraryWorkloadDiagnostics(int activeQueries, int readyReplies) {
		public LibraryWorkloadDiagnostics {
			if (activeQueries < 0 || readyReplies < 0)
				throw new IllegalArgumentException("negative Library workload diagnostics");
		}
	}

	/** Freezes collector participation in the production application, retaining host in-flight gates. */
	public synchronized boolean beginRouteSelection(UUID clusterId, RouteSelectionRequest request) {
		requireRunning(); Objects.requireNonNull(clusterId); request = Objects.requireNonNull(request); pumpResults();
		if (inFlight.contains(clusterId)) return false;
		ClusterEngineState state = states.get(clusterId);
		if (state == null) return false;
		RouteSelectionCoordinator coordinator = routeSelectionContext(state);
		if (!coordinator.begin(request)) return false;
		if (coordinator.nextAttemptSequence() != state.nextRouteSelectionSequence())
			states.put(clusterId, state.withNextRouteSelectionSequence(coordinator.nextAttemptSequence()));
		return true;
	}

	/** Explicit application CAS; old collectors cannot reverse a successful replacement. */
	public synchronized boolean supersedeRouteSelection(UUID clusterId,
		RouteSelectionScope expectedPrior, RouteSelectionRequest nextRequest) {
		requireRunning(); Objects.requireNonNull(clusterId); Objects.requireNonNull(expectedPrior);
		Objects.requireNonNull(nextRequest); pumpResults();
		if (inFlight.contains(clusterId)) return false;
		ClusterEngineState state = states.get(clusterId);
		RouteSelectionCoordinator coordinator = routeSelectionContexts.get(clusterId);
		if (state == null || coordinator == null || !coordinator.supersede(expectedPrior, nextRequest)) return false;
		states.put(clusterId, state.withNextRouteSelectionSequence(coordinator.nextAttemptSequence()));
		return true;
	}

	public synchronized boolean replaceRouteSources(UUID clusterId, RouteSelectionScope scope,
		RouteCandidate.ProviderKind kind, List<ProductionRouteTable.ProviderSource> feed) {
		requireRunning(); Objects.requireNonNull(clusterId); Objects.requireNonNull(scope); Objects.requireNonNull(kind);
		feed = List.copyOf(Objects.requireNonNull(feed)); pumpResults();
		if (inFlight.contains(clusterId) || !states.containsKey(clusterId)) return false;
		RouteSelectionCoordinator coordinator = routeSelectionContexts.get(clusterId);
		return coordinator != null && coordinator.replaceSources(scope, kind, feed);
	}

	public synchronized boolean completeRouteCollector(UUID clusterId, RouteSelectionScope scope,
		RouteCandidate.ProviderKind kind) {
		requireRunning(); Objects.requireNonNull(clusterId); Objects.requireNonNull(scope); Objects.requireNonNull(kind); pumpResults();
		RouteSelectionCoordinator coordinator = routeSelectionContexts.get(clusterId);
		return coordinator != null && coordinator.complete(scope, kind);
	}

	/** Select once in the application, then project the durable cursor into the existing save schema. */
	public synchronized Optional<DefaultRoutePolicy.Decision> trySelectRoute(UUID clusterId, RouteSelectionScope scope) {
		requireRunning(); Objects.requireNonNull(clusterId); Objects.requireNonNull(scope); pumpResults();
		if (inFlight.contains(clusterId)) return Optional.empty();
		RouteSelectionCoordinator coordinator = routeSelectionContexts.get(clusterId);
		if (coordinator == null) return Optional.empty();
		ClusterEngineState state = Objects.requireNonNull(states.get(clusterId), "unknown Cluster");
		Optional<DefaultRoutePolicy.Decision> decision = coordinator.select(scope);
		if (decision.isPresent()) states.put(clusterId, state.withRouteCursor(coordinator.cursorState()));
		return decision;
	}

	public synchronized List<ProductionRouteTable.ProviderSource> routeSourceSnapshot(UUID clusterId, RouteSelectionScope scope) {
		requireRunning(); Objects.requireNonNull(clusterId); Objects.requireNonNull(scope); pumpResults();
		RouteSelectionCoordinator coordinator = routeSelectionContexts.get(clusterId);
		return coordinator == null ? List.of() : coordinator.sourceSnapshot(scope);
	}

	private RouteSelectionCoordinator routeSelectionContext(ClusterEngineState state) {
		return routeSelectionContexts.computeIfAbsent(state.clusterId(), ignored -> new RouteSelectionCoordinator(
			state.epochId(), state.nextRouteSelectionSequence(), state.routeCursor(), routeSelectionScopeCapacity));
	}

	private void retireRouteSelectionQuery(UUID clusterId, UUID queryId) {
		RouteSelectionCoordinator coordinator = routeSelectionContexts.get(clusterId);
		if (coordinator != null) coordinator.retireQuery(queryId);
	}

	public synchronized boolean associateCoordinationProcess(UUID clusterId,
		CoordinationAssociation association) {
		requireRunning(); Objects.requireNonNull(clusterId); Objects.requireNonNull(association); pumpResults();
		if (inFlight.contains(clusterId)) return false;
		ClusterEngineState state = states.get(clusterId);
		if (state == null) return false;
		var process = state.processes().get(association.processId());
		var goal = process == null ? null : state.goals().get(process.goalId());
		if (!PatternPriorityAuthority.qualifies(state, association)
			|| goal == null || !goal.submission().patternId().equals(association.patternId())) return false;
		states.put(clusterId, state.withCoordinationAssociation(association));
		return true;
	}

	public synchronized PatternPriorityAuthority coordinationSnapshot(UUID clusterId) {
		requireRunning(); Objects.requireNonNull(clusterId); pumpResults();
		ClusterEngineState state = Objects.requireNonNull(states.get(clusterId), "unknown Cluster");
		return PatternPriorityAuthority.fromAuthoritativeState(state);
	}

	private static boolean sameMemberSnapshot(ClusterEngineState.MemberSnapshot installed,
		WorldFact.MemberLoadState published) {
		return installed != null && installed.profile().equals(published.profile())
			&& installed.loaded() == published.loaded()
			&& installed.routeGeneration() == published.routeGeneration()
			&& installed.fence() == published.fence();
	}

	/** Installs a validated current-schema snapshot before its first fact is published. */
	public synchronized void restoreSnapshot(ClusterEngineState state) {
		requireRunning(); Objects.requireNonNull(state, "state");
		if (inFlight.contains(state.clusterId())) throw new IllegalStateException("Cluster already has worker state in flight");
		ClusterEngineState prior = states.get(state.clusterId());
		if (prior != null && !prior.epochId().equals(state.epochId())) {
			taskPublicationAuthority.revokeCluster(state.clusterId());
			communicationPublicationAuthority.revokeCluster(state.clusterId());
			agentPublicationAuthority.revokeCluster(state.clusterId());
			panelRolePublications.remove(state.clusterId());
			removePanelEpochFences(state.clusterId());
		}
		states.put(state.clusterId(), state);
		facts.remove(state.clusterId()); confirmations.remove(state.clusterId());
		continuations.remove(state.clusterId()); readyIntents.remove(state.clusterId());
		replayedPendingIntents.remove(state.clusterId());
		deferredGoalWaits.keySet().removeIf(key -> key.clusterId().equals(state.clusterId()));
		libraryQueries.remove(state.clusterId()); readyLibraryReplies.remove(state.clusterId());
		inFlightLibraryWork.remove(state.clusterId());
		routeSelectionContexts.put(state.clusterId(), new RouteSelectionCoordinator(state.epochId(),
			state.nextRouteSelectionSequence(), state.routeCursor(), routeSelectionScopeCapacity));
		productionSearches.entrySet().stream()
			.filter(entry -> entry.getKey().clusterId().equals(state.clusterId()))
			.forEach(entry -> entry.getValue().invalidated = true);
		for (UUID intentId : state.uncertainIntentIds())
			addDiagnostic(new FactoryComputeWorker.Diagnostic("restored pending intent is uncertain: " + intentId));
	}

	/** Redelivers one exact durable Create intent after process restart without creating a new fact. */
	public synchronized boolean replayPendingCreateDispatch(UUID clusterId, UUID runId) {
		requireRunning(); clusterId = Objects.requireNonNull(clusterId, "clusterId");
		runId = Objects.requireNonNull(runId, "runId"); pumpResults();
		ClusterEngineState state = states.get(clusterId);
		if (state == null) return false;
		UUID exactRunId = runId;
		List<ProductionEffect.CreateDispatch> matching = state.pendingIntents().values().stream()
			.filter(ProductionEffect.CreateDispatch.class::isInstance)
			.map(ProductionEffect.CreateDispatch.class::cast)
			.filter(intent -> intent.runId().equals(exactRunId)).toList();
		if (matching.size() != 1) return false;
		return queuePendingCreateDispatch(state, matching.getFirst());
	}

	/**
	 * Reinstalls and queues the exact durable Create command whose prior attempt has an
	 * authoritative no-material rejection. This does not advance generation or derive a new ID.
	 */
	public synchronized boolean retryRejectedCreateDispatch(ProductionEffect.CreateDispatch intent) {
		requireRunning();
		ProductionEffect.CreateDispatch exactIntent = Objects.requireNonNull(intent, "intent");
		pumpResults();
		if (inFlight.contains(exactIntent.clusterId())) return false;
		ClusterEngineState state = states.get(exactIntent.clusterId());
		if (state == null || !state.epochId().equals(exactIntent.fence().epochId())
			|| state.generation() < exactIntent.generation()) return false;
		ProductionEffect existing = state.pendingIntents().get(exactIntent.intentId());
		if (existing != null && !existing.equals(exactIntent)) return false;
		if (state.pendingIntents().values().stream().anyMatch(candidate ->
			candidate instanceof ProductionEffect.CreateDispatch dispatch
				&& dispatch.runId().equals(exactIntent.runId())
				&& !dispatch.intentId().equals(exactIntent.intentId()))) return false;
		if (existing == null) {
			try {
				state = state.withRetriedPendingIntent(exactIntent);
			} catch (IllegalArgumentException | IllegalStateException invalid) {
				return false;
			}
			states.put(state.clusterId(), state);
			LinkedHashSet<UUID> replayed = replayedPendingIntents.get(state.clusterId());
			if (replayed != null) replayed.remove(exactIntent.intentId());
		}
		return queuePendingCreateDispatch(state, exactIntent);
	}

	private boolean queuePendingCreateDispatch(ClusterEngineState state,
		ProductionEffect.CreateDispatch intent) {
		UUID clusterId = state.clusterId();
		LinkedHashSet<UUID> replayed = replayedPendingIntents.computeIfAbsent(clusterId,
			ignored -> new LinkedHashSet<>());
		replayed.retainAll(state.pendingIntents().keySet());
		if (replayed.contains(intent.intentId())) return true;
		LinkedBlockingDeque<ProductionEffect> ready = readyIntents.computeIfAbsent(clusterId,
			ignored -> new LinkedBlockingDeque<>(ClusterEngineState.MAX_PENDING_INTENTS));
		boolean alreadyQueued = ready.stream().anyMatch(candidate ->
			candidate.intentId().equals(intent.intentId()))
			|| scheduledIntents.getOrDefault(clusterId, List.of()).stream().anyMatch(candidate ->
				candidate.intentId().equals(intent.intentId()));
		if (!alreadyQueued && !ready.offerLast(intent)) return false;
		replayed.add(intent.intentId());
		return true;
	}

	/** Returns this Cluster's share of the single server-wide commit budget for one game tick. */
	public synchronized List<ProductionEffect> drainIntents(UUID clusterId, long gameTick) {
		requireRunning(); Objects.requireNonNull(clusterId, "clusterId");
		long now = System.nanoTime();
		panelMutationReservations.entrySet().removeIf(entry -> !inFlight.contains(entry.getKey())
			&& now - entry.getValue() > 0);
		pumpResults();
		if (gameTick != scheduledGameTick) scheduleTick(gameTick);
		List<ProductionEffect> drained = scheduledIntents.remove(clusterId);
		return drained == null ? List.of() : drained;
	}

	synchronized List<ProductionEffect> drainIntents(UUID clusterId) {
		return drainIntents(clusterId, ++legacyDrainTick);
	}

	public synchronized Map<UUID, ClusterEngineState> snapshot() {
		if (started && !closed) pumpResults();
		return Collections.unmodifiableMap(new LinkedHashMap<>(states));
	}

	public synchronized Optional<ClusterEngineState> snapshot(UUID clusterId) {
		requireRunning(); Objects.requireNonNull(clusterId, "clusterId"); pumpResults();
		return Optional.ofNullable(states.get(clusterId));
	}

	/**
	 * Publishes a completed worker snapshot without immediately scheduling its continuation.
	 * A server-authoritative CAS command can therefore validate and mutate that exact snapshot.
	 */
	public synchronized Optional<ClusterEngineState> snapshotForServerMutation(UUID clusterId) {
		requireRunning(); Objects.requireNonNull(clusterId, "clusterId"); pumpResults(false);
		if (!inFlight.contains(clusterId)) {
			panelMutationReservations.remove(clusterId);
			return Optional.ofNullable(states.get(clusterId));
		}
		reservePanelMutationWindow(clusterId);
		return Optional.ofNullable(states.get(clusterId));
	}

	/** Releases a Panel CAS reservation after its complete asynchronous command is durable. */
	public synchronized void releasePanelMutationReservation(UUID clusterId) {
		requireRunning();
		panelMutationReservations.remove(Objects.requireNonNull(clusterId, "clusterId"));
		dispatch(clusterId);
	}

	private void reservePanelMutationWindow(UUID clusterId) {
		long reservationNanos = Math.addExact(
			Math.multiplyExact((long) config.workerCadenceMillis(), 1_000_000L), 100_000_000L);
		long now = System.nanoTime();
		panelMutationReservations.put(clusterId, Long.MAX_VALUE - now < reservationNanos
			? Long.MAX_VALUE : now + reservationNanos);
	}

	/** Panel projection that fences redispatch only when a worker already owns the snapshot. */
	public synchronized Optional<ClusterEngineState> snapshotForPanelView(UUID clusterId) {
		requireRunning(); Objects.requireNonNull(clusterId, "clusterId"); pumpResults(false);
		if (inFlight.contains(clusterId)) reservePanelMutationWindow(clusterId);
		return Optional.ofNullable(states.get(clusterId));
	}

	/** Frozen-runtime Panel projection: a pure state read that never drains worker results or reserves work. */
	public synchronized Optional<ClusterEngineState> peekSnapshotForPanelView(UUID clusterId) {
		requireRunning();
		return Optional.ofNullable(states.get(Objects.requireNonNull(clusterId, "clusterId")));
	}

	/** Read-only worker ownership diagnostic; it never advances or mutates a Cluster. */
	public synchronized boolean workerInFlight(UUID clusterId) {
		requireRunning(); pumpResults();
		return inFlight.contains(Objects.requireNonNull(clusterId));
	}

	public synchronized String panelAdmissionStatus(UUID clusterId, UUID epochId,
		long expectedGeneration) {
		requireRunning(); pumpResults(false);
		ClusterEngineState state = states.get(Objects.requireNonNull(clusterId));
		if (state == null) return "STATE_MISSING";
		if (inFlight.contains(clusterId)) return inFlightDiagnostic(clusterId);
		if (!state.epochId().equals(Objects.requireNonNull(epochId))) return "EPOCH_MISMATCH";
		if (state.generation() != expectedGeneration) return "GENERATION_MISMATCH";
		if (state.goals().size() >= ClusterEngineState.MAX_GOALS) return "GOAL_CAPACITY";
		if (state.nextSubmissionSequence() == Long.MAX_VALUE) return "SEQUENCE_EXHAUSTED";
		if (state.generation() == Long.MAX_VALUE) return "GENERATION_EXHAUSTED";
		return "READY";
	}

	/** Exact condition recorded by the most recent Panel admission attempt for this Cluster. */
	public synchronized String lastPanelAdmissionDiagnostic(UUID clusterId) {
		requireRunning();
		return panelAdmissionDiagnostics.getOrDefault(Objects.requireNonNull(clusterId), "NONE");
	}

	public int factCaptureLimit() { return config.pendingRunCapacity(); }

	/** The single authoritative pending Agent-run queue owned by this server runtime. */
	public synchronized AgentPendingRunQueue pendingAgentRuns() {
		requireRunning();
		return pendingAgentRuns;
	}

	/** Reserves the Agent's one durable Alchemist-backed process without inventing a Goal. */
	public synchronized Optional<AgentProcess.ResidentSlot> acquireAgentResidentSlot(UUID agentId,
		UUID ownerClusterId) {
		requireRunning(); agentId = Objects.requireNonNull(agentId, "agentId");
		ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
		pumpResults(); ClusterEngineState state = states.get(ownerClusterId);
		if (state == null || inFlight.contains(ownerClusterId)) return Optional.empty();
		UUID processId = agentResidentIdentity(agentId, ownerClusterId);
		ProcessRecord existing = state.processes().get(processId);
		if (existing != null) return existing.kind() == ProcessKind.AGENT
			&& existing.goalId().equals(agentId)
			? Optional.of(agentSlot(state, existing)) : Optional.empty();
		if (state.processes().size() >= ClusterEngineState.MAX_PROCESSES
			|| state.generation() == Long.MAX_VALUE) return Optional.empty();
		Set<Map.Entry<UUID, Integer>> occupied = new HashSet<>();
		state.processes().values().stream().filter(process -> process.state() != ProcessState.TERMINAL)
			.forEach(process -> occupied.add(Map.entry(process.owningComputerId(), process.owningSlot())));
		ClusterEngineState.MemberSnapshot selected = null; int selectedSlot = -1, selectedIndex = -1;
		for (int offset = 0; offset < state.members().size() && selected == null; offset++) {
			int index = (state.memberCursor() + offset) % state.members().size();
			ClusterEngineState.MemberSnapshot member = state.members().get(index);
			if (!member.loaded() || !member.profile().supports(ProductionCapability.ALCHEMIST)) continue;
			for (int slot = 0; slot < member.profile().processSlots(); slot++)
				if (!occupied.contains(Map.entry(member.computerId(), slot))) {
					selected = member; selectedSlot = slot; selectedIndex = index; break;
				}
		}
		if (selected == null) return Optional.empty();
		ProcessRecord process = new ProcessRecord(processId, agentId, selected.computerId(),
			selectedSlot, selected.profile(), ProcessKind.AGENT, ProcessState.RUNNABLE, null, 0,
			Map.of());
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.put(processId, process);
		states.put(ownerClusterId, stateWithProcesses(state, processes,
			(selectedIndex + 1) % state.members().size()));
		return Optional.of(agentSlot(states.get(ownerClusterId), process));
	}

	public synchronized boolean agentResidentSlotValid(UUID agentId, UUID ownerClusterId,
		AgentProcess.ResidentSlot slot) {
		requireRunning(); Objects.requireNonNull(agentId); Objects.requireNonNull(ownerClusterId);
		Objects.requireNonNull(slot); pumpResults(); ClusterEngineState state = states.get(ownerClusterId);
		if (state == null) return false;
		ProcessRecord process = state.processes().get(agentResidentIdentity(agentId, ownerClusterId));
		return process != null && process.kind() == ProcessKind.AGENT && process.goalId().equals(agentId)
			&& slot.equals(agentSlot(state, process));
	}

	public synchronized boolean releaseAgentResidentSlot(UUID agentId, UUID ownerClusterId,
		AgentProcess.ResidentSlot slot) {
		requireRunning(); Objects.requireNonNull(agentId); Objects.requireNonNull(ownerClusterId);
		Objects.requireNonNull(slot); pumpResults(); ClusterEngineState state = states.get(ownerClusterId);
		if (state == null || inFlight.contains(ownerClusterId) || state.generation() == Long.MAX_VALUE)
			return false;
		UUID processId = agentResidentIdentity(agentId, ownerClusterId);
		ProcessRecord process = state.processes().get(processId);
		if (process == null || process.kind() != ProcessKind.AGENT || !process.goalId().equals(agentId)
			|| !slot.equals(agentSlot(state, process))) return false;
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.remove(processId); states.put(ownerClusterId,
			stateWithProcesses(state, processes, state.memberCursor()));
		dispatch(ownerClusterId); return true;
	}

	private static AgentProcess.ResidentSlot agentSlot(ClusterEngineState state,
		ProcessRecord process) {
		return new AgentProcess.ResidentSlot(process.processId(), process.owningComputerId(),
			process.owningSlot(), state.epochId(), process.generation(), ProcessKind.AGENT, true);
	}

	private static UUID agentResidentIdentity(UUID agentId, UUID clusterId) {
		return priorityIdentity("agent-resident-process", clusterId, agentId, agentId);
	}

	/** Allocates one real finite Computer slot for a watched or pending output address. */
	public synchronized Optional<ProductionOutputContract.ProcessLease> acquireOutputProcess(
		UUID clusterId, UUID runId, UUID goalId, CreateRouteKey address,
		ProductionOutputContract.LeasePurpose purpose) {
		requireRunning(); Objects.requireNonNull(clusterId); Objects.requireNonNull(runId);
		Objects.requireNonNull(goalId); Objects.requireNonNull(address); Objects.requireNonNull(purpose);
		pumpResults(); ClusterEngineState state = states.get(clusterId);
		if (state == null || inFlight.contains(clusterId) || state.generation() == Long.MAX_VALUE
			|| !state.goals().containsKey(goalId)) return Optional.empty();
		UUID processId = outputProcessIdentity(clusterId, runId, address, purpose);
		ProcessRecord existing = state.processes().get(processId);
		if (existing != null) return existing.goalId().equals(goalId)
			&& existing.kind() == ProcessKind.OUTPUT_DESTINATION
			? Optional.of(new ProductionOutputContract.ProcessLease(processId, address)) : Optional.empty();
		if (state.processes().size() >= ClusterEngineState.MAX_PROCESSES) return Optional.empty();
		Set<Map.Entry<UUID, Integer>> occupied = new HashSet<>();
		state.processes().values().stream().filter(process -> process.state() != ProcessState.TERMINAL)
			.forEach(process -> occupied.add(Map.entry(process.owningComputerId(), process.owningSlot())));
		ClusterEngineState.MemberSnapshot selected = null; int slot = -1, selectedIndex = -1;
		for (int offset = 0; offset < state.members().size() && selected == null; offset++) {
			int index = (state.memberCursor() + offset) % state.members().size();
			ClusterEngineState.MemberSnapshot member = state.members().get(index);
			if (!member.loaded()) continue;
			for (int candidate = 0; candidate < member.profile().processSlots(); candidate++)
				if (!occupied.contains(Map.entry(member.computerId(), candidate))) {
					selected = member; slot = candidate; selectedIndex = index; break;
				}
		}
		if (selected == null) return Optional.empty();
		ProcessRecord process = new ProcessRecord(processId, goalId, selected.computerId(), slot,
			selected.profile(), ProcessKind.OUTPUT_DESTINATION, ProcessState.RUNNABLE, null, 0,
			Map.of());
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.put(processId, process);
		states.put(clusterId, stateWithProcesses(state, processes,
			(selectedIndex + 1) % state.members().size()));
		return Optional.of(new ProductionOutputContract.ProcessLease(processId, address));
	}

	public synchronized boolean outputProcessValid(UUID clusterId, UUID runId, UUID goalId,
		ProductionOutputContract.ProcessLease lease, ProductionOutputContract.LeasePurpose purpose) {
		requireRunning(); Objects.requireNonNull(lease); pumpResults();
		ClusterEngineState state = states.get(Objects.requireNonNull(clusterId));
		if (state == null) return false;
		UUID expected = outputProcessIdentity(clusterId, Objects.requireNonNull(runId),
			lease.address(), Objects.requireNonNull(purpose));
		ProcessRecord process = state.processes().get(expected);
		return lease.processId().equals(expected) && process != null
			&& process.goalId().equals(Objects.requireNonNull(goalId))
			&& process.kind() == ProcessKind.OUTPUT_DESTINATION
			&& process.state() != ProcessState.TERMINAL;
	}

	public synchronized boolean releaseOutputProcess(UUID clusterId, UUID runId, UUID goalId,
		ProductionOutputContract.ProcessLease lease, ProductionOutputContract.LeasePurpose purpose) {
		requireRunning(); Objects.requireNonNull(clusterId); Objects.requireNonNull(runId);
		Objects.requireNonNull(goalId); Objects.requireNonNull(lease); Objects.requireNonNull(purpose);
		pumpResults(); ClusterEngineState state = states.get(clusterId);
		if (state == null || inFlight.contains(clusterId) || state.generation() == Long.MAX_VALUE)
			return false;
		UUID expected = outputProcessIdentity(clusterId, runId, lease.address(), purpose);
		ProcessRecord process = state.processes().get(expected);
		if (!lease.processId().equals(expected) || process == null || !process.goalId().equals(goalId)
			|| process.kind() != ProcessKind.OUTPUT_DESTINATION) return false;
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.remove(expected);
		states.put(clusterId, stateWithProcesses(state, processes, state.memberCursor()));
		dispatch(clusterId); return true;
	}

	private static UUID outputProcessIdentity(UUID clusterId, UUID runId, CreateRouteKey address,
		ProductionOutputContract.LeasePurpose purpose) {
		return UUID.nameUUIDFromBytes(("output-process:" + clusterId + ":" + runId + ":"
			+ purpose + ":" + address.logisticsNetworkId() + ":" + address.address())
			.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Reserves one concrete ordinary Computer slot for a configured external listener.  The returned
	 * process is a real ClusterEngineState ProcessRecord, so ordinary scheduling sees the occupancy.
	 */
	public synchronized Optional<ExternalListenerProcessReservation> reserveExternalListenerProcess(
		UUID clusterId, UUID epochId, UUID computerId, UUID leaseId) {
		requireRunning(); clusterId = Objects.requireNonNull(clusterId, "cluster id");
		epochId = Objects.requireNonNull(epochId, "epoch id");
		computerId = Objects.requireNonNull(computerId, "computer id");
		leaseId = Objects.requireNonNull(leaseId, "lease id");
		pumpResults(false);
		ClusterEngineState state = states.get(clusterId);
		if (state == null || !state.epochId().equals(epochId)) return Optional.empty();
		ClusterEngineState.MemberSnapshot member = state.member(computerId);
		if (member == null || !member.loaded()) return Optional.empty();
		UUID processId = UUID.nameUUIDFromBytes(("external-listener:" + clusterId + ":" + epochId
			+ ":" + computerId + ":" + leaseId).getBytes(StandardCharsets.UTF_8));
		ProcessRecord existing = state.processes().get(processId);
		if (existing != null) {
			if (existing.kind() != ProcessKind.RADIO_LISTENER
				|| !existing.goalId().equals(leaseId)
				|| !existing.owningComputerId().equals(computerId)) return Optional.empty();
			ExternalListenerProcessReservation reclaimed = new ExternalListenerProcessReservation(
				processId, clusterId, epochId, computerId, leaseId, existing.owningSlot());
			pendingExternalListenerReleases.remove(processId, reclaimed);
			return Optional.of(reclaimed);
		}
		if (inFlight.contains(clusterId) || state.processes().size() >= ClusterEngineState.MAX_PROCESSES
			|| state.generation() == Long.MAX_VALUE) return Optional.empty();
		UUID exactComputerId = computerId;
		Set<Integer> occupied = new HashSet<>();
		state.processes().values().stream().filter(process -> process.state() != ProcessState.TERMINAL
			&& process.owningComputerId().equals(exactComputerId)).map(ProcessRecord::owningSlot)
			.forEach(occupied::add);
		int slot = -1;
		for (int candidate = 0; candidate < member.profile().processSlots(); candidate++)
			if (!occupied.contains(candidate)) { slot = candidate; break; }
		if (slot < 0) return Optional.empty();
		ProcessRecord process = new ProcessRecord(processId, leaseId, computerId, slot,
			member.profile(), ProcessKind.RADIO_LISTENER, ProcessState.RUNNABLE, null, 0, Map.of());
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.put(processId, process);
		states.put(clusterId, stateWithProcesses(state, processes, state.memberCursor()));
		dispatch(clusterId);
		return Optional.of(new ExternalListenerProcessReservation(processId, clusterId, epochId,
			computerId, leaseId, slot));
	}

	/** Releases only the exact listener ProcessRecord/lease reserved by this owner. */
	public synchronized boolean releaseExternalListenerProcess(ExternalListenerProcessReservation reservation) {
		requireRunning(); reservation = Objects.requireNonNull(reservation, "listener reservation");
		pumpResults(false);
		ClusterEngineState state = states.get(reservation.clusterId());
		if (state == null || !state.epochId().equals(reservation.epochId())) return false;
		if (inFlight.contains(reservation.clusterId())) {
			/* Do not lose an unload while a worker owns the immutable snapshot. */
			pendingExternalListenerReleases.putIfAbsent(reservation.processId(), reservation);
			return true;
		}
		return releaseExternalListenerProcessNow(reservation);
	}

	private boolean releaseExternalListenerProcessNow(ExternalListenerProcessReservation reservation) {
		ClusterEngineState state = states.get(reservation.clusterId());
		if (state == null || !state.epochId().equals(reservation.epochId())) return false;
		ProcessRecord current = state.processes().get(reservation.processId());
		if (!matchesExternalListenerReservation(current, reservation)) return false;
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.remove(reservation.processId());
		states.put(reservation.clusterId(), stateWithProcesses(state, processes, state.memberCursor()));
		dispatch(reservation.clusterId());
		return true;
	}

	private void drainExternalListenerReleases(UUID clusterId) {
		if (inFlight.contains(clusterId)) return;
		ClusterEngineState state = states.get(clusterId);
		if (state == null) return;
		/* One immutable replacement prevents a fresh worker snapshot from reviving a later entry. */
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		boolean changed = false;
		for (Iterator<Map.Entry<UUID, ExternalListenerProcessReservation>> iterator =
			pendingExternalListenerReleases.entrySet().iterator(); iterator.hasNext();) {
			ExternalListenerProcessReservation reservation = iterator.next().getValue();
			if (!reservation.clusterId().equals(clusterId)) continue;
			if (state.epochId().equals(reservation.epochId()) && matchesExternalListenerReservation(
				processes.get(reservation.processId()), reservation)) {
				processes.remove(reservation.processId());
				changed = true;
			}
			/* An exact process cannot become valid again after its owner has released it. */
			iterator.remove();
		}
		if (changed) states.put(clusterId, stateWithProcesses(state, processes, state.memberCursor()));
	}

	private static boolean matchesExternalListenerReservation(ProcessRecord current,
		ExternalListenerProcessReservation reservation) {
		return current != null && current.kind() == ProcessKind.RADIO_LISTENER
			&& current.goalId().equals(reservation.leaseId())
			&& current.owningComputerId().equals(reservation.computerId())
			&& current.owningSlot() == reservation.slot();
	}

	/** Runtime projection for configured entries that are active or waiting on a real ordinary slot. */
	public synchronized void projectExternalListeners(UUID clusterId, UUID epochId, UUID computerId,
		List<com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService.ListenerProjection> projection) {
		requireRunning(); clusterId = Objects.requireNonNull(clusterId, "cluster id");
		epochId = Objects.requireNonNull(epochId, "epoch id"); computerId = Objects.requireNonNull(computerId, "computer id");
		projection = List.copyOf(Objects.requireNonNull(projection, "listener projection"));
		ClusterEngineState state = states.get(clusterId);
		if (state == null || !state.epochId().equals(epochId)) return;
		ExternalListenerClusterProjection prior = externalListenerProjections.get(clusterId);
		LinkedHashMap<UUID, List<com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService.ListenerProjection>> listeners =
			new LinkedHashMap<>(prior == null || !prior.epochId().equals(epochId) ? Map.of() : prior.listeners());
		if (projection.isEmpty()) listeners.remove(computerId); else listeners.put(computerId, projection);
		externalListenerProjections.put(clusterId, new ExternalListenerClusterProjection(epochId, listeners));
	}

	public synchronized Optional<ExternalListenerClusterProjection> externalListenerProjection(UUID clusterId) {
		requireRunning(); return Optional.ofNullable(externalListenerProjections.get(
			Objects.requireNonNull(clusterId, "cluster id")));
	}

	public record ExternalListenerProcessReservation(UUID processId, UUID clusterId, UUID epochId,
		UUID computerId, UUID leaseId, int slot) {
		public ExternalListenerProcessReservation {
			processId = Objects.requireNonNull(processId, "process id"); clusterId = Objects.requireNonNull(clusterId, "cluster id");
			epochId = Objects.requireNonNull(epochId, "epoch id"); computerId = Objects.requireNonNull(computerId, "computer id");
			leaseId = Objects.requireNonNull(leaseId, "lease id"); if (slot < 0) throw new IllegalArgumentException("negative slot");
		}
	}

	public record ExternalListenerClusterProjection(UUID epochId,
		Map<UUID, List<com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService.ListenerProjection>> listeners) {
		public ExternalListenerClusterProjection {
			epochId = Objects.requireNonNull(epochId, "epoch id");
			listeners = Map.copyOf(Objects.requireNonNull(listeners, "listeners"));
		}
	}
	private static ClusterEngineState stateWithProcesses(ClusterEngineState state,
		Map<UUID, ProcessRecord> processes, int memberCursor) {
		int processCursor = processes.isEmpty() ? 0 : Math.min(state.processCursor(), processes.size() - 1);
		return new ClusterEngineState(state.clusterId(), state.epochId(), state.generation() + 1,
			state.nextSubmissionSequence(), state.nextRouteSelectionSequence(), memberCursor,
			processCursor, state.goals(), state.submissionSequences(), state.requestedKinds(),
			processes, state.members(), state.pendingIntents(), state.uncertainIntentIds(),
			state.goalScanOffset(), state.goalScanCandidate(), state.routeCursor(),
			state.coordinationAssociations(), state.priorityLeaseAssociations());
	}

	private static ClusterEngineState stateWithProcessTables(ClusterEngineState state,
		Map<UUID, ProcessRecord> processes,
		Map<UUID, CoordinationAssociation> coordinationAssociations, int memberCursor) {
		int processCursor = processes.isEmpty() ? 0
			: Math.min(state.processCursor(), processes.size() - 1);
		return new ClusterEngineState(state.clusterId(), state.epochId(), state.generation() + 1,
			state.nextSubmissionSequence(), state.nextRouteSelectionSequence(), memberCursor,
			processCursor, state.goals(), state.submissionSequences(), state.requestedKinds(),
			processes, state.members(), state.pendingIntents(), state.uncertainIntentIds(),
			state.goalScanOffset(), state.goalScanCandidate(), state.routeCursor(),
			coordinationAssociations, state.priorityLeaseAssociations());
	}

	private static ClusterEngineState stateAfterSpecialProcessRelease(ClusterEngineState state,
		Map<UUID, ProcessRecord> processes,
		Map<UUID, CoordinationAssociation> coordinationAssociations) {
		LinkedHashMap<UUID, GoalRecord> goals = new LinkedHashMap<>();
		state.goals().forEach((goalId, goal) -> goals.put(goalId,
			goal.state() == GoalState.WAITING && goal.waitReason() == WaitReason.PROCESS
				? new GoalRecord(goal.goalId(), goal.owningClusterId(), goal.submission(),
					goal.deliveryRoute(), goal.policy(), GoalState.RUNNABLE, null)
				: goal));
		int processCursor = processes.isEmpty() ? 0
			: Math.min(state.processCursor(), processes.size() - 1);
		return new ClusterEngineState(state.clusterId(), state.epochId(), state.generation() + 1,
			state.nextSubmissionSequence(), state.nextRouteSelectionSequence(), state.memberCursor(),
			processCursor, goals, state.submissionSequences(), state.requestedKinds(), processes,
			state.members(), state.pendingIntents(), state.uncertainIntentIds(), 0, null,
			state.routeCursor(), coordinationAssociations, state.priorityLeaseAssociations());
	}

	/**
	 * Atomically chooses and installs one exact receiver-owned slot for this lease. The returned
	 * allocation is therefore already visible to ordinary scheduling, never a speculative hint.
	 */
	public synchronized Optional<RemoteComputationAllocation> acquireRemoteComputationProcess(
		RemoteProcessLease lease, UUID receiverMemberId) {
		requireRunning(); lease = Objects.requireNonNull(lease, "lease");
		receiverMemberId = Objects.requireNonNull(receiverMemberId, "receiverMemberId");
		UUID exactReceiverMemberId = receiverMemberId;
		pumpResults(false);
		ClusterEngineState state = states.get(lease.receiverClusterId());
		if (state == null) return Optional.empty();
		UUID processId = remoteComputationProcessIdentity(lease, exactReceiverMemberId);
		ProcessRecord existing = state.processes().get(processId);
		if (existing != null) return matchesRemoteComputationProcess(existing, lease,
			exactReceiverMemberId, existing.owningSlot())
			? Optional.of(new RemoteComputationAllocation(processId, exactReceiverMemberId,
				existing.owningSlot())) : Optional.empty();
		if (inFlight.contains(state.clusterId())
			|| state.processes().size() >= ClusterEngineState.MAX_PROCESSES
			|| state.generation() == Long.MAX_VALUE) return Optional.empty();
		ClusterEngineState.MemberSnapshot member = state.member(exactReceiverMemberId);
		if (member == null || !member.loaded()
			|| !member.profile().supports(lease.requiredCapability())) return Optional.empty();
		Set<Integer> occupied = new HashSet<>();
		state.processes().values().stream()
			.filter(process -> process.state() != ProcessState.TERMINAL
				&& process.owningComputerId().equals(exactReceiverMemberId))
			.map(ProcessRecord::owningSlot).forEach(occupied::add);
		int owningSlot = -1;
		for (int candidate = 0; candidate < member.profile().processSlots(); candidate++) {
			if (!occupied.contains(candidate)) { owningSlot = candidate; break; }
		}
		if (owningSlot < 0) return Optional.empty();
		ProcessRecord process = new ProcessRecord(processId, lease.sourceGoalId(),
			exactReceiverMemberId, owningSlot, member.profile(), remoteComputationProcessKind(lease),
			ProcessState.RUNNABLE, null, lease.receiverGeneration(), Map.of());
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.put(processId, process);
		states.put(state.clusterId(), stateWithProcesses(state, processes, state.memberCursor()));
		return Optional.of(new RemoteComputationAllocation(processId, exactReceiverMemberId,
			owningSlot));
	}

	private static UUID remoteComputationProcessIdentity(RemoteProcessLease lease,
		UUID receiverMemberId) {
		return UUID.nameUUIDFromBytes(("remote-computation-process/" + lease.leaseId() + "/"
			+ lease.receiverClusterId() + "/" + receiverMemberId)
			.getBytes(StandardCharsets.UTF_8));
	}

	public record RemoteComputationAllocation(UUID processId, UUID receiverMemberId,
		int owningSlot) {
		public RemoteComputationAllocation {
			processId = Objects.requireNonNull(processId, "processId");
			receiverMemberId = Objects.requireNonNull(receiverMemberId, "receiverMemberId");
			if (owningSlot < 0) throw new IllegalArgumentException("negative remote process slot");
		}
	}

	/** Installs the receiver-owned process at the exact durable slot selected for the lease. */
	public synchronized boolean ensureRemoteComputationProcess(UUID processId,
		RemoteProcessLease lease, UUID receiverMemberId, int owningSlot) {
		requireRunning(); processId = Objects.requireNonNull(processId, "processId");
		lease = Objects.requireNonNull(lease, "lease");
		receiverMemberId = Objects.requireNonNull(receiverMemberId, "receiverMemberId");
		UUID exactReceiverMemberId = receiverMemberId;
		if (owningSlot < 0) throw new IllegalArgumentException("negative remote process slot");
		pumpResults(false);
		ClusterEngineState state = states.get(lease.receiverClusterId());
		if (state == null) return false;
		ProcessRecord existing = state.processes().get(processId);
		if (existing != null) return matchesRemoteComputationProcess(existing, lease,
			receiverMemberId, owningSlot);
		if (inFlight.contains(state.clusterId())
			|| state.processes().size() >= ClusterEngineState.MAX_PROCESSES
			|| state.generation() == Long.MAX_VALUE) return false;
		ClusterEngineState.MemberSnapshot member = state.member(receiverMemberId);
		if (member == null || !member.loaded() || owningSlot >= member.profile().processSlots()
			|| !member.profile().supports(lease.requiredCapability())) return false;
		boolean occupied = state.processes().values().stream()
			.filter(process -> process.state() != ProcessState.TERMINAL)
			.anyMatch(process -> process.owningComputerId().equals(exactReceiverMemberId)
				&& process.owningSlot() == owningSlot);
		if (occupied) return false;
		ProcessRecord process = new ProcessRecord(processId, lease.sourceGoalId(),
			receiverMemberId, owningSlot, member.profile(), remoteComputationProcessKind(lease),
			ProcessState.RUNNABLE, null, lease.receiverGeneration(), Map.of());
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.put(processId, process);
		states.put(state.clusterId(), stateWithProcesses(state, processes,
			state.memberCursor()));
		return true;
	}

	public synchronized boolean remoteComputationProcessValid(UUID processId,
		RemoteProcessLease lease, UUID receiverMemberId, int owningSlot) {
		requireRunning(); Objects.requireNonNull(processId); Objects.requireNonNull(lease);
		Objects.requireNonNull(receiverMemberId); pumpResults(false);
		ClusterEngineState state = states.get(lease.receiverClusterId());
		ProcessRecord process = state == null ? null : state.processes().get(processId);
		return process != null && matchesRemoteComputationProcess(process, lease,
			receiverMemberId, owningSlot);
	}

	/** Releases only this exact receiver allocation; replay after a durable completion is safe. */
	public synchronized boolean releaseRemoteComputationProcess(UUID processId,
		RemoteProcessLease lease, UUID receiverMemberId, int owningSlot) {
		requireRunning(); Objects.requireNonNull(processId); Objects.requireNonNull(lease);
		Objects.requireNonNull(receiverMemberId); pumpResults(false);
		ClusterEngineState state = states.get(lease.receiverClusterId());
		if (state == null || inFlight.contains(lease.receiverClusterId())
			|| state.generation() == Long.MAX_VALUE) return false;
		ProcessRecord process = state.processes().get(processId);
		if (process == null) return true;
		if (!matchesRemoteComputationProcess(process, lease, receiverMemberId, owningSlot))
			return false;
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.remove(processId);
		states.put(state.clusterId(), stateAfterSpecialProcessRelease(state, processes,
			state.coordinationAssociations()));
		dispatch(state.clusterId());
		return true;
	}

	private static boolean matchesRemoteComputationProcess(ProcessRecord process,
		RemoteProcessLease lease, UUID receiverMemberId, int owningSlot) {
		return process.kind() == remoteComputationProcessKind(lease)
			&& process.state() != ProcessState.TERMINAL
			&& process.goalId().equals(lease.sourceGoalId())
			&& process.owningComputerId().equals(receiverMemberId)
			&& process.owningSlot() == owningSlot
			&& process.generation() == lease.receiverGeneration()
			&& process.owningComputer().supports(lease.requiredCapability());
	}

	private static ProcessKind remoteComputationProcessKind(RemoteProcessLease lease) {
		return lease.sourceClusterId().equals(lease.receiverClusterId())
			? ProcessKind.PURE_EXPANSION : ProcessKind.REMOTE_LISTENER;
	}

	/**
	 * Installs the receiver-produced revision into the source-owned parent lineage and makes its
	 * acknowledgement durable in the coordinator snapshot. Replay of the exact revision is a no-op.
	 */
	public synchronized boolean installRemoteChildRevision(RemoteProcessLease lease,
		RemoteExpansionRevision revision) {
		requireRunning();
		lease = Objects.requireNonNull(lease, "lease");
		revision = Objects.requireNonNull(revision, "revision");
		ClusterEngineState state = currentRemoteRevisionSource(lease, revision);
		if (state == null) return false;
		ProcessRecord parent = state.processes().get(lease.sourceProcessId());

		ChildExecutionRevision child = revision.childRevision();
		ProcessRecord.ChildRevisionStatus prior = parent.childRevisionStates()
			.get(child.childProcessId());
		ProcessRecord nextParent;
		if (prior == null || !prior.revision().equals(child)) {
			ProcessRecord.RevisionAcceptance accepted;
			try {
				accepted = parent.acceptChildRevision(child);
			} catch (IllegalArgumentException staleOrConflicting) {
				return false;
			}
			nextParent = accepted.parent().markChildAcknowledgementDurable(
				accepted.acknowledgement());
		} else {
			nextParent = prior.acknowledgementDurable() ? parent
				: parent.markChildAcknowledgementDurable(child.acknowledgement());
		}
		if (nextParent == parent) return true;
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.put(parent.processId(), nextParent);
		states.put(state.clusterId(), stateWithProcesses(state, processes, state.memberCursor()));
		return true;
	}

	/** Revalidates the live source and its exact durable child ACK; never installs or caches authority. */
	public synchronized boolean remoteExpansionMaterialAuthorized(RemoteProcessLease lease,
		RemoteExpansionRevision revision) {
		requireRunning();
		lease = Objects.requireNonNull(lease, "lease");
		revision = Objects.requireNonNull(revision, "revision");
		ClusterEngineState state = currentRemoteRevisionSource(lease, revision);
		if (state == null) return false;
		ProcessRecord parent = state.processes().get(lease.sourceProcessId());
		try {
			parent.authorizeIrreversibleChildBoundary(revision.childRevision().acknowledgement());
			return true;
		} catch (IllegalArgumentException | IllegalStateException rejected) {
			return false;
		}
	}

	/** Called under the service monitor by both child installation and material revalidation. */
	@Nullable
	private ClusterEngineState currentRemoteRevisionSource(RemoteProcessLease lease,
		RemoteExpansionRevision revision) {
		if (!revision.matches(lease.expansionRequest())
			|| !revision.parentProcessId().equals(lease.sourceProcessId())) return null;
		pumpResults(false);
		ClusterEngineState state = states.get(lease.sourceClusterId());
		if (state == null || inFlight.contains(state.clusterId())
			|| state.generation() == Long.MAX_VALUE) return null;
		ProcessRecord parent = state.processes().get(lease.sourceProcessId());
		GoalRecord goal = state.goals().get(lease.sourceGoalId());
		if (parent == null || goal == null || goal.state() == GoalState.TERMINAL
			|| !parent.matchesLiveGoalParent(lease.sourceGoalId(),
				lease.expansionRequest().parentProcessGeneration())) return null;
		return state;
	}

	/** Enqueues receiver computation without allowing the worker to mutate the receiver snapshot. */
	public synchronized boolean submitRemotePureWork(
		FactoryComputeWorker.RemotePureWork work) {
		requireRunning(); work = Objects.requireNonNull(work, "work"); pumpResults();
		RemotePureKey key = RemotePureKey.from(work);
		RemotePureCompletion ready = readyRemotePureResults.get(key);
		if (ready != null) return ready.work().equals(work);
		FactoryComputeWorker.RemotePureWork submitted = remotePureInFlight.get(key);
		if (submitted != null) return submitted.equals(work);
		ClusterEngineState state = states.get(work.receiverClusterId());
		ProcessRecord process = state == null ? null : state.processes().get(work.processId());
		if (state == null || process == null || inFlight.contains(work.receiverClusterId())
			|| process.kind() != ProcessKind.REMOTE_LISTENER
				&& process.kind() != ProcessKind.PURE_EXPANSION
			|| process.state() == ProcessState.TERMINAL
			|| !process.owningComputerId().equals(work.receiverMemberId())
			|| process.owningSlot() != work.owningSlot()
			|| process.generation() != work.receiverGeneration()
			|| !process.owningComputer().supports(work.requiredCapability())) return false;
		if (!worker.trySubmitRemote(state, work)) return false;
		remotePureInFlight.put(key, work);
		return true;
	}

	public synchronized Optional<FactoryComputeWorker.RemotePureResult> drainRemotePureResult(
		FactoryComputeWorker.RemotePureWork expected) {
		requireRunning(); expected = Objects.requireNonNull(expected, "expected"); pumpResults();
		RemotePureCompletion completed = readyRemotePureResults.get(RemotePureKey.from(expected));
		if (completed == null || !completed.work().equals(expected)) return Optional.empty();
		readyRemotePureResults.remove(RemotePureKey.from(expected));
		return Optional.of(completed.result());
	}

	/**
	 * Clears only pure-worker artifacts owned by the exact terminal lease fence. A completion that
	 * arrives after removal finds no submitted owner in {@link #pumpResults()} and is discarded.
	 */
	public synchronized boolean retireRemotePureArtifacts(RemoteProcessLease lease) {
		requireRunning();
		lease = Objects.requireNonNull(lease, "lease");
		pumpResults(false);
		UUID leaseId = lease.leaseId();
		UUID receiverClusterId = lease.receiverClusterId();
		long revision = lease.revisionFence();
		boolean removedInFlight = remotePureInFlight.entrySet().removeIf(entry -> {
			RemotePureKey key = entry.getKey();
			return key.leaseId().equals(leaseId)
				&& key.receiverClusterId().equals(receiverClusterId)
				&& key.revision() == revision;
		});
		boolean removedReady = readyRemotePureResults.entrySet().removeIf(entry -> {
			RemotePureKey key = entry.getKey();
			return key.leaseId().equals(leaseId)
				&& key.receiverClusterId().equals(receiverClusterId)
				&& key.revision() == revision;
		});
		return removedInFlight || removedReady;
	}

	/** Server-authoritative Factory Panel admission; all ownership and CAS facts are rechecked here. */
	public synchronized Optional<UUID> submitPanelGoal(UUID clusterId, UUID epochId,
		long expectedGeneration, UUID patternId, CreateRouteKey deliveryRoute, long multiplier,
		InputMode mode, int priority) {
		requireRunning(); Objects.requireNonNull(clusterId); Objects.requireNonNull(epochId);
		Objects.requireNonNull(patternId); Objects.requireNonNull(deliveryRoute); Objects.requireNonNull(mode);
		ClusterEngineState state = states.get(clusterId);
		String rejected = panelAdmissionRejection(state, clusterId, epochId, expectedGeneration,
			multiplier);
		if (rejected != null) {
			panelAdmissionDiagnostics.put(clusterId, rejected);
			if (!rejected.startsWith("WORKER_IN_FLIGHT")) panelMutationReservations.remove(clusterId);
			return Optional.empty();
		}
		UUID goalId = UUID.randomUUID();
		return installPanelGoal(state, goalId, patternId, deliveryRoute, multiplier, mode, priority);
	}

	/**
	 * Idempotent half of durable asynchronous Panel admission. The caller has already journalled
	 * the complete command, selected Pattern, frozen plan and observation. It may therefore retry
	 * this exact Goal identity after a worker boundary or server reload without duplicating it.
	 */
	public synchronized Optional<UUID> ensurePanelGoal(UUID goalId, UUID clusterId, UUID epochId,
		UUID patternId, CreateRouteKey deliveryRoute, long multiplier, InputMode mode, int priority) {
		requireRunning(); Objects.requireNonNull(goalId); Objects.requireNonNull(clusterId);
		Objects.requireNonNull(epochId); Objects.requireNonNull(patternId);
		Objects.requireNonNull(deliveryRoute); Objects.requireNonNull(mode);
		pumpResults(false);
		ClusterEngineState state = states.get(clusterId);
		GoalRecord existing = state == null ? null : state.goals().get(goalId);
		if (existing != null) {
			boolean exact = existing.owningClusterId().equals(clusterId)
				&& existing.submission().patternId().equals(patternId)
				&& existing.submission().multiplier() == multiplier
				&& existing.submission().inputMode() == mode
				&& existing.deliveryRoute().equals(deliveryRoute)
				&& existing.policy().priority() == priority;
			panelAdmissionDiagnostics.put(clusterId, exact ? "ACCEPTED_IDEMPOTENT"
				: "GOAL_ID_CONFLICT");
			return exact ? Optional.of(goalId) : Optional.empty();
		}
		String rejected = panelAdmissionRejection(state, clusterId, epochId,
			state == null ? -1 : state.generation(), multiplier);
		if (rejected != null) {
			panelAdmissionDiagnostics.put(clusterId, rejected);
			return Optional.empty();
		}
		return installPanelGoal(state, goalId, patternId, deliveryRoute, multiplier, mode,
			priority);
	}

	private Optional<UUID> installPanelGoal(ClusterEngineState state, UUID goalId,
		UUID patternId, CreateRouteKey deliveryRoute, long multiplier, InputMode mode,
		int priority) {
		GoalSubmission submission = new GoalSubmission(goalId, patternId, multiplier, mode);
		GoalRecord goal = new GoalRecord(goalId, state.clusterId(), submission, deliveryRoute,
			new PolicySnapshot(0, priority, false), GoalState.RUNNABLE, null);
		LinkedHashMap<UUID, GoalRecord> goals = new LinkedHashMap<>(state.goals()); goals.put(goalId, goal);
		LinkedHashMap<UUID, Long> sequences = new LinkedHashMap<>(state.submissionSequences());
		sequences.put(goalId, state.nextSubmissionSequence());
		LinkedHashMap<UUID, ProcessKind> kinds = new LinkedHashMap<>(state.requestedKinds());
		kinds.put(goalId, ProcessKind.GOAL);
		states.put(state.clusterId(), panelState(state, state.generation() + 1,
			state.nextSubmissionSequence() + 1, goals, sequences, kinds, state.processes(),
			state.coordinationAssociations(), state.priorityLeaseAssociations()));
		panelAdmissionDiagnostics.put(state.clusterId(), "ACCEPTED");
		panelMutationReservations.remove(state.clusterId());
		dispatch(state.clusterId()); return Optional.of(goalId);
	}

	private String panelAdmissionRejection(ClusterEngineState state, UUID clusterId, UUID epochId,
		long expectedGeneration, long multiplier) {
		if (state == null) return "STATE_MISSING";
		if (inFlight.contains(clusterId)) return inFlightDiagnostic(clusterId);
		if (!state.epochId().equals(epochId)) return "EPOCH_MISMATCH:" + state.epochId();
		if (state.generation() != expectedGeneration)
			return "GENERATION_MISMATCH:actual=" + state.generation() + ",expected=" + expectedGeneration;
		if (multiplier <= 0) return "INVALID_MULTIPLIER";
		if (state.goals().size() >= ClusterEngineState.MAX_GOALS) return "GOAL_CAPACITY";
		if (state.nextSubmissionSequence() == Long.MAX_VALUE) return "SEQUENCE_EXHAUSTED";
		if (state.generation() == Long.MAX_VALUE) return "GENERATION_EXHAUSTED";
		return null;
	}

	private String inFlightDiagnostic(UUID clusterId) {
		long now = System.nanoTime();
		long since = inFlightSinceNanos.getOrDefault(clusterId, now);
		Long reservation = panelMutationReservations.get(clusterId);
		return "WORKER_IN_FLIGHT:elapsed_ms=" + Math.max(0, (now - since) / 1_000_000L)
			+ ",reserved=" + (reservation != null)
			+ ",reservation_ms=" + (reservation == null ? -1
				: Math.max(0, (reservation - now) / 1_000_000L));
	}

	public synchronized boolean changePanelGoalMode(UUID clusterId, UUID epochId,
		long expectedGeneration, UUID goalId, InputMode mode) {
		requireRunning(); Objects.requireNonNull(mode);
		ClusterEngineState state = exactPanelState(clusterId, epochId, expectedGeneration);
		GoalRecord prior = state == null ? null : state.goals().get(Objects.requireNonNull(goalId));
		if (state == null || !FactoryGoalTransitionPolicy.allows(state, goalId,
			FactoryGoalTransitionPolicy.Action.CHANGE_MODE)) return false;
		GoalSubmission submission = new GoalSubmission(goalId, prior.submission().patternId(),
			prior.submission().multiplier(), mode);
		LinkedHashMap<UUID, GoalRecord> goals = new LinkedHashMap<>(state.goals());
		goals.put(goalId, new GoalRecord(goalId, clusterId, submission, prior.deliveryRoute(),
			prior.policy(), prior.state(), prior.waitReason()));
		states.put(clusterId, panelState(state, state.generation() + 1, state.nextSubmissionSequence(),
			goals, state.submissionSequences(), state.requestedKinds(), state.processes(),
			state.coordinationAssociations(), state.priorityLeaseAssociations()));
		return true;
	}

	public synchronized boolean changePanelGoalPriority(UUID clusterId, UUID epochId,
		long expectedGeneration, UUID goalId, int priority) {
		requireRunning(); ClusterEngineState state = exactPanelState(clusterId, epochId,
			expectedGeneration); GoalRecord prior = state == null ? null : state.goals().get(Objects.requireNonNull(goalId));
		if (state == null || !FactoryGoalTransitionPolicy.allows(state, goalId,
			FactoryGoalTransitionPolicy.Action.CHANGE_PRIORITY)) return false;
		LinkedHashMap<UUID, GoalRecord> goals = new LinkedHashMap<>(state.goals());
		goals.put(goalId, new GoalRecord(goalId, clusterId, prior.submission(), prior.deliveryRoute(),
			new PolicySnapshot(prior.policy().generation() + 1, priority,
				prior.policy().directRequiredOutputs()), prior.state(), prior.waitReason()));
		states.put(clusterId, panelState(state, state.generation() + 1, state.nextSubmissionSequence(),
			goals, state.submissionSequences(), state.requestedKinds(), state.processes(),
			state.coordinationAssociations(), state.priorityLeaseAssociations())); return true;
	}

	public synchronized boolean resumePanelGoal(UUID clusterId, UUID epochId,
		long expectedGeneration, UUID goalId) {
		requireRunning(); ClusterEngineState state = exactPanelState(clusterId, epochId,
			expectedGeneration); GoalRecord prior = state == null ? null : state.goals().get(Objects.requireNonNull(goalId));
		if (state == null || !FactoryGoalTransitionPolicy.allows(state, goalId,
			FactoryGoalTransitionPolicy.Action.RESUME)) return false;
		LinkedHashMap<UUID, GoalRecord> goals = new LinkedHashMap<>(state.goals());
		goals.put(goalId, new GoalRecord(goalId, clusterId, prior.submission(), prior.deliveryRoute(),
			prior.policy(), GoalState.RUNNABLE, null));
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.replaceAll((processId, process) -> process.goalId().equals(goalId)
			&& process.state() == ProcessState.WAITING
			? new ProcessRecord(process.processId(), process.goalId(), process.owningComputerId(),
				process.owningSlot(), process.owningComputer(), process.kind(), ProcessState.RUNNABLE,
				null, process.generation(), process.childRevisionStates()) : process);
		states.put(clusterId, panelState(state, state.generation() + 1, state.nextSubmissionSequence(),
			goals, state.submissionSequences(), state.requestedKinds(), processes,
			state.coordinationAssociations(), state.priorityLeaseAssociations())); dispatch(clusterId); return true;
	}

	/** Resumes both halves of a serial production Goal after one exact Run seals output. */
	public synchronized boolean resumePanelGoalAfterOutputDelivery(UUID clusterId, UUID epochId,
		long expectedGeneration, UUID goalId) {
		requireRunning(); ClusterEngineState state = exactPanelState(clusterId, epochId,
			expectedGeneration); GoalRecord prior = state == null ? null
			: state.goals().get(Objects.requireNonNull(goalId));
		if (prior == null || prior.state() != GoalState.WAITING
			|| prior.waitReason() != WaitReason.OUTPUT_DELIVERY) return false;
		LinkedHashMap<UUID, GoalRecord> goals = new LinkedHashMap<>(state.goals());
		goals.put(goalId, new GoalRecord(goalId, clusterId, prior.submission(), prior.deliveryRoute(),
			prior.policy(), GoalState.RUNNABLE, null));
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.replaceAll((processId, process) -> process.goalId().equals(goalId)
			&& process.state() == ProcessState.WAITING
			&& process.waitReason() == WaitReason.OUTPUT_DELIVERY
			? new ProcessRecord(process.processId(), process.goalId(), process.owningComputerId(),
				process.owningSlot(), process.owningComputer(), process.kind(), ProcessState.RUNNABLE,
				null, process.generation(), process.childRevisionStates()) : process);
		states.put(clusterId, panelState(state, state.generation() + 1, state.nextSubmissionSequence(),
			goals, state.submissionSequences(), state.requestedKinds(), processes,
			state.coordinationAssociations(), state.priorityLeaseAssociations()));
		return true;
	}

	public synchronized boolean cancelPanelGoal(UUID clusterId, UUID epochId,
		long expectedGeneration, UUID goalId) {
		requireRunning(); ClusterEngineState state = exactPanelState(clusterId, epochId,
			expectedGeneration); GoalRecord prior = state == null ? null : state.goals().get(Objects.requireNonNull(goalId));
		if (state == null || !FactoryGoalTransitionPolicy.allows(state, goalId,
			FactoryGoalTransitionPolicy.Action.CANCEL)) return false;
		LinkedHashMap<UUID, GoalRecord> goals = new LinkedHashMap<>(state.goals());
		goals.put(goalId, new GoalRecord(goalId, clusterId, prior.submission(), prior.deliveryRoute(),
			prior.policy(), GoalState.TERMINAL, null));
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.entrySet().removeIf(entry -> entry.getValue().goalId().equals(goalId));
		LinkedHashMap<UUID, CoordinationAssociation> associations = new LinkedHashMap<>(state.coordinationAssociations());
		associations.keySet().retainAll(processes.keySet());
		LinkedHashMap<UUID, PriorityLeaseAssociation> priority = new LinkedHashMap<>(state.priorityLeaseAssociations());
		priority.keySet().retainAll(processes.keySet());
		states.put(clusterId, panelState(state, state.generation() + 1, state.nextSubmissionSequence(),
			goals, state.submissionSequences(), state.requestedKinds(), processes, associations, priority));
		return true;
	}

	/** Credits completion only from an absolute actual-stock observation; promises never enter here. */
	public synchronized boolean creditPanelGoalActualStock(UUID clusterId, UUID epochId,
		long expectedGeneration, UUID goalId, long actualCount) {
		if (actualCount < 0) throw new IllegalArgumentException("Actual stock cannot be negative");
		requireRunning();
		ClusterEngineState state = states.get(Objects.requireNonNull(clusterId));
		if (state == null || !state.epochId().equals(Objects.requireNonNull(epochId))
			|| expectedGeneration > state.generation()) return false;
		GoalRecord prior = state == null ? null : state.goals().get(Objects.requireNonNull(goalId));
		if (prior == null || prior.state() == GoalState.TERMINAL
			|| prior.state() == GoalState.WAITING && prior.waitReason() == WaitReason.RESET_REQUIRED
			|| actualCount < prior.submission().multiplier()) return false;
		return publishFact(new WorldFact.PlayerCommand(clusterId, epochId, state.generation(),
			new WorldFact.Command.CreditActualStock(goalId, actualCount)));
	}

	/** Records a server-authoritative production failure on the owning Goal and process. */
	public synchronized boolean waitGoalFromServerObservation(UUID clusterId, UUID epochId,
		long expectedGeneration, UUID goalId, WaitReason reason) {
		requireRunning(); Objects.requireNonNull(clusterId); Objects.requireNonNull(epochId);
		Objects.requireNonNull(goalId); Objects.requireNonNull(reason);
		ClusterEngineState state = states.get(clusterId);
		if (state == null || !state.epochId().equals(epochId)
			|| state.generation() != expectedGeneration || state.generation() == Long.MAX_VALUE)
			return false;
		GoalRecord prior = state.goals().get(goalId);
		if (prior == null || prior.state() == GoalState.TERMINAL) return false;
		if (inFlight.contains(clusterId)) {
			deferredGoalWaits.put(new GoalWaitKey(clusterId, goalId),
				new DeferredGoalWait(epochId, expectedGeneration, reason));
			return true;
		}
		applyGoalWait(state, goalId, reason);
		return true;
	}

	private void applyGoalWait(ClusterEngineState state, UUID goalId, WaitReason reason) {
		GoalRecord prior = Objects.requireNonNull(state.goals().get(goalId), "unknown Goal");
		LinkedHashMap<UUID, GoalRecord> goals = new LinkedHashMap<>(state.goals());
		goals.put(goalId, new GoalRecord(goalId, state.clusterId(), prior.submission(),
			prior.deliveryRoute(), prior.policy(), GoalState.WAITING, reason));
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.replaceAll((processId, process) -> process.goalId().equals(goalId)
			&& process.state() != ProcessState.TERMINAL
			? new ProcessRecord(process.processId(), process.goalId(), process.owningComputerId(),
				process.owningSlot(), process.owningComputer(), process.kind(), ProcessState.WAITING,
				reason, process.generation(), process.childRevisionStates()) : process);
		states.put(state.clusterId(), panelState(state, state.generation() + 1,
			state.nextSubmissionSequence(), goals, state.submissionSequences(), state.requestedKinds(),
			processes, state.coordinationAssociations(), state.priorityLeaseAssociations()));
	}

	private ClusterEngineState exactPanelState(UUID clusterId, UUID epochId, long generation) {
		Objects.requireNonNull(clusterId); Objects.requireNonNull(epochId);
		if (inFlight.contains(clusterId)) return null;
		ClusterEngineState state = states.get(clusterId);
		return state != null && state.epochId().equals(epochId) && state.generation() == generation
			&& generation < Long.MAX_VALUE ? state : null;
	}

	private static ClusterEngineState panelState(ClusterEngineState state, long generation,
		long nextSubmissionSequence, Map<UUID, GoalRecord> goals, Map<UUID, Long> sequences,
		Map<UUID, ProcessKind> kinds, Map<UUID, ProcessRecord> processes,
		Map<UUID, CoordinationAssociation> associations,
		Map<UUID, PriorityLeaseAssociation> priorityAssociations) {
		int processCursor = processes.isEmpty() ? 0 : Math.min(state.processCursor(), processes.size() - 1);
		return new ClusterEngineState(state.clusterId(), state.epochId(), generation,
			nextSubmissionSequence, state.nextRouteSelectionSequence(), state.memberCursor(),
			processCursor, goals, sequences, kinds, processes, state.members(), state.pendingIntents(),
			state.uncertainIntentIds(), 0, null, state.routeCursor(), associations, priorityAssociations);
	}

	private Optional<AgentPendingRunQueue.PriorityLease> acquirePriorityLease(
		ProductionRequestTicket ticket, UUID agentOwnerClusterId, ProcessKind kind,
		boolean requiresAlchemist) {
		Objects.requireNonNull(ticket, "ticket"); Objects.requireNonNull(kind, "kind");
		if (!started || closed || kind != ProcessKind.PRIORITY_LEASE || requiresAlchemist) return Optional.empty();
		pumpResults();
		UUID clusterId = Objects.requireNonNull(agentOwnerClusterId, "agentOwnerClusterId");
		ClusterEngineState state = states.get(clusterId);
		if (state == null || inFlight.contains(clusterId)) return Optional.empty();
		UUID processId = priorityIdentity("priority-lease-process", clusterId, ticket.agentId(), ticket.requestId());
		UUID goalId = priorityIdentity("priority-lease-goal", clusterId, ticket.agentId(), ticket.requestId());
		ProcessRecord existing = state.processes().get(processId);
		if (existing != null) {
			GoalRecord goal = state.goals().get(existing.goalId());
			PriorityLeaseAssociation association = state.priorityLeaseAssociations().get(processId);
			if (existing.kind() != kind || existing.state() == ProcessState.TERMINAL
				|| !existing.goalId().equals(goalId) || !matchesPriorityTicket(goal, ticket)
				|| association == null || !association.matches(ticket, clusterId))
				return Optional.empty();
			return Optional.of(new AgentPendingRunQueue.PriorityLease(processId, clusterId,
				ticket.agentId(), ticket.requestId(), kind, false));
		}
		if (state.goals().containsKey(goalId) || state.goals().size() >= ClusterEngineState.MAX_GOALS
			|| state.processes().size() >= ClusterEngineState.MAX_PROCESSES
			|| state.nextSubmissionSequence() == Long.MAX_VALUE
			|| state.generation() == Long.MAX_VALUE) return Optional.empty();
		PrioritySlot slot = findOrdinaryPrioritySlot(state);
		if (slot == null) return Optional.empty();

		GoalSubmission submission = new GoalSubmission(goalId, ticket.patternId(), ticket.multiplier(), ticket.mode());
		GoalRecord goal = new GoalRecord(goalId, clusterId, submission, ticket.targetRoute(),
			new PolicySnapshot(0, ticket.requestedQueuePriority(), false), GoalState.RUNNABLE, null);
		ProcessRecord process = new ProcessRecord(processId, goalId, slot.member().computerId(), slot.slot(),
			slot.member().profile(), kind, ProcessState.RUNNABLE, null, 0, Map.of());
		LinkedHashMap<UUID, GoalRecord> goals = new LinkedHashMap<>(state.goals()); goals.put(goalId, goal);
		LinkedHashMap<UUID, Long> sequences = new LinkedHashMap<>(state.submissionSequences());
		sequences.put(goalId, state.nextSubmissionSequence());
		LinkedHashMap<UUID, ProcessKind> kinds = new LinkedHashMap<>(state.requestedKinds()); kinds.put(goalId, kind);
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.put(processId, process);
		LinkedHashMap<UUID, PriorityLeaseAssociation> priorityAssociations =
			new LinkedHashMap<>(state.priorityLeaseAssociations());
		priorityAssociations.put(processId, PriorityLeaseAssociation.from(processId, ticket, clusterId));
		states.put(clusterId, new ClusterEngineState(clusterId, state.epochId(), state.generation() + 1,
			state.nextSubmissionSequence() + 1, state.nextRouteSelectionSequence(), slot.nextMemberCursor(),
			state.processCursor(), goals, sequences, kinds, processes, state.members(), state.pendingIntents(),
			state.uncertainIntentIds(), state.goalScanOffset(), state.goalScanCandidate(), state.routeCursor(),
			state.coordinationAssociations(), priorityAssociations));
		return Optional.of(new AgentPendingRunQueue.PriorityLease(processId, clusterId,
			ticket.agentId(), ticket.requestId(), kind, false));
	}

	private void releasePriorityLease(AgentPendingRunQueue.PriorityLease lease) {
		Objects.requireNonNull(lease, "lease");
		if (inFlight.contains(lease.ownerClusterId())) {
			pendingPriorityLeaseReleases.put(lease.processId(), lease);
			return;
		}
		applyPriorityLeaseRelease(lease);
	}

	private boolean replacePriorityLease(AgentPendingRunQueue.PriorityLease lease,
		ProductionRequestTicket expectedTicket, ProductionRequestTicket updatedTicket,
		UUID agentOwnerClusterId) {
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(expectedTicket, "expectedTicket");
		Objects.requireNonNull(updatedTicket, "updatedTicket");
		UUID clusterId = Objects.requireNonNull(agentOwnerClusterId, "agentOwnerClusterId");
		if (!started || closed || expectedTicket.requestedQueuePriority() == AgentPendingRunQueue.DEFAULT_PRIORITY
			|| updatedTicket.requestedQueuePriority() == AgentPendingRunQueue.DEFAULT_PRIORITY)
			return false;
		pumpResults();
		if (inFlight.contains(clusterId) || pendingPriorityLeaseReleases.containsKey(lease.processId()))
			return false;

		UUID expectedProcessId = priorityIdentity("priority-lease-process", clusterId,
			expectedTicket.agentId(), expectedTicket.requestId());
		UUID updatedProcessId = priorityIdentity("priority-lease-process", clusterId,
			updatedTicket.agentId(), updatedTicket.requestId());
		UUID expectedGoalId = priorityIdentity("priority-lease-goal", clusterId,
			expectedTicket.agentId(), expectedTicket.requestId());
		UUID updatedGoalId = priorityIdentity("priority-lease-goal", clusterId,
			updatedTicket.agentId(), updatedTicket.requestId());
		if (!lease.processId().equals(expectedProcessId) || !expectedProcessId.equals(updatedProcessId)
			|| !expectedGoalId.equals(updatedGoalId)
			|| !lease.ownerClusterId().equals(clusterId)
			|| !lease.agentId().equals(expectedTicket.agentId())
			|| !lease.requestId().equals(expectedTicket.requestId())
			|| lease.kind() != ProcessKind.PRIORITY_LEASE || lease.requiresAlchemist())
			return false;

		ClusterEngineState state = states.get(clusterId);
		if (state == null || state.generation() == Long.MAX_VALUE) return false;
		ProcessRecord process = state.processes().get(expectedProcessId);
		GoalRecord goal = process == null ? null : state.goals().get(process.goalId());
		PriorityLeaseAssociation association = state.priorityLeaseAssociations()
			.get(expectedProcessId);
		if (process == null || process.kind() != ProcessKind.PRIORITY_LEASE
			|| process.state() == ProcessState.TERMINAL
			|| !process.goalId().equals(expectedGoalId)
			|| association == null || !association.matches(expectedTicket, clusterId)
			|| !matchesPriorityTicket(goal, expectedTicket))
			return false;

		GoalSubmission submission = new GoalSubmission(expectedGoalId, updatedTicket.patternId(),
			updatedTicket.multiplier(), updatedTicket.mode());
		GoalRecord updatedGoal = new GoalRecord(expectedGoalId, clusterId, submission,
			updatedTicket.targetRoute(), new PolicySnapshot(goal.policy().generation(),
				updatedTicket.requestedQueuePriority(), goal.policy().directRequiredOutputs()),
			goal.state(), goal.waitReason());
		LinkedHashMap<UUID, GoalRecord> goals = new LinkedHashMap<>(state.goals());
		goals.put(expectedGoalId, updatedGoal);
		LinkedHashMap<UUID, PriorityLeaseAssociation> priorityAssociations =
			new LinkedHashMap<>(state.priorityLeaseAssociations());
		priorityAssociations.put(expectedProcessId,
			PriorityLeaseAssociation.from(expectedProcessId, updatedTicket, clusterId));
		states.put(clusterId, new ClusterEngineState(clusterId, state.epochId(),
			state.generation() + 1, state.nextSubmissionSequence(),
			state.nextRouteSelectionSequence(), state.memberCursor(), state.processCursor(), goals,
			state.submissionSequences(), state.requestedKinds(), state.processes(), state.members(),
			state.pendingIntents(), state.uncertainIntentIds(), state.goalScanOffset(),
			state.goalScanCandidate(), state.routeCursor(), state.coordinationAssociations(),
			priorityAssociations));
		return true;
	}

	private void applyPriorityLeaseRelease(AgentPendingRunQueue.PriorityLease lease) {
		UUID clusterId = lease.ownerClusterId();
		ClusterEngineState state = states.get(clusterId);
		if (state == null) return;
		ProcessRecord process = state.processes().get(lease.processId());
		if (process == null || process.kind() != ProcessKind.PRIORITY_LEASE) return;
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.remove(process.processId());
		LinkedHashMap<UUID, GoalRecord> goals = new LinkedHashMap<>();
		state.goals().forEach((goalId, goal) -> {
			if (goalId.equals(process.goalId())) return;
			goals.put(goalId, goal.state() == GoalState.WAITING && goal.waitReason() == WaitReason.PROCESS
				? new GoalRecord(goal.goalId(), goal.owningClusterId(), goal.submission(), goal.deliveryRoute(),
					goal.policy(), GoalState.RUNNABLE, null) : goal);
		});
		LinkedHashMap<UUID, Long> sequences = new LinkedHashMap<>(state.submissionSequences());
		sequences.remove(process.goalId());
		LinkedHashMap<UUID, ProcessKind> kinds = new LinkedHashMap<>(state.requestedKinds());
		kinds.remove(process.goalId());
		LinkedHashMap<UUID, CoordinationAssociation> associations =
			new LinkedHashMap<>(state.coordinationAssociations());
		associations.remove(process.processId());
		LinkedHashMap<UUID, PriorityLeaseAssociation> priorityAssociations =
			new LinkedHashMap<>(state.priorityLeaseAssociations());
		priorityAssociations.remove(process.processId());
		int processCursor = processes.isEmpty() ? 0 : Math.min(state.processCursor(), processes.size() - 1);
		long generation = state.generation() == Long.MAX_VALUE ? Long.MAX_VALUE : state.generation() + 1;
		states.put(clusterId, new ClusterEngineState(clusterId, state.epochId(), generation,
			state.nextSubmissionSequence(), state.nextRouteSelectionSequence(), state.memberCursor(),
			processCursor, goals, sequences, kinds, processes, state.members(), state.pendingIntents(),
			state.uncertainIntentIds(), 0, null, state.routeCursor(), associations,
			priorityAssociations));
		if (!closed) dispatch(clusterId);
	}

	private void drainPriorityLeaseReleases() {
		for (Iterator<Map.Entry<UUID, AgentPendingRunQueue.PriorityLease>> iterator =
			pendingPriorityLeaseReleases.entrySet().iterator(); iterator.hasNext();) {
			AgentPendingRunQueue.PriorityLease lease = iterator.next().getValue();
			if (inFlight.contains(lease.ownerClusterId())) continue;
			iterator.remove(); applyPriorityLeaseRelease(lease);
		}
	}

	private static PrioritySlot findOrdinaryPrioritySlot(ClusterEngineState state) {
		if (state.members().isEmpty()) return null;
		HashSet<Map.Entry<UUID, Integer>> occupied = new HashSet<>();
		state.processes().values().stream().filter(process -> process.state() != ProcessState.TERMINAL)
			.forEach(process -> occupied.add(Map.entry(process.owningComputerId(), process.owningSlot())));
		for (int offset = 0; offset < state.members().size(); offset++) {
			int memberIndex = (state.memberCursor() + offset) % state.members().size();
			ClusterEngineState.MemberSnapshot member = state.members().get(memberIndex);
			if (!member.loaded()) continue;
			for (int slot = 0; slot < member.profile().processSlots(); slot++)
				if (!occupied.contains(Map.entry(member.computerId(), slot)))
					return new PrioritySlot(member, slot, (memberIndex + 1) % state.members().size());
		}
		return null;
	}

	private static boolean matchesPriorityTicket(GoalRecord goal, ProductionRequestTicket ticket) {
		return goal != null
			&& goal.submission().patternId().equals(ticket.patternId())
			&& goal.submission().multiplier() == ticket.multiplier()
			&& goal.submission().inputMode() == ticket.mode()
			&& goal.deliveryRoute().equals(ticket.targetRoute())
			&& goal.policy().priority() == ticket.requestedQueuePriority();
	}

	private static UUID priorityIdentity(String namespace, UUID clusterId, UUID agentId, UUID requestId) {
		return UUID.nameUUIDFromBytes((namespace + ":" + clusterId + ":" + agentId + ":" + requestId)
			.getBytes(StandardCharsets.UTF_8));
	}

	public synchronized List<FactoryComputeWorker.Diagnostic> drainDiagnostics() {
		pumpResults(); ArrayList<FactoryComputeWorker.Diagnostic> result = new ArrayList<>(diagnostics); diagnostics.clear(); return List.copyOf(result);
	}

	private void pumpResults() { pumpResults(true); }

	/**
	 * Publishes completed immutable snapshots. Server-authoritative mutations suppress immediate
	 * continuation dispatch so they can claim the just-completed snapshot before the worker does.
	 */
	private void pumpResults(boolean redispatchCompleted) {
		List<FactoryComputeWorker.ComputeResult> completed = worker.drainResults(config.pendingRunCapacity());
		for (FactoryComputeWorker.ComputeResult result : completed) {
			if (result.remotePureWork().isPresent()) {
				FactoryComputeWorker.RemotePureWork work = result.remotePureWork().orElseThrow();
				RemotePureKey key = RemotePureKey.from(work);
				FactoryComputeWorker.RemotePureWork submitted = remotePureInFlight.remove(key);
				if (submitted != null && submitted.equals(work)) result.remotePureResult().ifPresent(value -> {
					if (readyRemotePureResults.size() >= config.pendingRunCapacity()
						&& !readyRemotePureResults.containsKey(key))
						throw new IllegalStateException("ready remote-computation invariant exceeded");
					readyRemotePureResults.put(key, new RemotePureCompletion(work, value));
				});
				if (result.diagnostic() != null) addDiagnostic(result.diagnostic());
				continue;
			}
			inFlightLibraryWork.remove(result.clusterId());
			ClusterEngineState current = states.get(result.clusterId());
			if (current != null && !current.epochId().equals(result.state().epochId())) {
				inFlight.remove(result.clusterId()); inFlightSinceNanos.remove(result.clusterId());
				if (redispatchCompleted) dispatch(result.clusterId());
				continue;
			}
			states.put(result.clusterId(), result.state()); inFlight.remove(result.clusterId());
			inFlightSinceNanos.remove(result.clusterId());
			applyDeferredGoalWaits(result.clusterId());
			drainExternalListenerReleases(result.clusterId());
			if (result.yielded()) continuations.add(result.clusterId());
			if (result.diagnostic() != null) {
				addDiagnostic(result.diagnostic());
			}
			ArrayDeque<PatternReply> libraryReady = readyLibraryReplies.computeIfAbsent(result.clusterId(),
				ignored -> new ArrayDeque<>());
			for (PatternReply reply : result.libraryReplies()) {
				if (libraryReady.size() == MAX_READY_LIBRARY_REPLIES)
					throw new IllegalStateException("ready Library reply invariant exceeded");
				libraryReady.addLast(reply);
			}
			result.libraryContinuation().ifPresent(query -> libraryQueries.computeIfAbsent(
				result.clusterId(), ignored -> new ArrayDeque<>()).addFirst(query));
			LinkedBlockingDeque<ProductionEffect> ready = readyIntents.computeIfAbsent(result.clusterId(),
				ignored -> new LinkedBlockingDeque<>(ClusterEngineState.MAX_PENDING_INTENTS));
			ready.removeIf(intent -> !result.state().pendingIntents().containsKey(intent.intentId()));
			for (ProductionEffect intent : result.intents())
				if (result.state().uncertainIntentIds().contains(intent.intentId())
					&& ready.stream().noneMatch(existing -> existing.intentId().equals(intent.intentId()))
					&& !ready.offerLast(intent))
					throw new IllegalStateException("ready intent invariant exceeded for " + result.clusterId());
			if (redispatchCompleted) dispatch(result.clusterId());
		}
		readyIntents.forEach((clusterId, ready) -> {
			ClusterEngineState state = states.get(clusterId);
			ready.removeIf(intent -> state == null || !state.pendingIntents().containsKey(intent.intentId()));
		});
		replayedPendingIntents.entrySet().removeIf(entry -> {
			ClusterEngineState state = states.get(entry.getKey());
			if (state == null) return true;
			entry.getValue().retainAll(state.pendingIntents().keySet());
			return entry.getValue().isEmpty();
		});
		// A full worker queue leaves facts in their Cluster mailboxes. Completion frees
		// capacity, so retry every waiting Cluster rather than requiring another fact.
		drainPriorityLeaseReleases();
		if (redispatchCompleted && !completed.isEmpty())
			List.copyOf(states.keySet()).forEach(this::dispatch);
	}

	private void removeQueuedProductionSearchWork(UUID searchId) {
		libraryQueries.values().forEach(queries ->
			queries.removeIf(query -> query.queryId().equals(searchId)));
		readyLibraryReplies.values().forEach(replies ->
			replies.removeIf(reply -> reply.queryId().equals(searchId)));
	}

	private void addDiagnostic(FactoryComputeWorker.Diagnostic diagnostic) {
		if (diagnostics.size() == config.recoveryLedgerCapacity()) diagnostics.removeFirst();
		diagnostics.addLast(diagnostic);
	}

	private void applyDeferredGoalWaits(UUID clusterId) {
		if (inFlight.contains(clusterId)) return;
		ClusterEngineState state = states.get(clusterId);
		for (Iterator<Map.Entry<GoalWaitKey, DeferredGoalWait>> iterator =
			deferredGoalWaits.entrySet().iterator(); iterator.hasNext();) {
			Map.Entry<GoalWaitKey, DeferredGoalWait> entry = iterator.next();
			if (!entry.getKey().clusterId().equals(clusterId)) continue;
			DeferredGoalWait wait = entry.getValue();
			if (state == null || !state.epochId().equals(wait.epochId())) {
				iterator.remove(); continue;
			}
			GoalRecord goal = state.goals().get(entry.getKey().goalId());
			if (goal == null || goal.state() == GoalState.TERMINAL) {
				iterator.remove(); continue;
			}
			if (state.generation() < wait.observedGeneration()
				|| state.generation() == Long.MAX_VALUE) continue;
			applyGoalWait(state, goal.goalId(), wait.reason());
			state = states.get(clusterId);
			iterator.remove();
		}
	}

	private void replaceEpoch(UUID clusterId, UUID epochId) {
		taskPublicationAuthority.revokeCluster(clusterId);
		communicationPublicationAuthority.revokeCluster(clusterId);
		agentPublicationAuthority.revokeCluster(clusterId);
		panelRolePublications.remove(clusterId);
		removePanelEpochFences(clusterId);
		states.put(clusterId, ClusterEngineState.empty(clusterId, epochId));
		facts.remove(clusterId); confirmations.remove(clusterId); continuations.remove(clusterId);
		readyIntents.remove(clusterId);
		replayedPendingIntents.remove(clusterId);
		externalListenerProjections.remove(clusterId);
		pendingExternalListenerReleases.entrySet().removeIf(entry ->
			entry.getValue().clusterId().equals(clusterId));
		remotePureInFlight.entrySet().removeIf(entry ->
			entry.getValue().receiverClusterId().equals(clusterId));
		readyRemotePureResults.entrySet().removeIf(entry ->
			entry.getValue().work().receiverClusterId().equals(clusterId));
		deferredGoalWaits.keySet().removeIf(key -> key.clusterId().equals(clusterId));
		libraryQueries.remove(clusterId); readyLibraryReplies.remove(clusterId);
		inFlightLibraryWork.remove(clusterId);
		routeSelectionContexts.remove(clusterId);
		productionSearches.entrySet().stream()
			.filter(entry -> entry.getKey().clusterId().equals(clusterId))
			.forEach(entry -> entry.getValue().invalidated = true);
	}

	private void scheduleTick(long gameTick) {
		for (Map.Entry<UUID, List<ProductionEffect>> entry : scheduledIntents.entrySet()) {
			LinkedBlockingDeque<ProductionEffect> ready = readyIntents.computeIfAbsent(entry.getKey(),
				ignored -> new LinkedBlockingDeque<>(ClusterEngineState.MAX_PENDING_INTENTS));
			List<ProductionEffect> unclaimed = entry.getValue();
			for (int index = unclaimed.size() - 1; index >= 0; index--) ready.addFirst(unclaimed.get(index));
		}
		scheduledIntents.clear(); scheduledGameTick = gameTick;
		ArrayList<UUID> clusters = readyIntents.entrySet().stream().filter(entry -> !entry.getValue().isEmpty())
			.map(Map.Entry::getKey).sorted().collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		if (clusters.isEmpty()) return;
		int start = 0;
		if (lastScheduledCluster != null)
			for (int index = 0; index < clusters.size(); index++)
				if (clusters.get(index).compareTo(lastScheduledCluster) > 0) { start = index; break; }
		int remaining = config.maxCommittedIntentsPerTick();
		while (remaining > 0) {
			boolean progressed = false;
			for (int offset = 0; offset < clusters.size() && remaining > 0; offset++) {
				UUID clusterId = clusters.get((start + offset) % clusters.size());
				LinkedBlockingDeque<ProductionEffect> ready = readyIntents.get(clusterId);
				if (ready == null || ready.isEmpty()) continue;
				scheduledIntents.computeIfAbsent(clusterId, ignored -> new ArrayList<>()).add(ready.removeFirst());
				lastScheduledCluster = clusterId; remaining--; progressed = true;
			}
			if (!progressed) break;
			start = (clusters.indexOf(lastScheduledCluster) + 1) % clusters.size();
		}
		scheduledIntents.replaceAll((clusterId, intents) -> List.copyOf(intents));
	}

	private void dispatch(UUID clusterId) {
		if (closed) return;
		if (panelMutationReservations.containsKey(clusterId)) return;
		if (inFlight.contains(clusterId)) return;
		ArrayBlockingQueue<WorldFact> mailbox = facts.computeIfAbsent(clusterId,
			ignored -> new ArrayBlockingQueue<>(config.pendingRunCapacity()));
		ArrayBlockingQueue<WorldFact.IntentResult> evidence = confirmations.computeIfAbsent(clusterId,
			ignored -> new ArrayBlockingQueue<>(ClusterEngineState.MAX_PENDING_INTENTS));
		WorldFact next = !evidence.isEmpty() ? evidence.peek() : mailbox.peek();
		List<WorldFact> batch = next == null ? List.of() : List.of(next);
		ClusterEngineState state = states.get(clusterId);
		ArrayDeque<PatternQuery> queries = libraryQueries.get(clusterId);
		PatternQuery libraryQuery = batch.isEmpty() && queries != null ? queries.peekFirst() : null;
		ArrayDeque<PatternReply> libraryReady = readyLibraryReplies.get(clusterId);
		if (libraryQuery != null && libraryReady != null && libraryReady.size() >= MAX_READY_LIBRARY_REPLIES)
			libraryQuery = null;
		if (batch.isEmpty() && libraryQuery == null && !continuations.remove(clusterId) && !hasRunnableTransition(state)) return;
		boolean submitted;
		if (libraryQuery != null) {
			ProductionRecipeCatalog catalog = libraryCatalogs.get(libraryQuery.libraryId());
			if (catalog == null) catalog = new ProductionRecipeCatalog(libraryQuery.libraryId(),
				libraryQuery.formationGeneration(), libraryQuery.queryGeneration(), false, List.of());
			submitted = worker.trySubmitLibrary(state, new FactoryComputeWorker.LibraryWork(catalog, libraryQuery));
		} else submitted = worker.trySubmit(state, batch);
		if (!submitted) return;
		if (libraryQuery != null) {
			queries.removeFirst();
			inFlightLibraryWork.add(clusterId);
		}
		if (next instanceof WorldFact.IntentResult) evidence.remove();
		else if (next != null) mailbox.remove();
		inFlight.add(clusterId); inFlightSinceNanos.put(clusterId, System.nanoTime());
	}

	private static boolean hasRunnableTransition(ClusterEngineState state) {
		Set<UUID> allocated = new HashSet<>();
		state.processes().values().stream()
			.filter(process -> process.kind() == state.requestedKinds().get(process.goalId()))
			.forEach(process -> allocated.add(process.goalId()));
		return state.goals().values().stream().anyMatch(goal -> goal.state() != com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState.TERMINAL
			&& !allocated.contains(goal.goalId()));
	}

	private void requireRunning() {
		if (closed) throw new IllegalStateException("runtime service is closed");
		if (!started) throw new IllegalStateException("runtime service is not started");
	}

	private record GoalWaitKey(UUID clusterId, UUID goalId) {
		private GoalWaitKey { Objects.requireNonNull(clusterId); Objects.requireNonNull(goalId); }
	}

	private record DeferredGoalWait(UUID epochId, long observedGeneration, WaitReason reason) {
		private DeferredGoalWait {
			Objects.requireNonNull(epochId); Objects.requireNonNull(reason);
			if (observedGeneration < 0) throw new IllegalArgumentException("negative observation generation");
		}
	}

	private record PrioritySlot(ClusterEngineState.MemberSnapshot member, int slot,
		int nextMemberCursor) {}

	private record RemotePureKey(UUID leaseId, UUID receiverClusterId,
		FactoryComputeWorker.RemotePureStage stage, long revision) {
		private RemotePureKey {
			Objects.requireNonNull(leaseId); Objects.requireNonNull(receiverClusterId);
			Objects.requireNonNull(stage);
		}
		private static RemotePureKey from(FactoryComputeWorker.RemotePureWork work) {
			return new RemotePureKey(work.leaseId(), work.receiverClusterId(), work.stage(),
				work.revision());
		}
	}

	private record RemotePureCompletion(FactoryComputeWorker.RemotePureWork work,
		FactoryComputeWorker.RemotePureResult result) {
		private RemotePureCompletion {
			Objects.requireNonNull(work); Objects.requireNonNull(result);
		}
	}

	@Override public void close() {
		synchronized (this) {
			if (panelListenerCasInProgress)
				throw new IllegalStateException("listener lifecycle is locked by a configuration CAS");
			if (closed) return;
			closed = true;
			panelCommunicationSnapshotSource = null;
			panelCommunicationSnapshotOwner = null;
			panelCommunicationNetworkSource = null;
			panelCommunicationNetworkOwner = null;
			panelCommunicationTopologyAuthority = null;
			panelProductionComposition = null;
			if (panelListenerRegistry != null) panelListenerRegistry.closeInternal();
			taskPublicationAuthority.close();
			communicationPublicationAuthority.close();
			agentPublicationAuthority.close();
			panelRolePublications.clear();
			panelRoleTombstones.clear();
			pendingAgentRuns.close();
		}
		worker.close();
		synchronized (this) {
			pumpResults();
			inFlight.clear();
			inFlightLibraryWork.clear();
			libraryQueries.clear();
			readyLibraryReplies.clear();
			drainPriorityLeaseReleases();
		}
	}

	private final class RuntimePriorityLeaseAllocator
		implements AgentPendingRunQueue.PriorityLeaseAllocator {
		@Override public Optional<AgentPendingRunQueue.PriorityLease> acquire(
			ProductionRequestTicket ticket, UUID agentOwnerClusterId, ProcessKind kind,
			boolean requiresAlchemist) {
			synchronized (FactoryRuntimeService.this) {
				return acquirePriorityLease(ticket, agentOwnerClusterId, kind, requiresAlchemist);
			}
		}
		@Override public boolean replace(AgentPendingRunQueue.PriorityLease lease,
			ProductionRequestTicket expectedTicket, ProductionRequestTicket updatedTicket,
			UUID agentOwnerClusterId) {
			synchronized (FactoryRuntimeService.this) {
				return replacePriorityLease(lease, expectedTicket, updatedTicket,
					agentOwnerClusterId);
			}
		}
		@Override public void release(AgentPendingRunQueue.PriorityLease lease) {
			synchronized (FactoryRuntimeService.this) { releasePriorityLease(lease); }
		}
	}
}
