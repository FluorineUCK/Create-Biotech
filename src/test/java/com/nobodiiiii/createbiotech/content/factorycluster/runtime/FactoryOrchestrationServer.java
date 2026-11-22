package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffectCommitter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch.ProductionInputDispatch;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch.ProductionOutputDispatch;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch.ProductionSafeRetryRecovery;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch.SafeCreateRetryProof;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch.ProductionSafeRetryStore;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.DispatchManifest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.ProductionPlanRepository;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch.ProductionPlanDispatch;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionRemoteExpansion;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionPublication.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionRemoteExpansion.*;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion.Job;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion.Lifecycle;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion.Phase;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissions;


import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionLedger;


import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.PendingProductionAdmission;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.goal.ProductionGoalSettlement;


import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.DefaultRoutePolicy;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;


import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.PatternPriorityPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressObservationRegistry;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressObserver;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkAssociation;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkAssociation.AssociationKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterPrimaryFrequencyResolver;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationCommandService;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.AgentRevocationDrainState;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ProductionAgentRoleTransitionPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ProductionAgentFormationPreflight;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ProductionCommunicationAuthorityPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCoordinator;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentFormation;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputPhase;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentPendingRunQueue;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.NativeOrderLedger;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentProcess;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResetService;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentRouteRuntimeAdapter;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResolution;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentSignalSelector;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.FactoryPanelAgentAccess;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.HistoricalProductionHost;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ComputerCoordinatorMember;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerRuntime;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateDispatchCommand;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.InventoryIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.NativePackageObservation;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionRecipeCatalog;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryRegistration;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.OrdinaryProviderRegistry;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorSnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.AgentProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioLinkPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyFrame;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyLifecycle;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyPublicationAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyTracker;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RecoveryProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryBinding;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.RequestType;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;

import net.createmod.catnip.data.Pair;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import dev.ryanhcode.sable.companion.SubLevelAccess;

/** Server-owned composition root for the current factory-orchestration schema. */
public final class FactoryOrchestrationServer {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Map<MinecraftServer, Runtime> RUNTIMES = new WeakHashMap<>();
	private static final Map<MinecraftServer, InstallationReservation> INSTALLING =
		new WeakHashMap<>();
	static final String REMOTE_COMPUTATION_LIFECYCLE_KEY =
		"remote_computation/lifecycle";
	static final String LOCAL_COMPUTATION_LIFECYCLE_KEY =
		"local_computation/lifecycle";
	static final String RADIO_LEASE_PROTOCOL_KEY = "radio/leases";
	static final String RADIO_OUTBOX_KEY = "radio/outbox";
	static final String RADIO_AUTHORITY_KEY = "radio/authority";
	static final String RADIO_LISTENER_RETIREMENTS_KEY = "radio/listener_retirements";
	static final String REMOTE_AGENT_DISPATCH_PREFIX = "remote_agent_dispatch/";
	static final String REMOTE_RECOVERY_EXCHANGE_PREFIX = "remote_recovery_exchange/";

	private FactoryOrchestrationServer() {}

	/** Identity-only per-server reservation; class-monitor reentrancy cannot manufacture its owner. */
	private static final class InstallationReservation {}



	static boolean durableRemoteDispatchOwnsRun(
		Collection<ProductionEffect.CreateDispatch> dispatches, UUID runId) {
		UUID expectedRunId = Objects.requireNonNull(runId, "runId");
		return List.copyOf(Objects.requireNonNull(dispatches, "dispatches")).stream()
			.anyMatch(dispatch -> dispatch.runId().equals(expectedRunId));
	}



	static Optional<CreateRouteKey> resolveManagedInputRoute(String exactAddress,
		Set<CreateRouteKey> activeRoutes, Set<UUID> attachedNetworks) {
		String normalizedAddress = RecipeAddressRules.requireNormalized(exactAddress);
		Set<CreateRouteKey> routes = Set.copyOf(Objects.requireNonNull(activeRoutes,
			"activeRoutes"));
		Set<UUID> networks = Set.copyOf(Objects.requireNonNull(attachedNetworks,
			"attachedNetworks"));
		List<CreateRouteKey> exact = routes.stream()
			.filter(route -> route.address().equals(normalizedAddress))
			.filter(route -> networks.contains(route.logisticsNetworkId()))
			.distinct().toList();
		return exact.size() == 1 ? Optional.of(exact.getFirst()) : Optional.empty();
	}

	static Optional<StackKey> exactRequestedPrimary(@Nullable GoalObservationState evidence,
		Set<StackKey> required) {
		Set<StackKey> exactRequired = Set.copyOf(Objects.requireNonNull(required, "required"));
		return Optional.ofNullable(evidence).map(GoalObservationState::item)
			.filter(exactRequired::contains);
	}


	/** Last known peer-equal Library registration, retained durably while its Core is unloaded. */
	public record DurableProductionLibrary(UUID libraryId, UUID clusterId, Set<UUID> logisticsIds,
		long bindingRevision, long directoryRevision, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency, int priority,
		SpaceAddress address, LibraryRegistration.Reachability reachability,
		ProductionRecipeCatalog catalog) {
		public DurableProductionLibrary {
			libraryId = Objects.requireNonNull(libraryId, "libraryId");
			clusterId = Objects.requireNonNull(clusterId, "clusterId");
			logisticsIds = Set.copyOf(Objects.requireNonNull(logisticsIds, "logisticsIds"));
			address = Objects.requireNonNull(address, "address");
			frequency = Objects.requireNonNull(frequency, "frequency");
			reachability = Objects.requireNonNull(reachability, "reachability");
			catalog = Objects.requireNonNull(catalog, "catalog");
			if (bindingRevision < 0 || directoryRevision < 0
				|| !catalog.libraryId().equals(libraryId))
				throw new IllegalArgumentException("Invalid durable production Library");
		}

		/** Process restart never proves the physical Core is loaded. */
		public DurableProductionLibrary knownUnloadedAfterBootstrap() {
			if (!catalog.loaded()) return this;
			ProductionRecipeCatalog unloaded = new ProductionRecipeCatalog(catalog.libraryId(),
				catalog.formationGeneration(), catalog.catalogGeneration(), false,
				catalog.patterns());
			return new DurableProductionLibrary(libraryId, clusterId, logisticsIds,
				bindingRevision, Math.incrementExact(directoryRevision), frequency, priority,
				address, reachability, unloaded);
		}
	}

	public record AgentConfigurationFaultState(SpaceAddress panelAddress, UUID agentId,
		UUID configuredOwner, UUID configuredEpoch, UUID currentOwner, UUID currentEpoch,
		long configurationRevision, String reason) {
		public AgentConfigurationFaultState {
			panelAddress = Objects.requireNonNull(panelAddress, "panelAddress");
			agentId = Objects.requireNonNull(agentId, "agentId");
			configuredOwner = Objects.requireNonNull(configuredOwner, "configuredOwner");
			configuredEpoch = Objects.requireNonNull(configuredEpoch, "configuredEpoch");
			currentOwner = Objects.requireNonNull(currentOwner, "currentOwner");
			currentEpoch = Objects.requireNonNull(currentEpoch, "currentEpoch");
			reason = Objects.requireNonNull(reason, "reason");
			if (configurationRevision < 0 || reason.isBlank())
				throw new IllegalArgumentException("Invalid Agent configuration fault");
		}
	}

	public record AgentEndpointAuthorityState(SpaceAddress endpointAddress, UUID ownerClusterId,
		UUID clusterEpochId, long configurationRevision) {
		public AgentEndpointAuthorityState {
			endpointAddress = Objects.requireNonNull(endpointAddress, "endpointAddress");
			ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
			clusterEpochId = Objects.requireNonNull(clusterEpochId, "clusterEpochId");
			if (configurationRevision < 0)
				throw new IllegalArgumentException("negative endpoint authority revision");
		}
	}

	/** Physical native Gauge snapshot before logical Create-route authority is applied. */
	public record NativeGaugeProvider(String providerKey, int effectivePriority,
		RouteCandidate.Availability availability, boolean agentEndpointOccupied) {
		public NativeGaugeProvider {
			providerKey = Objects.requireNonNull(providerKey, "providerKey").trim();
			if (providerKey.isEmpty()) throw new IllegalArgumentException("blank Gauge provider key");
			availability = Objects.requireNonNull(availability, "availability");
		}
	}

	/** Shared production gate for both initial selection and exact provider revalidation. */
	static List<ProductionRouteTable.ProviderSource> collectLegalNativeGaugeProviders(
		RouteSelectionScope scope, UUID libraryId, UUID patternId,
		AgentResolution routeAuthority, List<NativeGaugeProvider> physicalProviders) {
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(libraryId, "libraryId");
		Objects.requireNonNull(patternId, "patternId");
		Objects.requireNonNull(routeAuthority, "routeAuthority");
		physicalProviders = List.copyOf(Objects.requireNonNull(physicalProviders,
			"physicalProviders"));
		// A claimed endpoint is an Agent provider, but unrelated native Gauges remain
		// direct Create providers and compete through DefaultRoutePolicy.
		if (physicalProviders.size() > com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable.MAX_SOURCES)
			throw new IllegalStateException("native Gauge provider snapshot exceeds bound");
		return physicalProviders.stream().filter(provider -> !provider.agentEndpointOccupied())
			.map(provider ->
			new com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable.ProviderSource(scope, libraryId, patternId,
					provider.providerKey(), RouteCandidate.ProviderKind.FACTORY_GAUGE,
					provider.effectivePriority(), provider.availability(),
					RouteCandidate.Blocker.NONE)).toList();
	}

	/** Durable desired native configuration; an empty value is an explicit clear tombstone. */
	public record AgentEndpointConfigurationState(SpaceAddress endpointAddress,
		Optional<FactoryPanelAgentAccess.Configuration> configuration) {
		public AgentEndpointConfigurationState {
			endpointAddress = Objects.requireNonNull(endpointAddress, "endpointAddress");
			configuration = Objects.requireNonNull(configuration, "configuration");
		}
	}

	/** Durable source-owned handoff replayed until the remote Agent admits it. */
	public record RemoteAgentRadioDispatch(UUID messageId, ProductionEffect.CreateDispatch intent,
		UUID agentId, long agentGeneration, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency requesterFrequency, RadioEnvelope envelope,
		List<RadioEndpoint.Hop> relays, boolean committed) {
		public RemoteAgentRadioDispatch {
			messageId = Objects.requireNonNull(messageId, "messageId");
			intent = Objects.requireNonNull(intent, "intent");
			agentId = Objects.requireNonNull(agentId, "agentId");
			requesterFrequency = Objects.requireNonNull(requesterFrequency, "requesterFrequency");
			envelope = Objects.requireNonNull(envelope, "envelope");
			relays = List.copyOf(Objects.requireNonNull(relays, "relays"));
			if (agentGeneration < 0 || !messageId.equals(envelope.messageId())
				|| !(envelope.payload() instanceof ProcessProtocolMessage.Data data)
				|| !data.value().equals("agent-dispatch:" + intent.intentId()))
				throw new IllegalArgumentException("Invalid remote Agent radio dispatch");
		}
		RemoteAgentRadioDispatch complete() {
			return new RemoteAgentRadioDispatch(messageId, intent, agentId, agentGeneration,
				requesterFrequency, envelope, relays, true);
		}
	}

	public enum RemoteRecoveryPhase {
		OFFER_PENDING,
		TERMINAL_PENDING,
		ACK_READY,
		ACK_LOST,
		STATUS_QUERY_PENDING,
		STATUS_CONFIRMED,
		RECONCILED
	}

	/** Durable cross-Cluster terminal evidence exchange; every message freezes its radio route. */
	public record RemoteRecoveryExchange(RemoteRequestKey key, UUID goalId,
		long terminalRevision, RadioEnvelope offerEnvelope,
		List<RadioEndpoint.Hop> offerRelays, RadioEnvelope terminalEnvelope,
		List<RadioEndpoint.Hop> terminalRelays, RadioEnvelope statusQueryEnvelope,
		List<RadioEndpoint.Hop> statusQueryRelays, RadioEnvelope acknowledgementEnvelope,
		List<RadioEndpoint.Hop> acknowledgementRelays, RemoteRecoveryPhase phase) {
		public RemoteRecoveryExchange {
			key = Objects.requireNonNull(key, "key");
			goalId = Objects.requireNonNull(goalId, "goalId");
			if (terminalRevision < 0) throw new IllegalArgumentException("negative terminal revision");
			offerEnvelope = Objects.requireNonNull(offerEnvelope, "offerEnvelope");
			terminalEnvelope = Objects.requireNonNull(terminalEnvelope, "terminalEnvelope");
			statusQueryEnvelope = Objects.requireNonNull(statusQueryEnvelope, "statusQueryEnvelope");
			acknowledgementEnvelope = Objects.requireNonNull(acknowledgementEnvelope,
				"acknowledgementEnvelope");
			offerRelays = List.copyOf(Objects.requireNonNull(offerRelays, "offerRelays"));
			terminalRelays = List.copyOf(Objects.requireNonNull(terminalRelays, "terminalRelays"));
			statusQueryRelays = List.copyOf(Objects.requireNonNull(statusQueryRelays,
				"statusQueryRelays"));
			acknowledgementRelays = List.copyOf(Objects.requireNonNull(acknowledgementRelays,
				"acknowledgementRelays"));
			phase = Objects.requireNonNull(phase, "phase");
		}
		RemoteRecoveryExchange withPhase(RemoteRecoveryPhase next) {
			return new RemoteRecoveryExchange(key, goalId, terminalRevision, offerEnvelope,
				offerRelays, terminalEnvelope, terminalRelays, statusQueryEnvelope, statusQueryRelays, acknowledgementEnvelope,
				acknowledgementRelays, next);
		}
		RemoteRecoveryExchange recoveredAfterReload() {
			return phase == RemoteRecoveryPhase.ACK_READY || phase == RemoteRecoveryPhase.ACK_LOST
				? withPhase(RemoteRecoveryPhase.STATUS_QUERY_PENDING) : this;
		}
	}

	private static Set<UUID> recoveryMessageIds(RemoteRecoveryExchange exchange) {
		exchange = Objects.requireNonNull(exchange, "exchange");
		return Set.of(exchange.offerEnvelope().messageId(),
			exchange.terminalEnvelope().messageId(),
			exchange.statusQueryEnvelope().messageId(),
			exchange.acknowledgementEnvelope().messageId());
	}

	/** Frozen output policy envelope keyed by Run for exact current-schema recovery. */
	public record OutputPolicyState(UUID runId, ProductionOutputPolicy policy) {
		public OutputPolicyState {
			runId = Objects.requireNonNull(runId, "runId");
			policy = Objects.requireNonNull(policy, "policy");
		}
	}

	/** Exact physical output attachment owned by a configured Agent output endpoint. */
	public record ManagedOutputEndpoint(SpaceAddress packager, InventoryIdentity inventory) {
		public ManagedOutputEndpoint {
			packager = Objects.requireNonNull(packager, "packager");
			inventory = Objects.requireNonNull(inventory, "inventory");
		}
	}

	/** Durable observation cursor; duplicate output events retain a stable identity after reload. */
	public record OutputProbeState(UUID runId, UUID observationEpoch, long sequence,
		Map<CreateRouteKey, Optional<StackKey>> stacks,
		Map<CreateRouteKey, Long> baselineCounts, Map<CreateRouteKey, Long> lastCounts,
		Map<CreateRouteKey, ManagedOutputEndpoint> managedOutputs,
		Set<SpaceAddress> dispatchPackagers) {
		public OutputProbeState {
			runId = Objects.requireNonNull(runId, "runId");
			observationEpoch = Objects.requireNonNull(observationEpoch, "observationEpoch");
			stacks = Map.copyOf(Objects.requireNonNull(stacks, "stacks"));
			baselineCounts = Map.copyOf(Objects.requireNonNull(baselineCounts, "baselineCounts"));
			lastCounts = Map.copyOf(Objects.requireNonNull(lastCounts, "lastCounts"));
			managedOutputs = Map.copyOf(Objects.requireNonNull(managedOutputs,
				"managedOutputs"));
			dispatchPackagers = Set.copyOf(Objects.requireNonNull(dispatchPackagers,
				"dispatchPackagers"));
			if (sequence < 0 || stacks.isEmpty() || !baselineCounts.keySet().equals(stacks.keySet())
				|| !lastCounts.keySet().equals(stacks.keySet())
				|| !managedOutputs.keySet().equals(stacks.keySet())
				|| baselineCounts.values().stream().anyMatch(count -> count < 0)
				|| lastCounts.values().stream().anyMatch(count -> count < 0))
				throw new IllegalArgumentException("Invalid output observation cursor");
			stacks.values().forEach(value -> Objects.requireNonNull(value, "output stack"));
		}

		public OutputProbeState sampled(long nextSequence, Map<CreateRouteKey, Long> counts) {
			return new OutputProbeState(runId, observationEpoch, nextSequence, stacks,
				baselineCounts, counts, managedOutputs, dispatchPackagers);
		}

		public OutputProbeState withDispatchPackagers(Collection<SpaceAddress> packagers) {
			LinkedHashSet<SpaceAddress> merged = new LinkedHashSet<>(dispatchPackagers);
			merged.addAll(Objects.requireNonNull(packagers, "packagers"));
			Set<SpaceAddress> managed = managedOutputs.values().stream()
				.map(ManagedOutputEndpoint::packager).collect(Collectors.toSet());
			if (!managed.containsAll(merged))
				throw new IllegalArgumentException("Output dispatch escaped its managed Packager");
			return new OutputProbeState(runId, observationEpoch, sequence, stacks,
				baselineCounts, lastCounts, managedOutputs, merged);
		}
	}

	/** Durable rising-edge cursor for one exact active Run and frozen player selector. */
	public record SignalProbeState(UUID runId, long fence, AgentSignalSelector selector,
		boolean initialized, boolean lastActive, boolean published, long sampleSequence) {
		public SignalProbeState {
			runId = Objects.requireNonNull(runId, "runId");
			selector = Objects.requireNonNull(selector, "selector");
			if (fence < 0 || sampleSequence < 0 || published && !initialized)
				throw new IllegalArgumentException("invalid signal probe fence");
		}

		public static SignalProbeState initial(ProductionRun run, AgentSignalSelector selector) {
			return new SignalProbeState(run.runId(), run.fence(), selector, false, false,
				false, 0);
		}

		public SignalSample sample(ProductionRun activeRun, AgentSignalSelector activeSelector,
			boolean loaded, int strength) {
			Objects.requireNonNull(activeRun, "activeRun");
			Objects.requireNonNull(activeSelector, "activeSelector");
			if (!runId.equals(activeRun.runId()) || fence != activeRun.fence()
				|| !selector.equals(activeSelector)) return new SignalSample(this, false);
			if (!loaded) return new SignalSample(this, false);
			if (strength < 0 || strength > 15)
				throw new IllegalArgumentException("invalid sampled signal strength");
			boolean active = strength >= selector.minimumStrength();
			boolean publish = initialized && !lastActive && active && !published;
			return new SignalSample(new SignalProbeState(runId, fence, selector, true, active,
				published, Math.incrementExact(sampleSequence)), publish);
		}

		public SignalProbeState markPublished() {
			return published ? this : new SignalProbeState(runId, fence, selector, initialized,
				lastActive, true, sampleSequence);
		}
	}

	public record SignalSample(SignalProbeState state, boolean publish) {
		public SignalSample { state = Objects.requireNonNull(state, "state"); }
	}

	static boolean matchesManagedOutput(OutputProbeState probe, CreateRouteKey route,
		SpaceAddress packager, InventoryIdentity inventory) {
		ManagedOutputEndpoint frozen = Objects.requireNonNull(probe, "probe")
			.managedOutputs().get(Objects.requireNonNull(route, "route"));
		return frozen != null && frozen.packager().equals(packager)
			&& frozen.inventory().equals(inventory);
	}




	static boolean reconcileResetRequiredGoal(FactoryRuntimeService runtime,
		ClusterEngineState source, AgentInputContract.State input) {
		Objects.requireNonNull(runtime, "runtime");
		source = Objects.requireNonNull(source, "source");
		input = Objects.requireNonNull(input, "input");
		if (input.phase() != AgentInputPhase.RESET_REQUIRED) return false;
		GoalRecord goal = source.goals().get(input.run().goalId());
		if (goal == null || goal.state() == GoalState.TERMINAL) return false;
		if (goal.state() == GoalState.WAITING
			&& goal.waitReason() == WaitReason.RESET_REQUIRED) return true;
		return runtime.waitGoalFromServerObservation(source.clusterId(), source.epochId(),
			source.generation(), goal.goalId(), WaitReason.RESET_REQUIRED);
	}

	/** Replays the Goal projection from the durable plan-side cancellation authority. */
	static boolean reconcileCancellationGoalFence(FactoryRuntimeService runtime,
		FrozenProductionPlan plan) {
		runtime = Objects.requireNonNull(runtime, "runtime");
		plan = Objects.requireNonNull(plan, "plan");
		if (!plan.cancelAfterDrain() || plan.runs().currentRun() == null) return false;
		ClusterEngineState source = runtime.snapshot(plan.clusterId()).orElse(null);
		GoalRecord goal = source == null ? null : source.goals().get(plan.goalId());
		if (goal == null || goal.state() == GoalState.TERMINAL) return false;
		if (goal.state() == GoalState.WAITING
			&& goal.waitReason() == WaitReason.RESET_REQUIRED) return true;
		return runtime.waitGoalFromServerObservation(source.clusterId(), source.epochId(),
			source.generation(), goal.goalId(), WaitReason.RESET_REQUIRED);
	}

	/** Applies a late materialized input only under the exact durable plan/run cancellation fence. */
	static RecoveryLedger.CancelResult beginPlanCancellationDrain(FrozenProductionPlan plan,
		RemoteRequestKey key, RecoveryLedger recovery, AgentPendingRunQueue queue,
		AgentProcess process, AgentInputContract input) {
		plan = Objects.requireNonNull(plan, "plan");
		key = Objects.requireNonNull(key, "key");
		recovery = Objects.requireNonNull(recovery, "recovery");
		queue = Objects.requireNonNull(queue, "queue");
		process = Objects.requireNonNull(process, "process");
		input = Objects.requireNonNull(input, "input");
		ProductionRun current = plan.runs().currentRun();
		AgentInputContract.State inputState = input.snapshot();
		if (!plan.cancelAfterDrain() || current == null
			|| !current.runId().equals(key.runId())
			|| !current.runId().equals(inputState.run().runId())
			|| key.fence() != inputState.run().fence()
			|| !plan.goalId().equals(inputState.run().goalId()))
			return RecoveryLedger.CancelResult.RESOURCE_MISMATCH;
		return recovery.cancel(new AgentProtocolMessage.Cancel(key), queue, process, input);
	}

	/** Replays a durable BEGIN_CANCEL_DRAIN effect after either side of its input-store boundary. */
	static RecoveryLedger.CancelResult reconcilePlanCancellationDrain(FrozenProductionPlan plan,
		RemoteRequestKey key, RecoveryLedger recovery, AgentPendingRunQueue queue,
		AgentProcess process, AgentInputContract input) {
		return beginPlanCancellationDrain(plan, key, recovery, queue, process, input);
	}

	static Optional<Map<SpaceAddress, FactoryPanelAgentAccess.Configuration>>
		authoritativeEndpointConfigurations(
			Map<SpaceAddress, AgentEndpointConfigurationState> desired,
			Map<SpaceAddress, AgentEndpointAuthorityState> authorities,
			SpaceAddress target, @Nullable UUID requestedAgentId) {
		desired = Map.copyOf(Objects.requireNonNull(desired, "desired"));
		authorities = Map.copyOf(Objects.requireNonNull(authorities, "authorities"));
		target = Objects.requireNonNull(target, "target");
		for (Map.Entry<SpaceAddress, AgentEndpointConfigurationState> entry : desired.entrySet())
			if (!entry.getKey().equals(entry.getValue().endpointAddress())) return Optional.empty();
		LinkedHashSet<UUID> relevant = new LinkedHashSet<>();
		if (requestedAgentId != null) relevant.add(requestedAgentId);
		desired.getOrDefault(target, new AgentEndpointConfigurationState(target, Optional.empty()))
			.configuration().map(FactoryPanelAgentAccess.Configuration::agentId)
			.ifPresent(relevant::add);
		LinkedHashMap<SpaceAddress, FactoryPanelAgentAccess.Configuration> observed =
			new LinkedHashMap<>();
		for (Map.Entry<SpaceAddress, AgentEndpointConfigurationState> entry : desired.entrySet()) {
			FactoryPanelAgentAccess.Configuration configuration =
				entry.getValue().configuration().orElse(null);
			if (configuration == null || !relevant.contains(configuration.agentId())) continue;
			AgentEndpointAuthorityState authority = authorities.get(entry.getKey());
			if (authority == null || !authority.endpointAddress().equals(entry.getKey())
				|| !authority.ownerClusterId().equals(configuration.ownerClusterId())
				|| !authority.clusterEpochId().equals(configuration.clusterEpochId())
				|| authority.configurationRevision() != configuration.configurationRevision())
				return Optional.empty();
			observed.put(entry.getKey(), configuration);
		}
		return Optional.of(Map.copyOf(observed));
	}

	static Optional<Map<UUID, AgentFormation>> plannedMembershipFormations(
		Map<SpaceAddress, AgentEndpointState> prospectiveEndpoints,
		Set<UUID> affectedAgentIds, Set<UUID> currentlyFormedAgentIds) {
		prospectiveEndpoints = Map.copyOf(Objects.requireNonNull(prospectiveEndpoints,
			"prospectiveEndpoints"));
		affectedAgentIds = Set.copyOf(Objects.requireNonNull(affectedAgentIds,
			"affectedAgentIds"));
		currentlyFormedAgentIds = Set.copyOf(Objects.requireNonNull(currentlyFormedAgentIds,
			"currentlyFormedAgentIds"));
		if (!affectedAgentIds.containsAll(currentlyFormedAgentIds)) return Optional.empty();
		LinkedHashMap<UUID, AgentFormation> formations = new LinkedHashMap<>();
		for (UUID agentId : affectedAgentIds) {
			List<AgentEndpointState> members = prospectiveEndpoints.values().stream()
				.filter(AgentEndpointState::loaded)
				.filter(endpoint -> endpoint.agentId().equals(agentId))
				.sorted(Comparator.comparing(AgentEndpointState::endpointAddress,
					AgentFormation.ADDRESS_ORDER)).toList();
			boolean formable = members.stream().anyMatch(endpoint ->
				endpoint.role() == com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole.INPUT)
				&& members.stream().anyMatch(endpoint ->
					endpoint.role() == com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole.OUTPUT);
			if (!formable) {
				if (currentlyFormedAgentIds.contains(agentId)) return Optional.empty();
				continue;
			}
			try { formations.put(agentId, AgentFormation.form(members)); }
			catch (IllegalArgumentException incoherent) { return Optional.empty(); }
		}
		return Optional.of(Map.copyOf(formations));
	}

	static void publishRecoveryTransition(Runnable durablePublication,
		java.util.function.Consumer<UUID> retireMessage, List<UUID> deliveredMessageIds) {
		Objects.requireNonNull(durablePublication, "durablePublication").run();
		retireMessage = Objects.requireNonNull(retireMessage, "retireMessage");
		for (UUID messageId : List.copyOf(Objects.requireNonNull(deliveredMessageIds,
			"deliveredMessageIds"))) retireMessage.accept(messageId);
	}

	static int retiredRecoveryMessagePrefix(RemoteRecoveryPhase phase) {
		return switch (Objects.requireNonNull(phase, "phase")) {
			case OFFER_PENDING -> 0;
			case TERMINAL_PENDING -> 1;
			case ACK_READY, ACK_LOST, STATUS_QUERY_PENDING -> 2;
			case STATUS_CONFIRMED -> 3;
			case RECONCILED -> 4;
		};
	}


	/**
	 * Generic radio submissions may be retired only when the router publishes their recipient
	 * evidence durably. Recovery and production Data messages have dedicated durable owners;
	 * Agent and pure-computation protocols are persisted by the router before delivery returns.
	 */
	static boolean genericRadioProtocolHasDurableRecipientStore(ProcessProtocolMessage payload) {
		payload = Objects.requireNonNull(payload, "payload");
		return payload instanceof AgentProtocolMessage
			|| payload instanceof ProcessProtocolMessage.LeaseOffer
			|| payload instanceof ProcessProtocolMessage.LeaseRevision
			|| payload instanceof ProcessProtocolMessage.RevisionAcknowledgement
			|| payload instanceof ProcessProtocolMessage.LeaseResult;
	}









	/** Reload normalization for a cancel intent that reached RecoveryLedger before its plan fence. */
	static FrozenProductionPlan normalizeCancellationPlanFence(FrozenProductionPlan plan,
		Set<UUID> cancelDrainRunIds) {
		plan = Objects.requireNonNull(plan, "plan");
		cancelDrainRunIds = Set.copyOf(Objects.requireNonNull(cancelDrainRunIds,
			"cancelDrainRunIds"));
		ProductionRun current = plan.runs().currentRun();
		return !plan.cancelAfterDrain() && current != null
			&& cancelDrainRunIds.contains(current.runId()) ? plan.cancelAfterDrainNow() : plan;
	}

	/** Panel projections intentionally have no historical fallback. */
	static <T> @Nullable T currentRunArtifact(@Nullable FrozenProductionPlan plan,
		Map<UUID, T> artifacts) {
		Objects.requireNonNull(artifacts, "artifacts");
		ProductionRun current = plan == null ? null : plan.runs().currentRun();
		return current == null ? null : artifacts.get(current.runId());
	}

	/** Status diagnostics may fall back only to bounded terminal evidence for the exact goal. */
	static Optional<UUID> selectProductionStatusRunId(@Nullable UUID currentRunId,
		Set<UUID> liveRunIds, Collection<RemoteRequestKey> terminalKeys) {
		liveRunIds = Set.copyOf(Objects.requireNonNull(liveRunIds, "liveRunIds"));
		List<RemoteRequestKey> terminals = List.copyOf(Objects.requireNonNull(terminalKeys,
			"terminalKeys"));
		// The frozen plan's current Run is authoritative even before it has produced a dispatch
		// artifact (for example while remote computation is waiting for a route). Historical
		// terminal evidence is only a fallback after the plan has no current Run.
		if (currentRunId != null) return Optional.of(currentRunId);
		return terminals.stream().max(Comparator.comparingLong(RemoteRequestKey::sourceGeneration)
			.thenComparingLong(RemoteRequestKey::fence)
			.thenComparingLong(RemoteRequestKey::agentGeneration)
			.thenComparing(RemoteRequestKey::runId)).map(RemoteRequestKey::runId);
	}

	static WaitReason outputDeliveryWaitReason(FrozenProductionPlan plan) {
		return Objects.requireNonNull(plan, "plan").cancelAfterDrain()
			? WaitReason.RESET_REQUIRED : WaitReason.OUTPUT_DELIVERY;
	}

	public record OutputNativeReceiptState(UUID intentId, UUID runId,
		CreateRouteKey destination, UUID receiverAgentId, UUID observationEpoch,
		NativeOrderLedger.State nativeOrders, boolean ackPublished) {
		public OutputNativeReceiptState {
			intentId = Objects.requireNonNull(intentId, "intentId");
			runId = Objects.requireNonNull(runId, "runId");
			destination = Objects.requireNonNull(destination, "destination");
			receiverAgentId = Objects.requireNonNull(receiverAgentId, "receiverAgentId");
			observationEpoch = Objects.requireNonNull(observationEpoch, "observationEpoch");
			nativeOrders = Objects.requireNonNull(nativeOrders, "nativeOrders");
		}
		OutputNativeReceiptState withLedger(NativeOrderLedger.State state) {
			return new OutputNativeReceiptState(intentId, runId, destination, receiverAgentId,
				observationEpoch, state, ackPublished);
		}
		OutputNativeReceiptState acknowledged() {
			return new OutputNativeReceiptState(intentId, runId, destination, receiverAgentId,
				observationEpoch, nativeOrders, true);
		}
	}

	static OutputNativeReceiptState observeOutputReceipt(OutputNativeReceiptState receipt,
		NativePackageObservation observation) {
		receipt = Objects.requireNonNull(receipt, "receipt");
		NativeOrderLedger ledger = NativeOrderLedger.restore(receipt.nativeOrders());
		return ledger.observe(Objects.requireNonNull(observation, "observation"))
			== NativeOrderLedger.ObservationResult.ACCEPTED
				? receipt.withLedger(ledger.snapshot()) : receipt;
	}

	static boolean outputReceiptComplete(OutputNativeReceiptState receipt) {
		return NativeOrderLedger.restore(Objects.requireNonNull(receipt, "receipt").nativeOrders())
			.complete();
	}

	/** The exact durable source-side receipt paired with consumed router authority. */




	/**
	 * A new handoff must still prove the mutable source lineage. A durably paired handoff receipt
	 * instead replays solely from its exact consumed router fence, even if that source Process later
	 * migrates before the mailbox accepts the immutable fact.
	 */


	/**
	 * Reconstructs only the DispatchCreate already authorized by a durable consumed handoff. An
	 * existing Engine intent is redelivered by identity; otherwise a rejected mailbox publication
	 * leaves the receipt retryable without changing material authority.
	 */
	static boolean replayRemoteMaterialHandoffDispatch(FactoryRuntimeService runtime,
		FrozenProductionPlan plan, RemoteComputationExchange exchange, RadioRouter router,
		net.minecraft.core.HolderLookup.Provider registries,
		BooleanSupplier durableDownstreamEvidence,
		java.util.function.Predicate<WorldFact.PlayerCommand> mailboxPublisher) {
		runtime = Objects.requireNonNull(runtime, "runtime");
		plan = Objects.requireNonNull(plan, "plan");
		exchange = Objects.requireNonNull(exchange, "exchange");
		router = Objects.requireNonNull(router, "router");
		registries = Objects.requireNonNull(registries, "registries");
		durableDownstreamEvidence = Objects.requireNonNull(durableDownstreamEvidence,
			"durableDownstreamEvidence");
		mailboxPublisher = Objects.requireNonNull(mailboxPublisher, "mailboxPublisher");
		if (!remoteMaterialHandoffMayPublish(plan, exchange, router, () -> false)) return false;
		ProductionRun run = plan.runs().currentRun();
		ClusterEngineState state = runtime.snapshot(plan.clusterId()).orElse(null);
		if (run == null || state == null || state.generation() == Long.MAX_VALUE) return false;
		ProcessRecord process = state.processes().get(
			exchange.lease().expansionRequest().parentProcessId());
		GoalRecord goal = state.goals().get(plan.goalId());
		if (process == null || goal == null || goal.state() == GoalState.TERMINAL
			|| !process.goalId().equals(plan.goalId())) return false;
		ClusterEngineState.MemberSnapshot owner = state.member(process.owningComputerId());
		if (owner == null || !owner.loaded()) return false;
		List<OrderedIngredient> ingredients = orderedDispatchIngredients(plan,
			state.generation(), registries);
		List<ProductionEffect.CreateDispatch> pending = state.pendingIntents().values().stream()
			.filter(ProductionEffect.CreateDispatch.class::isInstance)
			.map(ProductionEffect.CreateDispatch.class::cast)
			.filter(intent -> intent.runId().equals(run.runId())).toList();
		if (pending.size() > 1) return false;
		if (pending.size() == 1) {
			ProductionEffect.CreateDispatch exact = pending.getFirst();
			if (!exact.processId().equals(process.processId())
				|| !exact.route().equals(plan.stages().getFirst().route())
				|| !exact.ingredients().equals(ingredients)) return false;
			return runtime.replayPendingCreateDispatch(state.clusterId(), run.runId());
		}
		if (durableDownstreamEvidence.getAsBoolean()) return true;
		return mailboxPublisher.test(new WorldFact.PlayerCommand(state.clusterId(), state.epochId(),
			state.generation(), new WorldFact.Command.DispatchCreate(process.processId(), run.runId(),
				plan.stages().getFirst().route(), ingredients)));
	}

	private static List<OrderedIngredient> orderedDispatchIngredients(
		FrozenProductionPlan plan, long generation,
		net.minecraft.core.HolderLookup.Provider registries) {
		Objects.requireNonNull(registries, "registries");
		return Objects.requireNonNull(plan, "plan").orderedIngredientsForCurrentRun(generation);
	}

	record PendingCreateRejection(UUID clusterId, UUID epochId, UUID runId, UUID intentId,
		long generation) {
		PendingCreateRejection {
			clusterId = Objects.requireNonNull(clusterId, "clusterId");
			epochId = Objects.requireNonNull(epochId, "epochId");
			runId = Objects.requireNonNull(runId, "runId");
			intentId = Objects.requireNonNull(intentId, "intentId");
			if (generation < 0) throw new IllegalArgumentException("negative intent generation");
		}

		static PendingCreateRejection from(ProductionEffect.CreateDispatch intent) {
			intent = Objects.requireNonNull(intent, "intent");
			return new PendingCreateRejection(intent.clusterId(), intent.fence().epochId(),
				intent.runId(), intent.intentId(), intent.generation());
		}
	}

	/** Holds an aborted Output closed across reload until the exact retry admission reopens it. */
	static boolean prepareOutputObservation(ProductionOutputContract output,
		ProductionOutputContract.OutputProcessCapacity capacity, boolean safeRetryPending) {
		output = Objects.requireNonNull(output, "output");
		capacity = Objects.requireNonNull(capacity, "capacity");
		if (safeRetryPending) return false;
		if (output.milestone() == ProductionOutputContract.Milestone.WAITING_INPUT_READY)
			output.beginAtInputReady(capacity);
		else output.reattachCapacity(capacity);
		return true;
	}

	static boolean pendingCreateRejectionApplied(ClusterEngineState state,
		PendingCreateRejection rejection) {
		state = Objects.requireNonNull(state, "state");
		rejection = Objects.requireNonNull(rejection, "rejection");
		return state.clusterId().equals(rejection.clusterId())
			&& state.epochId().equals(rejection.epochId())
			&& !state.pendingIntents().containsKey(rejection.intentId());
	}



	public enum RecoveryOfferAction {
		PROCEED,
		WAIT_ROUTE,
		WAIT_PROCESS,
		WAIT_REMOTE_RECOVERY,
		WAIT_RECOVERY_LEDGER_FULL,
		FAIL_RESET_REQUIRED
	}

	static RecoveryOfferAction recoveryOfferAction(RecoveryLedger.OfferDisposition disposition) {
		return switch (Objects.requireNonNull(disposition, "disposition")) {
			case ACCEPTED, DUPLICATE -> RecoveryOfferAction.PROCEED;
			case RECOVERY_LEDGER_FULL -> RecoveryOfferAction.WAIT_RECOVERY_LEDGER_FULL;
			case QUEUE_REJECTED -> RecoveryOfferAction.WAIT_PROCESS;
			case ACKNOWLEDGED_DUPLICATE, REMOTE_RECOVERY ->
				RecoveryOfferAction.WAIT_REMOTE_RECOVERY;
			case STALE_FENCE, STALE_SOURCE_GENERATION, STALE_AGENT_GENERATION,
				POST_INTENT_RETRY_REJECTED, LINEAGE_MUTATION_REJECTED ->
				RecoveryOfferAction.FAIL_RESET_REQUIRED;
		};
	}

	static WaitReason recoveryOfferWaitReason(RecoveryOfferAction action) {
		return switch (Objects.requireNonNull(action, "action")) {
			case WAIT_ROUTE -> WaitReason.ROUTE;
			case WAIT_PROCESS -> WaitReason.PROCESS;
			case WAIT_REMOTE_RECOVERY -> WaitReason.REMOTE_RECOVERY;
			case WAIT_RECOVERY_LEDGER_FULL -> WaitReason.RECOVERY_LEDGER_FULL;
			case FAIL_RESET_REQUIRED -> WaitReason.RESET_REQUIRED;
			case PROCEED -> throw new IllegalArgumentException(
				"a successful recovery offer has no wait reason");
		};
	}

	public static synchronized boolean running(MinecraftServer server) {
		return RUNTIMES.containsKey(Objects.requireNonNull(server, "server"));
	}

	/** The server-owned authority boundary for durable Cluster communication commands. */
	public static Optional<ClusterCommunicationCommandService> communicationCommandService(
		MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null || !runtime.acceptsWork() ? Optional.empty()
			: Optional.of(runtime.communicationCommands);
	}

	public static Optional<ClusterCommunicationConfiguration> communicationConfiguration(
		MinecraftServer server, UUID clusterId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime == null ? Optional.empty() : runtime.communicationConfiguration(clusterId);
	}

	/** Latest complete immutable physical-radio sample; it is safe for worker-side inspection. */
	public static Optional<RadioTopologyFrame> radioTopologyFrame(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime == null ? Optional.empty() : Optional.of(runtime.radioTopologyFrame());
	}

	/** Small read-only normal Goggles summary; capture stays on the Minecraft server thread. */
	public static Optional<com.nobodiiiii.createbiotech.content.factorycluster.diagnostics.FactoryGoggleProjection.Radio> normalRadioState(MinecraftServer server, RadioEndpoint.Key key) {
		if(!server.isSameThread()) throw new IllegalStateException("normal radio capture off server thread");
		Runtime runtime; synchronized(FactoryOrchestrationServer.class) { runtime=RUNTIMES.get(server); }
		if(runtime==null) return Optional.empty();
		RadioEndpoint source=runtime.radioEndpoints.get(key);
		return source==null?Optional.empty():com.nobodiiiii.createbiotech.content.factorycluster.diagnostics.FactoryGoggleCapture.radio(source,java.util.stream.Stream.concat(runtime.radioEndpoints.values().stream(),runtime.listenerRelayCandidates().stream()).toList(),runtime.radioTopologyFrame(),runtime.radioLinkPolicy,server.overworld().getGameTime());
	}
	/** Read-only owner proof projection; never starts a runtime, process, query or production admission. */
	public static com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority
		patternBrowserCoordination(MinecraftServer server, UUID clusterId) {
		if (!server.isSameThread()) throw new IllegalStateException("browser coordination capture off server thread");
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime == null) return com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority.empty();
		return runtime.runtime.peekSnapshotForPanelView(clusterId)
			.map(com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority::fromAuthoritativeState)
			.orElseGet(com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority::empty);
	}

	/** Read-only production Library workload counters; this never starts or pumps a runtime. */
	public static com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService.LibraryWorkloadDiagnostics
		patternLibraryWorkload(MinecraftServer server, UUID clusterId) {
		if (!server.isSameThread()) throw new IllegalStateException("Library workload capture off server thread");
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null
			? new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService.LibraryWorkloadDiagnostics(0, 0)
			: runtime.runtime.libraryWorkloadDiagnostics(Objects.requireNonNull(clusterId, "clusterId"));
	}

    // These historical endpoints can only target an explicitly installed fixture.
    // Looking at a loaded book must never create a server worker or open the old journal.
    public static boolean enqueueLegacyLibraryQuery(MinecraftServer server, UUID group,
            com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery query) {
        Runtime owner;
        synchronized (FactoryOrchestrationServer.class) { owner = RUNTIMES.get(server); }
        if (owner == null || !server.isSameThread() || !owner.acceptsWork()) return false;
        var state = owner.runtime.snapshot(group).orElse(null);
        return state != null && owner.runtime.enqueueLibraryQuery(group, state.epochId(), query);
    }

    public static List<com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternReply>
            drainLegacyLibraryReplies(MinecraftServer server, UUID group, UUID requester, int maximum) {
        Runtime owner;
        synchronized (FactoryOrchestrationServer.class) { owner = RUNTIMES.get(server); }
        return owner == null || !server.isSameThread() || !owner.acceptsWork() ? List.of()
            : owner.runtime.drainLibraryReplies(group, requester, maximum);
    }

	public static java.util.OptionalInt normalComputerUsedSlots(MinecraftServer server, UUID clusterId, UUID computerId) {
		if(!server.isSameThread()) throw new IllegalStateException("normal Computer capture off server thread");
		Runtime runtime; synchronized(FactoryOrchestrationServer.class) { runtime=RUNTIMES.get(server); }
		if(runtime==null) return java.util.OptionalInt.empty();
		var snapshot=runtime.runtime.snapshot(clusterId);
		return snapshot.isEmpty()?java.util.OptionalInt.empty():java.util.OptionalInt.of((int)snapshot.orElseThrow().processes().values().stream()
			.filter(process->process.owningComputerId().equals(computerId) && process.state()!=com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState.TERMINAL).count());
	}
	/** Exact-target advanced view: read existing authority only, never start or drain a runtime. */
 public static void captureAdvancedGoggles(net.minecraft.world.level.block.entity.BlockEntity be,
   UUID clusterId,UUID memberId,com.nobodiiiii.createbiotech.content.factorycluster.diagnostics.FactoryAdvancedGoggleCapture.Rows rows) {
  com.nobodiiiii.createbiotech.content.factorycluster.diagnostics.FactoryGoggleCapture.requireServerThread(be);
  var level=(ServerLevel)be.getLevel();var server=level.getServer();
  Runtime runtime; synchronized(FactoryOrchestrationServer.class){runtime=RUNTIMES.get(server);}
  if(runtime==null){rows.add(rows.errors,"runtime=UNAVAILABLE");return;}
  if(clusterId!=null) {
   var state=runtime.runtime.peekSnapshotForPanelView(clusterId).orElse(null);
   if(state!=null) {
    rows.add(rows.formation,"engineGeneration="+state.generation()+" epoch="+state.epochId());
    state.processes().values().stream().filter(process->process.owningComputerId().equals(memberId)).sorted(java.util.Comparator.comparingInt(process->process.owningSlot())).limit(rows.limit).forEach(process->rows.add(rows.slots,"slot="+process.owningSlot()+" process="+process.processId()+" goal="+process.goalId()+" generation="+process.generation()+" state="+process.state()+" wait="+process.waitReason()));
   }
   var key=new RadioEndpoint.Key(clusterId,memberId);var endpoint=runtime.radioEndpoints.get(key);
   if(endpoint!=null&&endpoint.address().equals(SpaceAddress.capture(level,be.getBlockPos()))) {
    rows.add(rows.routes,"radio="+key+" generation="+endpoint.generation()+" frameTick="+runtime.radioTopologyFrame().sampleTick());
    rows.add(rows.listeners,"listenerOccupancy="+runtime.radioRouter.listenerOccupancy(key));
    runtime.radioRouter.diagnosticRouteRows(key,rows.limit).forEach(line->rows.add(rows.routes,line));
   }
  }

 }

	/** Test-only read seam for the server router's exact endpoint ownership. */
	public static boolean radioEndpointRegisteredForTest(MinecraftServer server,
		RadioEndpoint.Key key) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime != null && runtime.radioEndpointRegisteredForTest(key);
	}

	/** Test-only read seam for the exact current endpoint used by production dispatch. */
	public static Optional<RadioEndpoint> radioEndpointForTest(MinecraftServer server,
		RadioEndpoint.Key key) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime == null ? Optional.empty() : runtime.radioEndpointForTest(key);
	}

	/** Test-only read seam for the production external-listener projection. */
	public static List<ExternalListenerService.ListenerProjection> externalListenerProjectionForTest(
		MinecraftServer server, UUID computerId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime == null ? List.of() : runtime.externalListenerProjectionForTest(computerId);
	}

	/** Test-only read seam for exact router-backed listener source/relay publications. */
	public static List<ExternalListenerService.Publication> externalListenerPublicationsForTest(
		MinecraftServer server, UUID computerId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime == null ? List.of() : runtime.externalListenerPublicationsForTest(computerId);
	}

	/** Test-only read seam for the automatic relay registry's exact active key. */
	public static boolean externalListenerRelayCandidateForTest(MinecraftServer server,
		RadioEndpoint.Key relayKey) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime != null && runtime.externalListenerRelayCandidateForTest(relayKey);
	}

	/** Test-only result from the production frozen-frame physical link policy. */
	public static RadioLinkPolicy.LinkResult radioLinkForTest(MinecraftServer server,
		RadioEndpoint source, RadioEndpoint target) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime == null ? RadioLinkPolicy.LinkResult.UNAVAILABLE
			: runtime.radioLinkForTest(source, target);
	}

	/** Read-only projection of public subnet capabilities derived from the live formed runtime. */
	public static Optional<SubnetBoundaryService.SubnetBoundaryProjection> publicSubnetProjectionForTest(
		MinecraftServer server, RadioEndpoint.Key activeListenerRelayKey) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime == null ? Optional.empty()
			: runtime.publicSubnetProjectionForTest(activeListenerRelayKey);
	}

	/** Exact server-owned Agent claim resolution used by production diagnostics and acceptance. */
	public static Optional<AgentResolution> productionAgentResolution(MinecraftServer server,
		CreateRouteKey route, UUID requesterClusterId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime == null ? Optional.empty() : Optional.of(runtime.agentDirectory.resolve(
			Objects.requireNonNull(route, "route"),
			Objects.requireNonNull(requesterClusterId, "requesterClusterId"),
			runtime.requesterFrequency(requesterClusterId, null)));
	}

	/** Auditable production drain evidence, including durable state for unloaded Agents. */
	public static Optional<AgentRevocationDrainState> agentRevocationDrainState(
		MinecraftServer server, UUID clusterId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime == null ? Optional.empty()
			: Optional.of(runtime.agentRevocationDrainState(
				Objects.requireNonNull(clusterId, "clusterId")));
	}

	/** Registers one ordinary production provider in the server-owned route collector. */
	public static boolean registerOrdinaryProvider(MinecraftServer server,
		OrdinaryProviderRegistry.Registration registration) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime != null && runtime.registerOrdinaryProvider(
			Objects.requireNonNull(registration, "registration"));
	}

	public static boolean unregisterOrdinaryProvider(MinecraftServer server, UUID providerId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime != null && runtime.unregisterOrdinaryProvider(
			Objects.requireNonNull(providerId, "providerId"));
	}

    /** Historical save replay fixture only. Worker and journal must be supplied; never loaded by game events. */
    static void installHistoricalFixture(MinecraftServer server, FactoryRuntimeConfig config,
            FactoryRuntimeService service, FactoryOrchestrationSavedData journal) {
        installRuntime(server, s -> new RuntimeComposition(s, config, service, journal, s.registryAccess(),
            FactoryOrchestrationServer::attachedNetworksFromCreate, NativePackageObservation::install,
            () -> liveLogisticsNetworksFromCreate(s)), true);
    }

	/** Package-private composition seam; the immutable facade lives under src/test. */
	static void installRuntimeForTest(MinecraftServer server, FactoryRuntimeConfig config,
		FactoryRuntimeService runtime, FactoryOrchestrationSavedData journal,
		Function<PackagerBlockEntity, Set<UUID>> attachedNetworks) {
		installRuntimeForTest(server, config, runtime, journal, attachedNetworks,
			NativePackageObservation::install);
	}

	/** Package-private fault-injection seam; it cannot publish over an existing Runtime. */
	static void installRuntimeForTest(MinecraftServer server, FactoryRuntimeConfig config,
		FactoryRuntimeService runtime, FactoryOrchestrationSavedData journal,
		Function<PackagerBlockEntity, Set<UUID>> attachedNetworks,
		NativePackageBindingInstaller nativeBindingInstaller) {
		server = Objects.requireNonNull(server, "server");
		MinecraftServer exactServer = server;
		installRuntime(server, ignored -> RuntimeComposition.test(exactServer, config, runtime, journal,
			attachedNetworks, nativeBindingInstaller), true);
	}

	/** Narrow test seam for live-network discovery at the production refresh boundary. */
	static void installRuntimeForTest(MinecraftServer server, FactoryRuntimeConfig config,
		FactoryRuntimeService runtime, FactoryOrchestrationSavedData journal,
		Function<PackagerBlockEntity, Set<UUID>> attachedNetworks,
		Supplier<Set<UUID>> liveLogisticsNetworks) {
		server = Objects.requireNonNull(server, "server");
		MinecraftServer exactServer = server;
		installRuntime(server, ignored -> RuntimeComposition.test(exactServer, config, runtime,
			journal, attachedNetworks, NativePackageObservation::install,
			liveLogisticsNetworks), true);
	}

	private static void installRuntime(MinecraftServer server,
		RuntimeCompositionFactory compositionFactory, boolean rejectExisting) {
		server = Objects.requireNonNull(server, "server");
		compositionFactory = Objects.requireNonNull(compositionFactory, "compositionFactory");
		InstallationReservation reservation;
		synchronized (FactoryOrchestrationServer.class) {
			if (RUNTIMES.containsKey(server) || INSTALLING.containsKey(server)) {
				if (rejectExisting) throw new IllegalStateException("runtime already installed");
				return;
			}
			reservation = new InstallationReservation();
			INSTALLING.put(server, reservation);
		}
		Runtime installed = null;
		boolean published = false;
		try {
			installed = new Runtime(compositionFactory.create(server));
			installed.activate();
			synchronized (FactoryOrchestrationServer.class) {
				if (INSTALLING.get(server) != reservation || RUNTIMES.containsKey(server))
					throw new IllegalStateException("runtime installation reservation was lost");
				RUNTIMES.put(server, installed);
				installed.published = true;
				INSTALLING.remove(server);
				published = true;
			}
		} catch (RuntimeException | Error failure) {
			if (installed != null) {
				try { installed.close(); }
				catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
			}
			throw failure;
		} finally {
			if (!published) synchronized (FactoryOrchestrationServer.class) {
				if (INSTALLING.get(server) == reservation) INSTALLING.remove(server);
			}
		}
	}

	static boolean observeExternalListenerForTest(MinecraftServer server,
		ProductionComputer computer, CommunicationFrequency primary, SpaceAddress address) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime != null && runtime.observeExternalListenerForTest(computer, primary, address);
	}

	static ExternalListenerService.EditResult replaceExternalListenersForTest(
		MinecraftServer server, ProductionComputer computer, long expectedRevision,
		List<CommunicationFrequency> frequencies) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? ExternalListenerService.EditResult.STOPPING
			: runtime.replaceExternalListeners(computer, expectedRevision, frequencies);
	}

	static boolean hasRadioBoundaryForTest(MinecraftServer server, UUID clusterId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime != null && runtime.radioRouter.boundaryBinding(clusterId).isPresent();
	}

	static TaskControlState taskControlStateForTest(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime == null) throw new IllegalStateException("runtime not installed");
		return new TaskControlState(runtime.suspendedInputDispatchClusters,
			runtime.droppedRecoveryAcknowledgements, runtime.recoveryAdmissionCeilings);
	}

	static RuntimeAuthoritySnapshot runtimeAuthoritySnapshotForTest(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime == null) throw new IllegalStateException("runtime not installed");
		return new RuntimeAuthoritySnapshot(runtime.radioRouter.authorityRevision(),
			runtime.radioRouter.activeRouteCount(), runtime.radioOutbox.snapshot());
	}

	static long radioTopologySampleTickForTest(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime == null) throw new IllegalStateException("runtime not installed");
		return runtime.radioTopology.frame().sampleTick();
	}

	public static RadioRouter.AuthorityState radioAuthorityStateForTest(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime == null) throw new IllegalStateException("runtime not installed");
		return runtime.radioRouter.authorityStateForTest();
	}

	static boolean offerRadioForTest(MinecraftServer server, RadioEnvelope envelope,
		List<RadioEndpoint.Hop> relays) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime == null) throw new IllegalStateException("runtime not installed");
		return runtime.offerRadioForTest(envelope, relays);
	}

	static List<RadioRouter.Delivery> pumpRadioOutboxForTest(MinecraftServer server,
		long serverTick) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime == null) throw new IllegalStateException("runtime not installed");
		return runtime.pumpRadioOutboxForTest(serverTick);
	}

	static boolean markRadioAppliedForTest(MinecraftServer server, UUID messageId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime == null) throw new IllegalStateException("runtime not installed");
		return runtime.markRadioAppliedForTest(messageId);
	}

	static int retireAppliedRadioRoutesForTest(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime == null) throw new IllegalStateException("runtime not installed");
		return runtime.retireAppliedRadioRoutesForTest();
	}

	static boolean hasNativePublisherForTest(MinecraftServer server) {
		return NativePackageObservation.publisherInstalled(Objects.requireNonNull(server, "server"));
	}

	record TaskControlState(Set<UUID> suspendedInputDispatchClusters,
		Set<UUID> droppedRecoveryAcknowledgements, Map<UUID, Integer> recoveryAdmissionCeilings) {
		TaskControlState {
			suspendedInputDispatchClusters = Set.copyOf(suspendedInputDispatchClusters);
			droppedRecoveryAcknowledgements = Set.copyOf(droppedRecoveryAcknowledgements);
			recoveryAdmissionCeilings = Map.copyOf(recoveryAdmissionCeilings);
		}
	}

	record RuntimeAuthoritySnapshot(long routerAuthorityRevision, int activeRouteCount,
		DurableRadioOutbox.State outbox) {
		RuntimeAuthoritySnapshot { outbox = Objects.requireNonNull(outbox, "outbox"); }
	}

	/** Freezes the live runtime before NeoForge releases the server's physical resources. */
	public static void beginOrderlyShutdown(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		if (runtime != null) runtime.freezeForOrderlyShutdown();
	}

	/** Test-only read seam for the server-owned orderly-shutdown lifecycle. */
	public static FactoryRuntimeLifecycle.Phase runtimePhaseForTest(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) {
			runtime = RUNTIMES.get(Objects.requireNonNull(server, "server"));
		}
		return runtime == null ? FactoryRuntimeLifecycle.Phase.STOPPED : runtime.lifecycle.phase();
	}

	public static synchronized void stop(MinecraftServer server) {
		Runtime runtime = RUNTIMES.remove(Objects.requireNonNull(server, "server"));
		if (runtime != null) {
			runtime.markStopped();
			runtime.close();
		}
	}

	public static void tick(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime != null) runtime.tick();
	}

	public static boolean commitIntent(MinecraftServer server, ProductionEffect intent) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime != null && runtime.effectCommitter.commit(intent);
	}

	/**
	 * Suspends one Cluster's next native input dispatch after its ATTEMPTING marker is durable.
	 * Used by lifecycle verification and by controlled shutdown tooling; it never performs or
	 * retries the native Create action.
	 */
	public static boolean suspendNextInputDispatchAtDurableAttempt(MinecraftServer server,
		UUID clusterId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime != null && runtime.suspendNextInputDispatch(clusterId);
	}

	public static Optional<CreateLogisticsGateway.CommitAttemptState> inputCommitAttemptState(
		MinecraftServer server, UUID goalId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? Optional.empty() : runtime.inputCommitAttemptState(goalId);
	}

	public static Optional<RemoteRecoveryPhase> remoteRecoveryStatus(MinecraftServer server,
		UUID goalId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? Optional.empty() : runtime.remoteRecoveryStatus(goalId);
	}

	public static Optional<String> lastPanelSubmissionDiagnostic(MinecraftServer server,
		UUID clusterId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? Optional.empty()
			: Optional.ofNullable(runtime.lastPanelSubmissionDiagnostics.get(clusterId));
	}

	/** Read-only durable admission evidence, including requests still crossing worker boundaries. */
	public static List<PendingProductionAdmission> pendingProductionAdmissions(
		MinecraftServer server, UUID clusterId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime == null) return List.of();
		return runtime.productionAdmissions.values().stream()
			.filter(admission -> admission.clusterId().equals(Objects.requireNonNull(clusterId)))
			.sorted(Comparator.comparing(PendingProductionAdmission::admissionId)).toList();
	}

	/**
	 * Drops one recovery ACK at the durable outbox boundary. This is a controlled fault boundary
	 * for lifecycle verification: terminal evidence remains durable and the next composition-root
	 * start must reconcile it through StatusQuery rather than redispatching material.
	 */
	public static boolean dropNextRecoveryAcknowledgement(MinecraftServer server, UUID goalId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime != null && runtime.dropNextRecoveryAcknowledgement(goalId);
	}

	/** Current correctness occupancy, including acknowledged lineages that still fence retries. */
	public static int recoveryCorrectnessOccupancy(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? -1 : runtime.recoveryCorrectnessOccupancy();
	}

	/**
	 * Non-destructively reserves only the requested additional recovery headroom. This is suitable
	 * for live pressure drills and never removes or rewrites existing durable evidence.
	 */
	public static boolean constrainRecoveryAdmissionHeadroom(MinecraftServer server,
		UUID clusterId, int additionalRecords) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime != null && runtime.constrainRecoveryAdmissionHeadroom(clusterId,
			additionalRecords);
	}

	public static boolean restoreConfiguredRecoveryAdmission(MinecraftServer server,
		UUID clusterId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime != null && runtime.restoreConfiguredRecoveryAdmission(clusterId);
	}

	/** Releases only an uncommitted in-memory submission marker after authoritative rejection. */
	public static void intentResolved(MinecraftServer server, ProductionEffect intent,
		boolean committed) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime != null) runtime.intentResolved(intent, committed);
	}

	public static boolean routeRadio(MinecraftServer server, RadioEnvelope envelope,
		List<RadioEndpoint.Hop> explicitRelays) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime != null && runtime.routeRadio(envelope, explicitRelays);
	}

	/** Read-only production route evidence for Panels, diagnostics, and vertical tests. */
	public static Optional<RadioRouter.RouteStatus> radioStatus(MinecraftServer server,
		UUID messageId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? Optional.empty() : runtime.radioStatus(messageId);
	}

	/** Read-only status of the durable radio handoff owned by a source production Goal. */
	public static Optional<RadioRouter.RouteStatus> remoteAgentRadioStatus(MinecraftServer server,
		UUID goalId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? Optional.empty() : runtime.remoteAgentRadioStatus(goalId);
	}

	/** Read-only evidence that a source-owned remote Agent dispatch passed its first frozen relay. */
	public static boolean remoteAgentRadioProgressedPastFirstRelay(MinecraftServer server,
		UUID goalId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime != null && runtime.remoteAgentRadioProgressedPastFirstRelay(goalId);
	}

	/** Read-only router/outbox evidence for the exact first relay currently holding a dispatch. */
	public static Optional<RadioEndpoint.Key> remoteAgentCurrentRelayForTest(MinecraftServer server,
		UUID goalId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? Optional.empty() : runtime.remoteAgentCurrentRelayForTest(goalId);
	}

	/** Frozen source ownership retained by the production pure-computation lease. */
	public static Optional<UUID> radioGoalOwner(MinecraftServer server, UUID leaseId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? Optional.empty() : runtime.radioGoalOwner(leaseId);
	}

	/** Durable production evidence for the source-owned pure-computation lease of a Goal. */
	public static Optional<RemoteComputationExchange> productionRemoteComputation(
		MinecraftServer server, UUID goalId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? Optional.empty() : runtime.remoteComputation(goalId);
	}

	/** Receiver-owned durable allocation and worker evidence for a source production Goal. */
	public static Optional<RemoteComputationJob> productionRemoteComputationJob(
		MinecraftServer server, UUID goalId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? Optional.empty() : runtime.remoteComputationJob(goalId);
	}

	/** Same-Cluster virtual pure-computation evidence; it never owns a radio route. */
	public static Optional<Job> productionLocalComputationJob(
		MinecraftServer server, UUID goalId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? Optional.empty() : runtime.localComputationJob(goalId);
	}

	public static int productionLocalComputationCount(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? 0 : runtime.localComputationCount();
	}

	public static int productionRemoteComputationActiveLeaseCount(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? 0 : runtime.remoteComputationActiveLeaseCount();
	}

	public static int productionRemoteComputationActiveRouteCount(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? 0 : runtime.remoteComputationActiveRouteCount();
	}

	/** Read-only route-scan evidence scoped to one exact remote-computation lease. */
	public static int productionRemoteComputationActiveRouteCount(MinecraftServer server,
		UUID leaseId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? 0 : runtime.remoteComputationActiveRouteCount(leaseId);
	}

	public static int productionRetiredRemoteComputationCount(MinecraftServer server) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? 0 : runtime.retiredRemoteComputationCount();
	}

	public static int productionRetiredRemoteComputationCount(MinecraftServer server,
		UUID sourceGoalId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? 0
			: runtime.retiredRemoteComputationCount(sourceGoalId);
	}

	/** Read-only authorization preview; callers still need a router-issued one-shot permit. */
	public static boolean radioCanCommitMaterial(MinecraftServer server,
		RadioEndpoint.Key receiver, UUID leaseId, long revision) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime != null && runtime.radioCanCommitMaterial(receiver, leaseId, revision);
	}

	/** Read-only production lifecycle evidence used by the Panel and vertical diagnostics. */
	public static Optional<ProductionStatus> productionStatus(MinecraftServer server, UUID goalId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? Optional.empty() : runtime.productionStatus(goalId);
	}

	/** Exact durable plan evidence exposed to the Panel and vertical production diagnostics. */
	public static Optional<FrozenProductionPlan> productionFrozenPlan(MinecraftServer server,
		UUID goalId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? Optional.empty()
			: Optional.ofNullable(runtime.planRepository.current(Objects.requireNonNull(goalId)));
	}

	public record ProductionStatus(UUID runId, boolean dispatchSubmitted,
		Optional<com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputPhase> inputPhase,
		Optional<ProductionOutputContract.Milestone> outputMilestone,
		Optional<WaitReason> outputWaitReason, Map<CreateRouteKey, Long> outputCounts) {
		public ProductionStatus {
			runId = Objects.requireNonNull(runId, "runId");
			inputPhase = Objects.requireNonNull(inputPhase, "inputPhase");
			outputMilestone = Objects.requireNonNull(outputMilestone, "outputMilestone");
			outputWaitReason = Objects.requireNonNull(outputWaitReason, "outputWaitReason");
			outputCounts = Map.copyOf(Objects.requireNonNull(outputCounts, "outputCounts"));
		}
	}

	/** Publishes and journals the exact Core-owned Library routing/load registration. */
	public static boolean publishProductionLibrary(MinecraftServer server, UUID libraryId,
		UUID clusterId, Set<UUID> logisticsIds, long bindingRevision, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency,
		int priority, SpaceAddress address, boolean loaded, ProductionRecipeCatalog catalog) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime != null && runtime.publishProductionLibrary(libraryId, clusterId,
			logisticsIds, bindingRevision, frequency, priority, address, loaded, catalog);
	}

	public static Optional<DurableProductionLibrary> productionLibraryRegistration(
		MinecraftServer server, UUID libraryId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? Optional.empty()
			: Optional.ofNullable(runtime.productionLibraries.get(Objects.requireNonNull(libraryId)));
	}

	/** Immutable last-seen address projection for one exact persistent logistics UUID. */
	public static List<LogisticsAddressComponent> logisticsAddressSnapshot(
		MinecraftServer server, UUID logisticsNetworkId) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? List.of()
			: runtime.logisticsAddressObserver.snapshot(logisticsNetworkId);
	}



	/** Server-only Agent-domain fixture/debug projection; never serialized by the Panel protocol. */
	public static List<AgentDiagnostic> agentDiagnostics(MinecraftServer server, UUID clusterId) {
		Objects.requireNonNull(server, "server");
		Runtime runtime; synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? List.of() : runtime.agentDiagnostics(clusterId);
	}

	/** Server-only Task-domain fixture/debug projection; never combined with Agent state. */
	public static List<GoalDiagnostic> goalDiagnostics(MinecraftServer server, UUID clusterId) {
		Objects.requireNonNull(server, "server");
		Runtime runtime; synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		return runtime == null ? List.of() : runtime.goalDiagnostics(clusterId);
	}



	public record AgentDiagnostic(UUID agentId, @Nullable UUID residentProcessId,
		@Nullable UUID activeRunId, long resetGeneration, long stateRevision) {}

	public record ProviderDiagnostic(@Nullable UUID libraryId,
		@Nullable RouteCandidate.ProviderKind kind, String key, int effectiveRoutePriority) {}

	public record ProcessDiagnostic(UUID processId,
		com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind kind, com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState state, @Nullable WaitReason waitReason) {}

	public record GoalDiagnostic(UUID goalId, long requestedActualCount,
		long receivedActualCount, long remainingActualCount, GoalState state,
		@Nullable WaitReason waitReason, ProviderDiagnostic provider,
		List<ProcessDiagnostic> processes) {}










	/** Routes durable listener edits through the server-owned runtime when the Computer is live. */
	public static ExternalListenerService.EditResult replaceExternalListeners(
		ProductionComputer computer, long expectedRevision, List<CommunicationFrequency> frequencies) {
		Objects.requireNonNull(computer, "computer");
		if (computer.owner().getLevel() == null || computer.owner().getLevel().isClientSide)
			return ExternalListenerService.replaceConfiguration(computer, expectedRevision, frequencies);
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(computer.owner().getLevel().getServer()); }
		return runtime == null
			? ExternalListenerService.replaceConfiguration(computer, expectedRevision, frequencies)
			: runtime.replaceExternalListeners(computer, expectedRevision, frequencies);
	}
	/** Server ingress gateway for a formed Trader listener; raw traffic never bypasses Goal/lease proof. */
	public static Optional<RadioEnvelope> forwardExternalListenerIngress(ProductionComputer computer,
		CommunicationFrequency frequency, RadioEnvelope envelope,
		ExternalListenerService.IngressAuthority authority) {
		Objects.requireNonNull(computer, "computer");
		if (computer.owner().getLevel() == null || computer.owner().getLevel().isClientSide) return Optional.empty();
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(computer.owner().getLevel().getServer()); }
		return runtime == null ? Optional.empty()
			: runtime.forwardExternalListenerIngress(computer, frequency, envelope, authority);
	}
	public static void nativeComputerObserved(ProductionComputer computer) {
		if (computer.owner().getLevel() == null || computer.owner().getLevel().isClientSide) return;
		MinecraftServer server = computer.owner().getLevel().getServer();
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime == null) return;
		if (!runtime.acceptsWork()) return;
		if (!computer.isAuthoritativeLoadedInstance()) {
			runtime.computerUnloaded(computer);
			return;
		}
		computer.refreshShellMembershipForLifecycle();
		runtime.computerObserved(computer);
	}

	public static void nativeComputerUnloaded(ProductionComputer computer) {
		if (computer.owner().getLevel() == null || computer.owner().getLevel().isClientSide) return;
		MinecraftServer server = computer.owner().getLevel().getServer();
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime != null) runtime.computerUnloaded(computer);
	}

	/** Immediate, conclusive shell-teardown fence; unlike unload it leaves no unavailable ghost. */
	public static void nativeComputerRetired(ProductionComputer computer) {
		if (computer.owner().getLevel() == null || computer.owner().getLevel().isClientSide) return;
		MinecraftServer server = computer.owner().getLevel().getServer();
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime != null) runtime.computerRetired(computer);
	}

	/** Temporary backend boundary: the new Library application selects affected production groups. */
	public static void productionLibraryTopologyChanged(MinecraftServer server, UUID groupId) {
		Runtime runtime;
		synchronized (FactoryOrchestrationServer.class) { runtime = RUNTIMES.get(server); }
		if (runtime != null) {
			runtime.requireServerThread();
			if (runtime.acceptsWork()) runtime.redriveClusterTopology(groupId);
		}
	}

	@FunctionalInterface
	private interface RuntimeCompositionFactory {
		RuntimeComposition create(MinecraftServer server);
	}

	@FunctionalInterface
	interface NativePackageBindingInstaller {
		AutoCloseable install(MinecraftServer server,
			NativePackageObservation.AcceptancePublisher publisher);
	}

	private record RuntimeComposition(MinecraftServer server, FactoryRuntimeConfig config,
		FactoryRuntimeService runtime, FactoryOrchestrationSavedData journal,
		HolderLookup.Provider registryAccess,
		Function<PackagerBlockEntity, Set<UUID>> attachedNetworks,
		NativePackageBindingInstaller nativeBindingInstaller,
		Supplier<Set<UUID>> liveLogisticsNetworks) {
		private RuntimeComposition {
			server = Objects.requireNonNull(server, "server");
			config = Objects.requireNonNull(config, "config");
			runtime = Objects.requireNonNull(runtime, "runtime");
			journal = Objects.requireNonNull(journal, "journal");
			registryAccess = Objects.requireNonNull(registryAccess, "registryAccess");
			attachedNetworks = Objects.requireNonNull(attachedNetworks, "attachedNetworks");
			nativeBindingInstaller = Objects.requireNonNull(nativeBindingInstaller,
				"nativeBindingInstaller");
			liveLogisticsNetworks = Objects.requireNonNull(liveLogisticsNetworks,
				"liveLogisticsNetworks");
		}

		private static RuntimeComposition test(MinecraftServer server, FactoryRuntimeConfig config,
			FactoryRuntimeService runtime, FactoryOrchestrationSavedData journal,
			Function<PackagerBlockEntity, Set<UUID>> attachedNetworks) {
			return test(server, config, runtime, journal, attachedNetworks,
				NativePackageObservation::install);
		}

		private static RuntimeComposition test(MinecraftServer server, FactoryRuntimeConfig config,
			FactoryRuntimeService runtime, FactoryOrchestrationSavedData journal,
			Function<PackagerBlockEntity, Set<UUID>> attachedNetworks,
			NativePackageBindingInstaller nativeBindingInstaller) {
			return test(server, config, runtime, journal, attachedNetworks,
				nativeBindingInstaller, () -> liveLogisticsNetworksFromCreate(server));
		}

		private static RuntimeComposition test(MinecraftServer server, FactoryRuntimeConfig config,
			FactoryRuntimeService runtime, FactoryOrchestrationSavedData journal,
			Function<PackagerBlockEntity, Set<UUID>> attachedNetworks,
			NativePackageBindingInstaller nativeBindingInstaller,
			Supplier<Set<UUID>> liveLogisticsNetworks) {
			return new RuntimeComposition(server, config, runtime, journal,
				net.minecraft.core.RegistryAccess.EMPTY, attachedNetworks, nativeBindingInstaller,
				liveLogisticsNetworks);
		}
	}

	private static Set<UUID> liveLogisticsNetworksFromCreate(MinecraftServer server) {
		LinkedHashSet<UUID> present = new LinkedHashSet<>();
		for (UUID network : List.copyOf(Create.LOGISTICS.logisticsNetworks.keySet())) {
			boolean presentOnServer = LogisticallyLinkedBehaviour.getAllPresent(network, false)
				.stream().map(link -> link.blockEntity).anyMatch(owner -> owner.getLevel() != null
					&& !owner.getLevel().isClientSide && owner.getLevel().getServer() == server);
			if (presentOnServer) present.add(network);
		}
		return Set.copyOf(present);
	}

	private static Set<UUID> attachedNetworksFromCreate(PackagerBlockEntity packager) {
		return Arrays.stream(Direction.values()).map(direction -> {
			if (!(packager.getLevel().getBlockEntity(packager.getBlockPos().relative(direction))
				instanceof PackagerLinkBlockEntity link) || link.getPackager() != packager)
				return null;
			return link.behaviour.freqId;
		}).filter(Objects::nonNull).collect(Collectors.toSet());
	}

	private static final class Runtime implements AutoCloseable,
		NativePackageObservation.AcceptancePublisher {
		private static final AutoCloseable NOOP_BINDING = () -> {};
		private static final String GOAL_PREFIX = "goal_observation/";
		private static final String PRODUCTION_SEARCH_PREFIX = "production_search/";
		private static final String PRODUCTION_ADMISSION_KEY = "production_admission/state";
		private static final String PRODUCTION_LIBRARY_PREFIX = "production_library/";
		private static final String OUTPUT_POLICY_PREFIX = "output_policy/";
		private static final String OUTPUT_PROBE_PREFIX = "output_probe/";
		private static final String SIGNAL_PROBE_PREFIX = "signal_probe/";
		private static final String NATIVE_FAILURE_PREFIX = "native_observation_failure/";

		private final MinecraftServer server;
		private final FactoryRuntimeConfig config;
		private final FactoryRuntimeService runtime;
		private final FactoryOrchestrationSavedData journal;
		private final HolderLookup.Provider registryAccess;
		private final Function<PackagerBlockEntity, Set<UUID>> attachedNetworks;
		private final NativePackageBindingInstaller nativeBindingInstaller;
		private final Supplier<Set<UUID>> liveLogisticsNetworks;
		private final ClusterCommunicationDirectory communicationDirectory;
		private final ClusterCommunicationCommandService communicationCommands;
		private final AgentAddressDirectory agentDirectory;
		private final LogisticsAddressObservationRegistry logisticsAddressObservations;
		private final LogisticsAddressObserver logisticsAddressObserver;
		private final InventoryIdentity.PersistentAttachmentBindingRegistry attachmentBindings;
		private final StockObservationService stockObservations;
		private final RecoveryLedger recovery;
		private final RadioLinkPolicy radioLinkPolicy;
		private final RadioRouter radioRouter;
		private final ExternalListenerRuntime externalListeners;
		private final DurableRadioOutbox radioOutbox;
		private final RadioTopologyLifecycle radioTopology;
		private final RadioTopologyPublicationAuthority radioTopologyPublications;
		private final ClusterPrimaryFrequencyResolver primaryFrequencyResolver;
		private final Map<RadioEndpoint.Key, RadioEndpoint> radioEndpoints = new LinkedHashMap<>();
		private final Map<RadioEndpoint.Key, RadioComputerOwner> radioComputerOwners = new LinkedHashMap<>();
		private record RadioComputerOwner(ProductionComputer computer, RadioEndpoint endpoint,
			RadioTopologyLifecycle.Registration registration, ExternalListenerService.ListenerOwner listenerOwner) {}
		private final Map<SpaceAddress, WeakReference<FactoryPanelBlockEntity>> nativePanels =
			new LinkedHashMap<>();
		private final Map<SpaceAddress, AgentEndpointState> endpoints = new LinkedHashMap<>();
		private final Map<UUID, AgentCoordinator> agents = new LinkedHashMap<>();
		private final Map<UUID, AgentResetService> resetServices = new LinkedHashMap<>();
		private final Map<String, ComputerMonitorSnapshot.RevisionSequence> monitorSequences =
			new LinkedHashMap<>();
		private final Map<UUID, AgentInputContract> inputContracts = new LinkedHashMap<>();
		private final Map<UUID, ProductionOutputPolicy> outputPolicies = new LinkedHashMap<>();
		private final Map<UUID, ProductionOutputContract> outputContracts = new LinkedHashMap<>();
		private final Map<UUID, OutputProbeState> outputProbes = new LinkedHashMap<>();
		private final Map<UUID, SignalProbeState> signalProbes = new LinkedHashMap<>();
		private final Map<UUID, OutputNativeReceiptState> outputNativeReceipts =
			new LinkedHashMap<>();
		private final Map<UUID, ProductionOutputCapacity> outputCapacities = new LinkedHashMap<>();
		private final Map<UUID, RemoteRequestKey> requestKeysByRun = new LinkedHashMap<>();
		private final ProductionGoalSettlement goalSettlement;
		private final ProductionEffectCommitter effectCommitter;
		private final ProductionInputDispatch inputDispatch;
		private final ProductionOutputDispatch<OutputProbeState, PreparedOutput> outputDispatch;
		private final ProductionSafeRetryRecovery<SafeCreateRetryProof> safeRetryRecovery;
		private final ProductionPlanRepository planRepository;
		private final ProductionAdmissions productionAdmissions;
		private final Map<UUID, DurableProductionLibrary> productionLibraries =
			new LinkedHashMap<>();
		private final OrdinaryProviderRegistry ordinaryProviders =
			new OrdinaryProviderRegistry();
		private final Map<UUID, RemoteAgentRadioDispatch> remoteAgentDispatches =
			new LinkedHashMap<>();
		private final ProductionLocalExpansion localExpansion;
		private final ProductionRemoteExpansion remoteExpansion;
		private final Map<RemoteRequestKey, RemoteRecoveryExchange> remoteRecoveryExchanges =
			new LinkedHashMap<>();
		@Nullable private UUID deliveredRadioRetryCursor;
		private final ArrayDeque<UUID> remoteMaterialHandoffReplayQueue = new ArrayDeque<>();
		private final Set<UUID> queuedRemoteMaterialHandoffRuns = new HashSet<>();
		private final Map<UUID, PendingCreateRejection>
			rejectedCreateIntentsAwaitingConfirmation = new LinkedHashMap<>();
		private final ArrayDeque<UUID> rejectedCreateConfirmationQueue = new ArrayDeque<>();
		private final ProductionSafeRetryStore safeCreateRetries;
		private final ProductionPlanDispatch planDispatch;
		private final Set<UUID> suspendedInputDispatchClusters = new HashSet<>();
		private final Set<UUID> droppedRecoveryAcknowledgements = new HashSet<>();
		private final Map<UUID, Integer> recoveryAdmissionCeilings = new LinkedHashMap<>();
		private final Map<UUID, String> lastPanelSubmissionDiagnostics = new LinkedHashMap<>();
		private AutoCloseable packageObservationBinding = NOOP_BINDING;
		private AutoCloseable productionResolverBinding = NOOP_BINDING;
		private AutoCloseable panelProductionComposition = NOOP_BINDING;
		private long panelDirectoryGeneration;
		private final FactoryRuntimeLifecycle lifecycle = new FactoryRuntimeLifecycle();
		private boolean activated;
		private volatile boolean published;
		private boolean closed;

		private Runtime(RuntimeComposition composition) {
			this.server = composition.server();
			this.config = composition.config();
			this.runtime = composition.runtime();
			this.journal = composition.journal();
			this.registryAccess = composition.registryAccess();
			this.safeCreateRetries = new ProductionSafeRetryStore(journal, registryAccess);
			this.attachedNetworks = composition.attachedNetworks();
			this.nativeBindingInstaller = composition.nativeBindingInstaller();
			this.liveLogisticsNetworks = composition.liveLogisticsNetworks();
			this.radioTopology = new RadioTopologyLifecycle(
				config.enableDynamicPhysicalNetworkTopology());
			if (!journal.persistenceValid())
				throw new IllegalStateException("Factory orchestration journal is invalid");
			this.planRepository = new ProductionPlanRepository(new ProductionPlanRepository.Persistence() {
				public Collection<FrozenProductionPlan> loadAll() {
					return journal.readPrefix(ProductionPlanRepository.JOURNAL_PREFIX,
						FrozenProductionPlan.class, registryAccess).values();
				}
				public Optional<FrozenProductionPlan> load(UUID goalId) {
					return journal.read(ProductionPlanRepository.journalKey(goalId),
						FrozenProductionPlan.class, registryAccess);
				}
				public void save(FrozenProductionPlan plan) {
					journal.write(ProductionPlanRepository.journalKey(plan.goalId()), plan, registryAccess);
				}
			});
			this.communicationDirectory = new ClusterCommunicationDirectory(
				journal.clusterCommunicationStore(registryAccess));
			this.primaryFrequencyResolver = communicationDirectory;
			this.communicationCommands = new ClusterCommunicationCommandService(
				communicationDirectory, this::validateCommunicationAuthority,
				new ProductionAgentRoleTransitionPolicy(this::hasFormedAgentEndpoints,
					this::agentRevocationDrainState), this::communicationConfigurationChanged);
			this.productionAdmissions = new ProductionAdmissions(config.pendingRunCapacity(), runtime,
				new ProductionAdmissions.Persistence() {
					public ProductionAdmissionLedger.State loadLedger() {
						return journal.read(PRODUCTION_ADMISSION_KEY, ProductionAdmissionLedger.State.class,
							registryAccess).orElseGet(() -> new ProductionAdmissionLedger.State(Map.of(), Map.of(), 0, Optional.empty()));
					}
					public void saveLedger(ProductionAdmissionLedger.State state) {
						journal.write(PRODUCTION_ADMISSION_KEY, state, registryAccess);
					}
					public void savePrepared(ProductionAdmissionLedger.State state, FrozenProductionPlan plan, GoalObservationState observation) {
						journal.writeBatch(Map.of(PRODUCTION_ADMISSION_KEY, state, ProductionPlanRepository.journalKey(plan.goalId()), plan,
							GOAL_PREFIX + observation.goalId(), observation), registryAccess);
					}
					public Map<String, FactoryRuntimeService.ProductionSearchSnapshot> loadSearches() {
						return journal.readPrefix(PRODUCTION_SEARCH_PREFIX, FactoryRuntimeService.ProductionSearchSnapshot.class, registryAccess);
					}
					public void saveSearch(FactoryRuntimeService.ProductionSearchSnapshot search) {
						journal.write(PRODUCTION_SEARCH_PREFIX + search.searchId(), search, registryAccess);
					}
					public void deleteSearch(UUID searchId) { journal.remove(PRODUCTION_SEARCH_PREFIX + searchId); }
					public void deleteSearchEntry(String storedKey) { journal.remove(storedKey); }
				}, new ProductionAdmissions.PlanningEvidence() {
					public Optional<StockObservationScope> outputScope(UUID logisticsId, String deliveryAddress, StackKey output) {
						return goalObservationScope(logisticsId, deliveryAddress, output);
					}
					public StockObservationService.Observation observe(StockObservationScope scope) { return stockObservations.observe(scope); }
					public Map<StackKey, Long> availableManifest(PatternRecord pattern, UUID logisticsId) {
						return Runtime.this.availableManifest(pattern, logisticsId);
					}
				}, (plan, observation) -> {
					planRepository.admissionCommitted(plan);
					Runtime.this.goalSettlement.admissionCommitted(observation.goalId());
				}, lastPanelSubmissionDiagnostics::put);
			this.agentDirectory = new AgentAddressDirectory(config.pendingRunCapacity(),
				config.recoveryLedgerCapacity());
			this.logisticsAddressObservations = new LogisticsAddressObservationRegistry(
				config.logisticsAddressRetentionCapacity(), server::isSameThread,
				(blockEntity, network) -> {
					if (blockEntity.getLevel() == null || blockEntity.isRemoved())
						return Optional.empty();
					SpaceAddress address = SpaceAddress.capture(blockEntity.getLevel(),
						blockEntity.getBlockPos());
					AgentEndpointState endpoint = endpoints.get(address);
					return endpoint != null
						&& endpoint.route().logisticsNetworkId().equals(network)
							? Optional.of(endpoint.endpointAddress()) : Optional.empty();
				});
			this.logisticsAddressObserver = journal.logisticsAddressObserver(registryAccess,
				config.logisticsAddressRetentionCapacity());
			this.attachmentBindings = journal.attachmentBindingRegistry(registryAccess);
			this.stockObservations = StockObservationService.usingCreate(server, attachmentBindings);
			this.recovery = new RecoveryLedger(config,
				journal.recoveryStore(registryAccess));
			this.radioLinkPolicy = new RadioLinkPolicy(config.ordinaryListenerRange(),
				config.wanderingTraderListenerRange());
			this.radioRouter = new RadioRouter(config.ordinaryListenerRange(),
				config.wanderingTraderListenerRange(), config.recoveryLedgerCapacity(),
				config.recoveryLedgerCapacity(), new RadioRouter.AgentProtocolStateStore() {
					@Override public Optional<RadioRouter.AgentProtocolState> load() {
						return journal.read("radio/protocol", RadioRouter.AgentProtocolState.class,
							registryAccess);
					}
					@Override public void persist(RadioRouter.AgentProtocolState state) {
						journal.write("radio/protocol", state, registryAccess);
					}
				}, new RadioRouter.LeaseProtocolStateStore() {
					@Override public Optional<RadioRouter.LeaseProtocolState> load() {
						return journal.read(RADIO_LEASE_PROTOCOL_KEY, RadioRouter.LeaseProtocolState.class,
							registryAccess);
					}
					@Override public void persist(RadioRouter.LeaseProtocolState state) {
						journal.write(RADIO_LEASE_PROTOCOL_KEY, state, registryAccess);
					}
				}, new RadioRouter.AuthorityStateStore() {
					@Override public Optional<RadioRouter.AuthorityState> load() {
						return journal.read(RADIO_AUTHORITY_KEY, RadioRouter.AuthorityState.class,
							registryAccess);
					}
					@Override public boolean compareAndSet(RadioRouter.AuthorityState expected,
						RadioRouter.AuthorityState replacement) {
						RadioRouter.AuthorityState actual = load().orElseGet(() ->
							new RadioRouter.AuthorityState(0, Map.of(), Map.of()));
						if (!actual.equals(expected)) return false;
						journal.write(RADIO_AUTHORITY_KEY, replacement, registryAccess);
						return true;
					}
				});
			this.radioTopologyPublications = new RadioTopologyPublicationAuthority(radioTopology,
				radioRouter);
			FactoryRuntimeService.PanelProductionComposition panelComposition =
				runtime.beginPanelProductionComposition();
			panelComposition.bindTopology(radioTopologyPublications);
			this.panelProductionComposition = panelComposition;
			this.externalListeners = new ExternalListenerRuntime(runtime, radioRouter,
				new ExternalListenerRuntime.TopologySink() {
					@Override public void observe(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) {
						retireRadioRoutes(radioTopology.observe(listenerTopologyRegistration(owner,
							endpoint), endpoint, topologyPose(endpoint),
							server.overworld().getGameTime()), endpoint);
					}
					@Override public void retire(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) {
						retireRadioRoutes(radioTopology.retire(listenerTopologyRegistration(owner,
							endpoint)), null);
					}
				}, new ExternalListenerService.RetirementStore() {
					@Override public Optional<ExternalListenerService.RetirementState> load() {
						return journal.read(RADIO_LISTENER_RETIREMENTS_KEY,
							ExternalListenerService.RetirementState.class, registryAccess);
					}
					@Override public void persist(ExternalListenerService.RetirementState state) {
						journal.write(RADIO_LISTENER_RETIREMENTS_KEY, state, registryAccess);
					}
				});
			this.radioOutbox = new DurableRadioOutbox(config.recoveryLedgerCapacity(),
				new DurableRadioOutbox.StateStore() {
					@Override public Optional<DurableRadioOutbox.State> load() {
					return journal.read(RADIO_OUTBOX_KEY, DurableRadioOutbox.State.class,
							registryAccess);
					}
					@Override public void persist(DurableRadioOutbox.State state) {
						journal.write(RADIO_OUTBOX_KEY, state, registryAccess);
					}
				});
			recovery.snapshot().records().forEach((key, record) ->
				requestKeysByRun.put(key.runId(), key));

            this.remoteExpansion = new ProductionRemoteExpansion(registryAccess, runtime,
                config.recoveryLedgerCapacity(), radioRouter, radioOutbox, new ProductionRemoteExpansion.Persistence() {
                    public Optional<RemoteComputationLifecycleState> load() {
                        return journal.read(REMOTE_COMPUTATION_LIFECYCLE_KEY, RemoteComputationLifecycleState.class, registryAccess);
                    }
                    public void commit(Optional<RemoteComputationLifecycleState> lifecycle, Optional<FrozenProductionPlan> plan,
                        Optional<RadioRouter.LeaseProtocolState> leases, Optional<DurableRadioOutbox.State> outbox) {
                        LinkedHashMap<String, Object> writes = new LinkedHashMap<>();
                        lifecycle.ifPresent(value -> writes.put(REMOTE_COMPUTATION_LIFECYCLE_KEY, value));
                        plan.ifPresent(value -> writes.put(ProductionPlanRepository.journalKey(value.goalId()), value));
                        leases.ifPresent(value -> writes.put(RADIO_LEASE_PROTOCOL_KEY, value));
                        outbox.ifPresent(value -> writes.put(RADIO_OUTBOX_KEY, value));
                        journal.writeBatch(writes, registryAccess);
                    }
                    public Optional<ProductionOutputContract.Snapshot> output(UUID runId) {
                        return journal.read("output_contract/" + runId, ProductionOutputContract.Snapshot.class, registryAccess);
                    }
                }, new ProductionRemoteExpansion.Evidence() {
                    public FrozenProductionPlan plan(UUID goalId) { return planRepository.current(goalId); }
                    public ProductionOutputContract.Snapshot output(UUID runId) {
                        ProductionOutputContract contract = outputContracts.get(runId);
                        return contract == null ? null : contract.snapshot();
                    }
                    public RadioEndpoint endpoint(RadioEndpoint.Key key) { return radioEndpoints.get(key); }
                    public List<RadioEndpoint> endpoints() { return List.copyOf(radioEndpoints.values()); }
                    public boolean linkPossible(RadioEndpoint source, RadioEndpoint target, CommunicationFrequency frequency) {
                        return radioLinkPossible(source, target, frequency);
                    }
                    public List<RadioEndpoint.Hop> relays(RadioEndpoint source, RadioEndpoint target, CommunicationFrequency frequency) {
                        return discoverRadioRelays(source, target, frequency);
                    }
                    public void publishPlan(FrozenProductionPlan plan) { planRepository.publishCommitted(plan); }
                    public void enqueueMaterialReplay(UUID runId) { enqueueRemoteMaterialHandoffReplay(runId); }
                });
			this.localExpansion = new ProductionLocalExpansion(registryAccess, runtime,
				config.recoveryLedgerCapacity(), new ProductionLocalExpansion.Persistence() {
					public Optional<Lifecycle> load() {
						return journal.read(LOCAL_COMPUTATION_LIFECYCLE_KEY, Lifecycle.class, registryAccess);
					}
					public void commit(Lifecycle lifecycle, Optional<FrozenProductionPlan> plan) {
						LinkedHashMap<String, Object> writes = new LinkedHashMap<>();
						writes.put(LOCAL_COMPUTATION_LIFECYCLE_KEY, lifecycle);
						plan.ifPresent(value -> writes.put(ProductionPlanRepository.journalKey(value.goalId()), value));
						journal.writeBatch(writes, registryAccess);
					}
					public Optional<ProductionOutputContract.Snapshot> output(UUID runId) {
						return journal.read("output_contract/" + runId, ProductionOutputContract.Snapshot.class, registryAccess);
					}
				}, new ProductionLocalExpansion.Evidence() {
					public FrozenProductionPlan plan(UUID goalId) { return planRepository.current(goalId); }
					public ProductionOutputContract.Snapshot output(UUID runId) {
						ProductionOutputContract contract = outputContracts.get(runId);
						return contract == null ? null : contract.snapshot();
					}
					public Set<UUID> remoteChildIds() {
						return remoteExpansion.childIds();
					}
					public void publishPlan(FrozenProductionPlan plan) { planRepository.publishCommitted(plan); }
				});
			this.goalSettlement = new ProductionGoalSettlement(new ProductionGoalSettlement.Persistence() {
				public Map<String, GoalObservationState> loadObservations() {
					return journal.readPrefix(GOAL_PREFIX, GoalObservationState.class, registryAccess);
				}
				public Optional<GoalObservationState> observation(UUID goalId) {
					return journal.read(GOAL_PREFIX + goalId, GoalObservationState.class, registryAccess);
				}
				public void saveObservation(GoalObservationState state) {
					journal.write(GOAL_PREFIX + state.goalId(), state, registryAccess);
				}
				public Optional<FrozenProductionPlan> plan(UUID goalId) {
					return planRepository.durable(goalId);
				}
				public Optional<ProductionOutputContract.Snapshot> output(UUID runId) {
					return journal.read("output_contract/" + runId, ProductionOutputContract.Snapshot.class, registryAccess);
				}
				public void commitRetirement(UUID runId, Optional<GoalObservationState> updated,
					ProductionGoalSettlement.CompletedRunRemoteArtifactRetirement remote) {
					Map<String, ?> writes = updated.<Map<String, ?>>map(value ->
						Map.of(GOAL_PREFIX + value.goalId(), value)).orElseGet(Map::of);
					journal.writeBatchAndRemoveActiveRunArtifacts(writes, runId,
						remote.terminalJournalArtifacts(), registryAccess);
				}
			}, runtime, stockObservations, new ProductionGoalSettlement.Evidence() {
				public FrozenProductionPlan plan(UUID goalId) {
					return planRepository.current(goalId);
				}
				public ProductionOutputPolicy outputPolicy(UUID runId) {
					return outputPolicies.get(runId);
				}
				public List<ProductionOutputContract.Snapshot> outputReceipts() {
					return outputContracts.values().stream().map(ProductionOutputContract::snapshot).toList();
				}
				public boolean hasLiveChild(UUID runId) {
					return remoteExpansion.containsRun(runId) || localExpansion.containsRun(runId);
				}
				public RecoveryLedger.State recovery() {
					return recovery.snapshot();
				}
				public void removeRetiredArtifacts(UUID runId) {
					removeRetiredRunArtifactsFromMemory(runId);
				}
			}, new ProductionGoalSettlement.Transport() {
				public Map<UUID, RemoteAgentRadioDispatch> dispatches() {
					return Map.copyOf(remoteAgentDispatches);
				}
				public Map<RemoteRequestKey, RemoteRecoveryExchange> exchanges() {
					return Map.copyOf(remoteRecoveryExchanges);
				}
				public DurableRadioOutbox.State outbox() {
					return radioOutbox.snapshot();
				}
				public boolean canRetireDeliveredRoute(UUID messageId) {
					return radioRouter.canRetireDeliveredRoute(messageId);
				}
				public boolean matchesCurrentPair(ProductionGoalSettlement.CompletedRunRemoteArtifactRetirement pair) {
					RemoteAgentRadioDispatch dispatch = pair.dispatch().orElse(null);
					RemoteRecoveryExchange exchange = pair.recoveryExchange().orElse(null);
					return dispatch == null || dispatch.equals(remoteAgentDispatches.get(dispatch.messageId()))
						&& exchange.equals(remoteRecoveryExchanges.get(exchange.key()));
				}
				public void removeExactPair(ProductionGoalSettlement.CompletedRunRemoteArtifactRetirement pair) {
					RemoteAgentRadioDispatch dispatch = pair.dispatch().orElse(null);
					RemoteRecoveryExchange exchange = pair.recoveryExchange().orElse(null);
					if (dispatch == null) return;
					if (!remoteAgentDispatches.remove(dispatch.messageId(), dispatch)
						|| !remoteRecoveryExchanges.remove(exchange.key(), exchange))
						throw new IllegalStateException(
							"Durably retired remote Run artifacts remained in the tick scan");
				}
			});
			this.safeRetryRecovery = new ProductionSafeRetryRecovery<>(new ProductionSafeRetryRecovery.Access<SafeCreateRetryProof>() {
				public RemoteComputationExchange exchange(UUID runId) { return remoteExpansion.exchange(runId).orElse(null); }
				public FrozenProductionPlan plan(UUID goalId) { return planRepository.current(goalId); }
				public Optional<ClusterEngineState> snapshot(UUID clusterId) { return runtime.snapshot(clusterId); }
				public UUID selectedAgent(SelectedProviderDescriptor provider) { return selectedAgentId(provider); }
				public OptionalLong selectedAgentGeneration(SelectedProviderDescriptor provider) { return Runtime.selectedAgentGeneration(provider); }
				public boolean handoffOwnsRun(FrozenProductionPlan plan, UUID runId) { return remoteMaterialHandoffOwnsRun(plan, runId); }
				public RecoveryLedger.State recovery() { return recovery.snapshot(); }
				public Optional<SafeCreateRetryProof> proof(ProductionEffect.CreateDispatch intent, CreateLogisticsGateway.PersistedCommit persisted,
					Optional<AgentInputContract.State> input, RecoveryLedger.State recoveryState) {
					return SafeCreateRetryProof.repair(intent, persisted, input, recoveryState);
				}
				public AgentResetService reset(UUID agentId) { return resetServices.get(agentId); }
				public ProductionOutputContract output(UUID runId) { return outputContracts.get(runId); }
				public AgentCoordinator coordinator(UUID agentId) { return agents.get(agentId); }
				public boolean rollbackAdmission(RemoteRequestKey key, AgentCoordinator coordinator) {
					return recovery.rollbackAdmission(key, runtime.pendingAgentRuns(), coordinator.process());
				}
				public void removeInput(UUID runId, AgentInputContract existing) {
					if (existing != null) inputContracts.remove(runId, existing);
					requestKeysByRun.remove(runId);
					journal.remove("input_contract/" + runId);
				}
			});
			this.inputDispatch = new ProductionInputDispatch(new ProductionInputDispatch.Access() {
				public Optional<ClusterEngineState> snapshot(UUID clusterId) { return runtime.snapshot(clusterId); }
				public FrozenProductionPlan plan(UUID goalId) { return planRepository.current(goalId); }
				public com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency(UUID clusterId, UUID memberId) {
					return requesterFrequency(clusterId, memberId);
				}
				public Optional<CreateLogisticsGateway.PersistedCommit> inputAttempt(UUID intentId) {
					return journal.inputCommitStore(registryAccess).load(intentId);
				}
				public AgentInputContract inputContract(UUID runId) { return inputContracts.get(runId); }
				public Optional<ProductionInputDispatch.SafeRetry> authorizeRetry(ProductionEffect.CreateDispatch intent,
					CreateLogisticsGateway.PersistedCommit attempt, AgentInputContract existing) {
					return safeRetryRecovery.authorize(intent, attempt, existing == null
						? Optional.empty() : Optional.of(existing.snapshot())).map(repaired -> new ProductionInputDispatch.SafeRetry() {
						public void persist() { safeCreateRetries.persistAndInstall(intent, attempt, repaired); }
						public Optional<WaitReason> normalize() { return safeRetryRecovery.normalize(intent, existing, repaired.command()); }
					});
				}
				public boolean replayInput(AgentInputContract existing, ProductionEffect.CreateDispatch intent) {
					return replayInputCommit(existing, intent);
				}
				public void requireReset(UUID runId, UUID agentId) {
					RemoteRequestKey key = requestKeysByRun.get(runId);
					AgentResetService reset = resetServices.get(agentId);
					if (key != null && reset != null) reset.requireReset(key);
				}
				public boolean hasBareAttempt(UUID intentId) { return journal.bareCommitStore(registryAccess).load(intentId).isPresent(); }
				public boolean providerCurrent(ProductionEffect.CreateDispatch intent, SelectedProviderDescriptor selected) {
					return runtime.revalidateProductionProvider(intent.clusterId(), intent.route().logisticsNetworkId(), selected).isPresent();
				}
				public AgentResolution resolve(CreateRouteKey route, UUID clusterId,
					com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency) {
					return agentDirectory.resolve(route, clusterId, frequency);
				}
				public boolean loadedInputClaims(CreateRouteKey route) { return loadedNativeAgentInputClaims(route); }
				public boolean bare(ProductionEffect.CreateDispatch intent, Map<StackKey, Long> manifest,
					AgentResolution resolution, ClusterEngineState state, GoalRecord goal, FrozenProductionPlan plan) {
					return commitBare(intent, manifest, resolution, state, goal, plan);
				}
				public boolean agent(ProductionEffect.CreateDispatch intent, Map<StackKey, Long> manifest, ClusterEngineState state,
					GoalRecord goal, com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration registration,
					FrozenProductionPlan plan) { return commitAgent(intent, manifest, state, goal, registration, plan); }
				public boolean remote(ProductionEffect.CreateDispatch intent, ClusterEngineState state, GoalRecord goal,
					ProcessRecord process, com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration registration,
					com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency) {
					return scheduleRemoteAgentDispatch(intent, state, goal, process, registration, frequency);
				}
				public boolean waitSource(ClusterEngineState state, GoalRecord goal, WaitReason reason) {
					return Runtime.this.waitSource(state, goal, reason);
				}
			});
			this.outputDispatch = new ProductionOutputDispatch<>(new ProductionOutputDispatch.Access<OutputProbeState, PreparedOutput>() {
				public ProductionOutputContract contract(UUID runId) { return outputContracts.get(runId); }
				public ProductionOutputPolicy policy(UUID runId) { return outputPolicies.get(runId); }
				public OutputProbeState probe(UUID runId) { return outputProbes.get(runId); }
				public Optional<ClusterEngineState> source(UUID runId) { return sourceState(runId); }
				public FrozenProductionPlan plan(UUID goalId) { return planRepository.current(goalId); }
				public boolean hasOutput(OutputProbeState probe, StackKey exactOutput) {
					return probe.stacks().values().contains(Optional.of(exactOutput));
				}
				public Optional<PreparedOutput> prepare(OutputProbeState probe, ProductionOutputContract.DispatchInstruction instruction) {
					return prepareOutputMove(probe, instruction);
				}
				public void persistPrepared(OutputProbeState probe, PreparedOutput prepared) {
					persistOutputProbe(probe.withDispatchPackagers(prepared.packagers()));
				}
				public OutputLogisticsGateway.CommitResult commit(ProductionOutputContract contract, ProductionOutputPolicy policy,
					PreparedOutput prepared, ProductionOutputContract.DispatchInstruction instruction) {
					OutputLogisticsGateway gateway = OutputLogisticsGateway.create(server::isSameThread,
						new OutputLogisticsGateway.Authority() {
							@Override public ProductionOutputContract.Snapshot captureContract() {
								return contract.snapshot();
							}
							@Override public OutputLogisticsGateway.ReadinessState captureReadiness(
								OutputLogisticsGateway.ReadinessRequest ignored) {
								throw new IllegalStateException("commit-only gateway");
							}
							@Override public OutputLogisticsGateway.DrainState captureDrain(
								OutputLogisticsGateway.DrainRequest ignored) {
								throw new IllegalStateException("commit-only gateway");
							}
							}, exact -> {
								if (!exact.equals(instruction))
									throw new IllegalStateException("output instruction changed after preflight");
								freezeOutputNativeReceipt(exact, policy, prepared);
								LogisticsManager.performPackageRequests(prepared.requests());
							return new OutputLogisticsGateway.NativeOperation(UUID.nameUUIDFromBytes(
								("create-output:" + exact.intentId()).getBytes(StandardCharsets.UTF_8)));
						}, journal.outputCommitStore(registryAccess));
					return gateway.commit(instruction);
				}
				public boolean waitSource(ClusterEngineState source, GoalRecord goal, WaitReason reason) {
					return Runtime.this.waitSource(source, goal, reason);
				}
				public void rejected(UUID runId, OutputLogisticsGateway.Rejection rejection) {
					LOGGER.error("Factory Agent output commit rejected for run {}: {}", runId, rejection);
				}
				public void confirmationMismatch(UUID runId) {
					LOGGER.error("Factory Agent output confirmation mismatch for run {}", runId);
				}
				public void fence(ProductionOutputContract contract, WaitReason reason) { fenceOutput(contract, reason); }
				public RemoteRequestKey requestKey(UUID runId) { return requestKeysByRun.get(runId); }
				public void recordOutput(RemoteRequestKey key, ProductionOutputContract.Snapshot snapshot) {
					recovery.recordOutput(key, snapshot);
				}
			});
			this.effectCommitter = new ProductionEffectCommitter(new ProductionEffectCommitter.Access() {
				public boolean acceptsWork() { return Runtime.this.acceptsWork(); }
				public boolean isServerThread() { return server.isSameThread(); }
				public Optional<GoalObservationState> observation(UUID requestId) {
					return goalSettlement.observation(requestId);
				}
				public StockObservationService.Observation observe(StockObservationScope scope) {
					return stockObservations.observe(scope);
				}
				public boolean publish(WorldFact.StockObservation fact) { return runtime.publishFact(fact); }
				public boolean create(ProductionEffect.CreateDispatch request) { return inputDispatch.commit(request); }
				public boolean output(ProductionEffect.OutputPackageRequest request) { return outputDispatch.commit(request); }
				public void persist(String key, ProductionEffect.PanelSnapshotPublication publication) {
					journal.write(key, publication, registryAccess);
				}
			});
			this.planDispatch = new ProductionPlanDispatch(runtime, registryAccess, localExpansion,
				remoteExpansion, new ProductionPlanDispatch.Evidence() {
					public Collection<FrozenProductionPlan> plans() { return planRepository.snapshot(); }
					public FrozenProductionPlan plan(UUID goalId) { return planRepository.current(goalId); }
					public GoalObservationState observation(UUID goalId) { return goalSettlement.observation(goalId).orElse(null); }
					public boolean runHasDurableMaterialEvidence(ClusterEngineState state, UUID runId) {
						return Runtime.this.runHasDurableMaterialEvidence(state, runId);
					}
					public boolean loadedAgentOwns(CreateRouteKey route, UUID clusterId) {
						return Runtime.this.loadedAgentOwns(route, clusterId);
					}
					public void persistPlan(FrozenProductionPlan plan) { planRepository.persist(plan); }
					public boolean waitSource(ClusterEngineState state, GoalRecord goal, WaitReason reason) {
						return Runtime.this.waitSource(state, goal, reason);
					}
				});
			planRepository.recover();
			safeCreateRetries.recover();
			List<DurableProductionLibrary> recoveredLibraries = journal.readPrefix(
				PRODUCTION_LIBRARY_PREFIX, DurableProductionLibrary.class,
				registryAccess).values().stream()
				.map(DurableProductionLibrary::knownUnloadedAfterBootstrap).toList();
			LinkedHashMap<String, Object> crashDemotions = new LinkedHashMap<>();
			for (DurableProductionLibrary library : recoveredLibraries)
				crashDemotions.put(PRODUCTION_LIBRARY_PREFIX + library.libraryId(), library);
			if (!crashDemotions.isEmpty())
				journal.writeBatch(crashDemotions, registryAccess);
			for (DurableProductionLibrary library : recoveredLibraries) {
				productionLibraries.put(library.libraryId(), library);
					runtime.publishProductionLibraryAccess(library.clusterId(), library.logisticsIds(),
					library.directoryRevision(), library.frequency(), library.priority(),
					library.address(), library.reachability(), library.catalog());
			}
			// Directory crash-demotion is itself a publication and therefore invalidates live searches.
			// Restore selected/adoption cursors only after that publication so PREPARED recovery keeps
			// its exact lease authority instead of being rewritten to SEARCHING during bootstrap.
			productionAdmissions.restoreSearchesAfterLibraryBootstrap();
			journal.readPrefix("remote_agent_dispatch/", RemoteAgentRadioDispatch.class,
				registryAccess).values().forEach(dispatch ->
					remoteAgentDispatches.put(dispatch.messageId(), dispatch));
			remoteExpansion.restore();
			localExpansion.restore();
			planRepository.snapshot().stream().map(plan -> plan.runs().currentRun())
				.filter(Objects::nonNull).map(ProductionRun::runId)
				.forEach(this::enqueueRemoteMaterialHandoffReplay);
			journal.readPrefix("remote_recovery_exchange/", RemoteRecoveryExchange.class,
				registryAccess).values().forEach(exchange -> {
					RemoteRecoveryExchange recovered = exchange.recoveredAfterReload();
					remoteRecoveryExchanges.put(recovered.key(), recovered);
					if (!recovered.equals(exchange)) persistRemoteRecoveryExchange(recovered);
				});
			journal.readPrefix("input_contract/", AgentInputContract.State.class,
				registryAccess).values().forEach(state -> {
				AgentInputContract contract = AgentInputContract.restore(state,
					journal.inputContractStore(state.run().runId(), registryAccess));
				inputContracts.put(state.run().runId(), contract);
			});
			journal.readPrefix(OUTPUT_POLICY_PREFIX, OutputPolicyState.class,
				registryAccess).values().forEach(state ->
					outputPolicies.put(state.runId(), state.policy()));
			journal.readPrefix(OUTPUT_PROBE_PREFIX, OutputProbeState.class,
				registryAccess).values().forEach(probe -> outputProbes.put(probe.runId(), probe));
			journal.readPrefix(SIGNAL_PROBE_PREFIX, SignalProbeState.class,
				registryAccess).values().forEach(probe -> signalProbes.put(probe.runId(), probe));
			journal.readPrefix("output_native_receipt/", OutputNativeReceiptState.class,
				registryAccess).values().forEach(receipt ->
					outputNativeReceipts.put(receipt.intentId(), receipt));
			journal.readPrefix("output_contract/", ProductionOutputContract.Snapshot.class,
				registryAccess).values().forEach(snapshot -> {
				ProductionOutputPolicy policy = outputPolicies.get(snapshot.run().runId());
				if (policy == null) throw new IllegalStateException(
					"Output contract has no frozen production policy " + snapshot.run().runId());
				Optional<ProductionOutputLedger> ledger = journal.read("output_ledger/"
					+ snapshot.run().runId(), ProductionOutputLedger.class, registryAccess);
				outputContracts.put(snapshot.run().runId(), ProductionOutputContract.restore(snapshot,
					policy, ledger, journal.outputContractStore(snapshot.run().runId(),
						registryAccess)));
			});
			outputNativeReceipts.entrySet().removeIf(entry ->
				!outputContracts.containsKey(entry.getValue().runId()));
		}

		/** Publishes external adapters only after construction has fully restored durable authority. */
		private void activate() {
			if (closed || activated) throw new IllegalStateException("runtime activation is not available");
			try {
				packageObservationBinding = Objects.requireNonNull(
					nativeBindingInstaller.install(server, this), "native package binding");
				productionResolverBinding = runtime.installProductionResolvers(
					this::collectProductionRouteSources, this::resolveProductionProvider,
					clusterId -> acceptsWork() ? requesterFrequency(clusterId, null)
						: CommunicationFrequency.UNCONFIGURED);
				activated = true;
			} catch (RuntimeException | Error failure) {
				try { deactivateExternalBindings(); }
				catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
				throw failure;
			}
		}

		private void deactivateExternalBindings() {
			RuntimeException failure = null;
			failure = closeBinding(productionResolverBinding, failure);
			productionResolverBinding = NOOP_BINDING;
			failure = closeBinding(packageObservationBinding, failure);
			packageObservationBinding = NOOP_BINDING;
			failure = closeBinding(panelProductionComposition, failure);
			panelProductionComposition = NOOP_BINDING;
			NativePackageObservation.uninstallIfOwnedBy(server, this);
			activated = false;
			if (failure != null) throw failure;
		}

		private static RuntimeException closeBinding(AutoCloseable binding,
			@Nullable RuntimeException priorFailure) {
			try {
				binding.close();
				return priorFailure;
			} catch (Exception failure) {
				RuntimeException wrapped = new IllegalStateException("runtime binding cleanup failed", failure);
				if (priorFailure == null) return wrapped;
				priorFailure.addSuppressed(wrapped);
				return priorFailure;
			}
		}





		private void tick() {
			requireServerThread();
			if (!acceptsWork()) return;
			reconcileLoadedRadioOwners();
			externalListeners.tick();
			refreshNativePanels();
			refreshLogisticsAddressDirectory();
			radioTopology.refresh(server.overworld().getGameTime(), this::resolveCurrentRadioTopology);
			reconcileResetRequiredGoals();
			for (UUID agentId : endpoints.values().stream().map(AgentEndpointState::agentId)
				.distinct().toList()) rebuildAgent(agentId);
			reconcileRejectedCreateIntentConfirmations();
			replayRemoteMaterialHandoffDispatches();
			reconcileCancellationDrainPlans();
			goalSettlement.replayDurableCredits();
			productionAdmissions.advanceTick(config.libraryReplyBatch());
			planDispatch.advance();
			advanceRemoteRecoveryExchanges();
			localExpansion.advance();
			remoteExpansion.advanceJobs();
			remoteExpansion.advanceExchanges();
			advanceRadio();
			advanceRemoteAgentDispatches();
			goalSettlement.observeStocks();
			advanceOutputContracts();
			localExpansion.retireCompleted();
			remoteExpansion.retireCompleted();
			goalSettlement.retireReceivedRuns();
			radioOutbox.retireAppliedRoutes(radioRouter);
		}

		private void refreshLogisticsAddressDirectory() {
			refreshLogisticsAddressDirectoryNow(false);
		}

		private void refreshLogisticsAddressDirectoryNow(boolean immediate) {
			long tick = server.overworld().getGameTime();
			if (!immediate && tick % LogisticsAddressObserver.PUBLICATION_INTERVAL_TICKS != 0) return;
			logisticsAddressObservations.beginLinkScan();
			for (WeakReference<FactoryPanelBlockEntity> reference :
				List.copyOf(nativePanels.values())) {
				FactoryPanelBlockEntity panel = reference.get();
				if (panel != null && panel.getLevel() != null && !panel.isRemoved())
					observePanelAddresses(panel);
			}
			LogisticsNetworkDirectory directory =
				journal.logisticsNetworkDirectory(registryAccess);
			for (UUID network : Set.copyOf(liveLogisticsNetworks.get()))
				if (!directory.tryEnsure(network).succeeded()) continue;
			Set<UUID> knownNetworks = directory.snapshot().entries().keySet();
			for (UUID network : knownNetworks)
				for (LogisticallyLinkedBehaviour link :
					LogisticallyLinkedBehaviour.getAllPresent(network, false)) {
					BlockEntity owner = link.blockEntity;
					if (owner.getLevel() == null || owner.getLevel().isClientSide
						|| owner.getLevel().getServer() != server) continue;
					logisticsAddressObservations.observe(owner, Optional.of(link),
						new LogisticsNetworkAssociation(network, AssociationKind.DIRECT_LINK));
					if (!(owner instanceof PackagerLinkBlockEntity packagerLink)) continue;
					LogisticsNetworkAssociation adjacent = new LogisticsNetworkAssociation(network,
						AssociationKind.ADJACENT_PACKAGER_LINK);
					PackagerBlockEntity packager = packagerLink.getPackager();
					if (packager != null)
						logisticsAddressObservations.observe(packager, Optional.of(link), adjacent);
					for (Direction direction : Direction.values()) {
						BlockEntity neighbor = owner.getLevel().getBlockEntity(
							owner.getBlockPos().relative(direction));
						if (neighbor != null && neighbor != packager)
							logisticsAddressObservations.observe(neighbor,
								Optional.of(link), adjacent);
					}
				}
			logisticsAddressObserver.publishIfDue(tick,
				logisticsAddressObservations.snapshot());
		}














		private Optional<PatternRecord> pattern(UUID patternId) {
			return productionLibraries.values().stream().filter(library -> library.catalog().loaded())
				.flatMap(library -> library.catalog().patterns().stream())
				.filter(candidate -> candidate.patternId().equals(patternId)).findFirst();
		}











		private boolean acceptsWork() {
			return published && lifecycle.acceptsWork() && !closed;
		}

		private void freezeForOrderlyShutdown() {
			requireServerThread();
			lifecycle.beginOrderlyShutdown();
		}

		private void markStopped() { lifecycle.markStopped(); }

		private void reconcileRejectedCreateIntentConfirmations() {
			int limit = Math.min(config.maxCommittedIntentsPerTick(),
				rejectedCreateConfirmationQueue.size());
			for (int index = 0; index < limit; index++) {
				UUID runId = rejectedCreateConfirmationQueue.removeFirst();
				PendingCreateRejection rejection =
					rejectedCreateIntentsAwaitingConfirmation.get(runId);
				if (rejection == null) continue;
				ClusterEngineState state = runtime.snapshot(rejection.clusterId()).orElse(null);
				if (state == null) {
					rejectedCreateConfirmationQueue.addLast(runId);
					continue;
				}
				if (!state.epochId().equals(rejection.epochId())) {
					rejectedCreateIntentsAwaitingConfirmation.remove(runId, rejection);
					continue;
				}
				if (!pendingCreateRejectionApplied(state, rejection)) {
					rejectedCreateConfirmationQueue.addLast(runId);
					continue;
				}
				rejectedCreateIntentsAwaitingConfirmation.remove(runId, rejection);
				enqueueRemoteMaterialHandoffReplay(runId);
			}
		}

		/**
		 * Replays a bounded fair batch of immutable material commands whose authorization and source
		 * receipt were already committed together. This runs before cancellation reconciliation so a
		 * receipt-before-contract Cancel can drain exactly its already-authorized first dispatch.
		 */
		private void replayRemoteMaterialHandoffDispatches() {
			int limit = Math.min(config.maxCommittedIntentsPerTick(),
				remoteMaterialHandoffReplayQueue.size());
			for (int offset = 0; offset < limit; offset++) {
				UUID runId = remoteMaterialHandoffReplayQueue.removeFirst();
				queuedRemoteMaterialHandoffRuns.remove(runId);
				if (planDispatch.isSubmitted(runId)) continue;
				if (rejectedCreateIntentsAwaitingConfirmation.containsKey(runId)) {
					enqueueRemoteMaterialHandoffReplay(runId);
					continue;
				}
				RemoteComputationExchange exchange = remoteExpansion.exchange(runId).orElse(null);
				FrozenProductionPlan plan = exchange == null ? null
					: planRepository.current(exchange.goalId());
				ProductionRun run = plan == null ? null : plan.runs().currentRun();
				if (run == null || exchange == null || !run.runId().equals(runId)
					|| !remoteMaterialHandoffOwnsRun(plan, runId)) continue;
				SafeCreateRetryProof retry = safeCreateRetries.get(runId);
				if (retry != null) {
					CreateLogisticsGateway.PersistedCommit persisted = journal.inputCommitStore(
						registryAccess).load(retry.intent().intentId()).orElse(null);
					SafeCreateRetryProof latest = persisted == null
						|| !persisted.allowsRetryWithoutMaterial() ? null
						: SafeCreateRetryProof.from(retry.intent(), persisted).orElse(null);
					if (latest == null || !retry.sameAuthority(latest)) {
						safeCreateRetries.remove(runId, retry);
						journal.remove(ProductionSafeRetryStore.JOURNAL_PREFIX + runId);
						if (persisted == null || !persisted.provesMaterialCommit()) continue;
					} else {
						if (retry.rejection() != latest.rejection())
							safeCreateRetries.persistAndInstall(retry.intent(), persisted, latest);
						retry = latest;
						if (!runtime.retryRejectedCreateDispatch(retry.intent())) {
							enqueueRemoteMaterialHandoffReplay(runId);
							continue;
						}
					}
				}
				ClusterEngineState state = runtime.snapshot(plan.clusterId()).orElse(null);
				boolean replayed = state != null && replayRemoteMaterialHandoffDispatch(runtime,
					plan, exchange, radioRouter,
					registryAccess, () -> runHasDurableMaterialEvidence(state, run.runId()),
					runtime::publishFact);
				if (replayed) planDispatch.markSubmitted(runId);
				else enqueueRemoteMaterialHandoffReplay(runId);
			}
		}

		private void enqueueRemoteMaterialHandoffReplay(UUID runId) {
			runId = Objects.requireNonNull(runId, "runId");
			RemoteComputationExchange exchange = remoteExpansion.exchange(runId).orElse(null);
			FrozenProductionPlan plan = exchange == null ? null
				: planRepository.current(exchange.goalId());
			if (plan == null || planDispatch.isSubmitted(runId)
				|| !remoteMaterialHandoffOwnsRun(plan, runId)) return;
			if (queuedRemoteMaterialHandoffRuns.add(runId))
				remoteMaterialHandoffReplayQueue.addLast(runId);
		}

		private void reconcileResetRequiredGoals() {
			for (AgentInputContract contract : List.copyOf(inputContracts.values())) {
				AgentInputContract.State input = contract.snapshot();
				if (input.phase() != AgentInputPhase.RESET_REQUIRED) continue;
				sourceState(input.run().runId()).ifPresent(source ->
					reconcileResetRequiredGoal(runtime, source, input));
			}
		}

		/**
		 * Closes every crash boundary around post-intent cancellation. The plan is the durable
		 * no-credit intent and is always fenced first; older states where RecoveryLedger won the
		 * race are normalized back into that same plan fence before stock observation.
		 */
		private void reconcileCancellationDrainPlans() {
			RecoveryLedger.State recoveryState = recovery.snapshot();
			LinkedHashSet<UUID> cancelDrainRuns = recoveryState.records().entrySet().stream()
				.filter(entry -> entry.getValue().condition()
					== RemoteAgentRecord.RecoveryCondition.CANCEL_DRAIN
					|| entry.getValue().terminalOutcome().filter(
						RemoteAgentRecord.TerminalOutcome.CANCEL_DRAINED::equals).isPresent())
				.map(entry -> entry.getKey().runId())
				.collect(Collectors.toCollection(LinkedHashSet::new));
			recoveryState.acknowledgedTombstones().forEach((key, tombstone) -> {
				if (tombstone.outcome() == RemoteAgentRecord.TerminalOutcome.CANCEL_DRAINED)
					cancelDrainRuns.add(key.runId());
			});
			for (FrozenProductionPlan plan : planRepository.snapshot()) {
				FrozenProductionPlan normalized = normalizeCancellationPlanFence(plan,
					cancelDrainRuns);
				if (!normalized.equals(plan)) planRepository.persist(normalized);
			}

			for (FrozenProductionPlan plan : planRepository.snapshot()) {
				ProductionRun run = plan.runs().currentRun();
				if (!plan.cancelAfterDrain() || run == null) continue;
				reconcileCancellationGoalFence(runtime, plan);
				RemoteRequestKey key = requestKeysByRun.get(run.runId());
				AgentInputContract input = inputContracts.get(run.runId());
				if (key == null || input == null) continue;
				RemoteAgentRecord record = recovery.status(key).orElse(null);
				if (record == null) continue;
				AgentResetService reset = resetServices.get(input.snapshot().agentId());
				if (record.terminalOutcome().filter(
						RemoteAgentRecord.TerminalOutcome.CANCEL_DRAINED::equals).isPresent()) {
					if (reset != null) reset.requireReset(key);
					continue;
				}
				if (record.isTerminal() || !record.crossedDispatchIntent()) continue;
				AgentCoordinator coordinator = agents.get(input.snapshot().agentId());
				if (coordinator == null) continue;
				RecoveryLedger.CancelResult result = reconcilePlanCancellationDrain(plan, key,
					recovery, runtime.pendingAgentRuns(), coordinator.process(), input);
				if ((result == RecoveryLedger.CancelResult.CANCEL_DRAIN
					|| result == RecoveryLedger.CancelResult.ALREADY_TERMINAL) && reset != null)
					reset.requireReset(key);
			}
		}


		private FactoryRuntimeService.ProductionRouteSources collectProductionRouteSources(
			FactoryRuntimeService.ProductionCollectionRequest request) {
			requireServerThread();
			if (!acceptsWork())
				return new FactoryRuntimeService.ProductionRouteSources(List.of(), List.of(),
					List.of(), List.of());
			ArrayList<com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable.ProviderSource> patternSources = new ArrayList<>();
			ArrayList<com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable.ProviderSource> agentSources = new ArrayList<>();
			ArrayList<com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable.ProviderSource> gaugeSources = new ArrayList<>();
			ArrayList<com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable.ProviderSource> ordinarySources = new ArrayList<>();
			AgentRouteRuntimeAdapter agentCollector = new AgentRouteRuntimeAdapter(runtime,
				request.clusterId());
			com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency = requesterFrequency(request.clusterId(), null);
			for (var patternSource : request.patternSources()) {
				DurableProductionLibrary library = productionLibraries.get(patternSource.libraryId());
				PatternRecord pattern = library == null ? null : library.catalog().patterns().stream()
					.filter(candidate -> candidate.patternId().equals(patternSource.patternId()))
					.findFirst().orElse(null);
				if (pattern == null) continue;
				String address = switch (pattern.body()) {
					case StandardPatternBody standard -> standard.address();
					case SequencedPatternBody sequenced -> sequenced.stages().getFirst().address();
				};
				CreateRouteKey route = new CreateRouteKey(request.logisticsId(), address);
				AgentResolution agentResolution = agentDirectory.resolve(route, request.clusterId(),
					frequency);
				patternSources.add(patternSource);
				agentSources.addAll(agentCollector.collect(request.scope(),
					patternSource.libraryId(), patternSource.patternId(), route, frequency,
					agentDirectory, List.copyOf(agents.values())));
				ArrayList<NativeGaugeProvider> physicalGauges = new ArrayList<>();
				for (Map.Entry<SpaceAddress, WeakReference<FactoryPanelBlockEntity>> entry
					: nativePanels.entrySet()) {
					FactoryPanelBlockEntity panel = entry.getValue().get();
					if (!(panel instanceof FactoryPanelAgentAccess access) || panel.isRemoved() || panel.panels == null) continue;
					int providerPriority = access
						.createBiotech$getProviderConfiguration()
						.map(FactoryPanelAgentAccess.ProviderConfiguration::routePriority)
						.orElse(0);
					for (FactoryPanelBehaviour behaviour : panel.panels.values()) {
						if (!behaviour.isActive() || !request.logisticsId().equals(behaviour.network)
							|| !address.equals(behaviour.recipeAddress)) continue;
						RouteCandidate.Availability availability = behaviour.waitingForNetwork
							? RouteCandidate.Availability.BUSY
							: RouteCandidate.Availability.AVAILABLE;
						physicalGauges.add(new NativeGaugeProvider("factory-gauge/"
							+ stableEndpoint(entry.getKey()) + "/" + behaviour.slot,
							providerPriority, availability, endpoints.containsKey(entry.getKey())));
					}
				}
				gaugeSources.addAll(collectLegalNativeGaugeProviders(request.scope(),
					patternSource.libraryId(), patternSource.patternId(), agentResolution,
					physicalGauges));
				ordinarySources.addAll(ordinaryProviders.collect(request.scope(),
					request.logisticsId(), patternSource.libraryId(), patternSource.patternId()));
				if (patternSources.size() + agentSources.size() + gaugeSources.size()
					+ ordinarySources.size()
					> com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable.MAX_SOURCES)
					throw new IllegalStateException("production route collector exceeds bound");
			}
			return new FactoryRuntimeService.ProductionRouteSources(patternSources, agentSources,
				gaugeSources, ordinarySources);
		}

		private Optional<ProductionRouteTable.ProviderSource> resolveProductionProvider(
			FactoryRuntimeService.ProductionProviderRevalidationRequest request) {
			if (!acceptsWork()) return Optional.empty();
			SelectedProviderDescriptor expected = request.expectedProvider();
			DurableProductionLibrary library = productionLibraries.get(expected.libraryId());
			ClusterEngineState state = runtime.snapshot(request.clusterId()).orElse(null);
			ClusterEngineState ownerState = library == null ? null
				: runtime.snapshot(library.clusterId()).orElse(null);
			if (library == null || state == null || ownerState == null) return Optional.empty();
			LibraryRegistration registration = new LibraryRegistration(library.libraryId(),
				library.clusterId(), new RecipeSourceLocation(library.address().dimension(), library.address().subLevelId(), library.address().localPos()), library.catalog().formationGeneration(),
				library.frequency(), library.priority(), library.catalog().catalogGeneration(),
				library.catalog().loaded() ? LibraryRegistration.LoadState.LOADED
					: LibraryRegistration.LoadState.UNLOADED, library.reachability());
			int patternPriority = PatternPriorityPolicy.resolve(request.pattern().priorityOverride(), library.priority(),
					com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority.fromAuthoritativeState(ownerState)
						.authorizes(registration.clusterId(), registration.libraryId(), request.pattern().patternId(), registration.publicCatalogGeneration())).effectivePriority();
			var patternSource = new com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable.ProviderSource(
				expected.selectionScope(), expected.libraryId(), expected.patternId(),
				expected.patternId().toString(), RouteCandidate.ProviderKind.PATTERN,
				patternPriority, RouteCandidate.Availability.AVAILABLE,
				RouteCandidate.Blocker.NONE);
			FactoryRuntimeService.ProductionRouteSources current = collectProductionRouteSources(
				new FactoryRuntimeService.ProductionCollectionRequest(request.clusterId(),
					request.logisticsId(), expected.selectionScope(), List.of(patternSource)));
			return java.util.stream.Stream.concat(current.patterns().stream(),
				java.util.stream.Stream.concat(current.agents().stream(),
					java.util.stream.Stream.concat(current.gauges().stream(),
						current.ordinaryProviders().stream())))
				.filter(expected::matches).findFirst();
		}

		private boolean registerOrdinaryProvider(
			OrdinaryProviderRegistry.Registration registration) {
			requireServerThread();
			if (!acceptsWork()) return false;
			return ordinaryProviders.register(registration);
		}

		private boolean unregisterOrdinaryProvider(UUID providerId) {
			requireServerThread();
			if (!acceptsWork()) return false;
			return ordinaryProviders.unregister(providerId);
		}




		private boolean runHasDurableMaterialEvidence(ClusterEngineState state, UUID runId) {
			return inputContracts.containsKey(runId)
				|| durableRemoteDispatchOwnsRun(remoteAgentDispatches.values().stream()
					.map(RemoteAgentRadioDispatch::intent).toList(), runId)
				|| state.pendingIntents().values().stream().anyMatch(intent ->
					intent instanceof ProductionEffect.CreateDispatch dispatch
						&& dispatch.runId().equals(runId))
				|| journal.readPrefix("input_commit/", CreateLogisticsGateway.PersistedCommit.class,
					registryAccess).values().stream().anyMatch(commit ->
						commit.command().runId().equals(runId) && commit.provesMaterialCommit())
				|| journal.readPrefix("bare_commit/",
					CreateLogisticsGateway.PersistedBareCommit.class, registryAccess)
					.values().stream().anyMatch(commit -> commit.request().runId().equals(runId));
		}












		private boolean loadedAgentOwns(CreateRouteKey route, UUID requesterClusterId) {
			AgentResolution resolution = agentDirectory.resolve(route, requesterClusterId,
				requesterFrequency(requesterClusterId, null));
			if (!(resolution instanceof AgentResolution.Unique unique)) return false;
			AgentCoordinator coordinator = agents.get(unique.registration().agentId());
			return coordinator != null && coordinator.process().available();
		}

		private ExternalListenerService.EditResult replaceExternalListeners(ProductionComputer computer,
			long expectedRevision, List<CommunicationFrequency> frequencies) {
			requireServerThread();
			if (!acceptsWork()) return ExternalListenerService.EditResult.STOPPING;
			return externalListeners.replace(computer, expectedRevision, frequencies);
		}

		private boolean observeExternalListenerForTest(ProductionComputer computer,
			CommunicationFrequency primary, SpaceAddress address) {
			requireServerThread();
			if (!acceptsWork()) return false;
			externalListeners.observe(Objects.requireNonNull(computer, "computer"),
				Objects.requireNonNull(primary, "primary"), Objects.requireNonNull(address, "address"));
			return true;
		}

		private Optional<RadioEnvelope> forwardExternalListenerIngress(ProductionComputer computer,
			CommunicationFrequency frequency, RadioEnvelope envelope,
			ExternalListenerService.IngressAuthority authority) {
			requireServerThread();
			if (!acceptsWork()) return Optional.empty();
			return externalListeners.forwardIngress(computer, frequency, envelope, authority);
		}
		private void computerObserved(ProductionComputer computer) {
			requireServerThread();
			if (!acceptsWork()) return;
			if (!computer.isAuthoritativeLoadedInstance()) {
				computerUnloaded(computer);
				return;
			}
			SpaceAddress source = SpaceAddress.capture(Objects.requireNonNull(computer.owner().getLevel()),
				computer.owner().getBlockPos());
			var epoch = computer.epoch().orElse(null);
			UUID member = computer.computerId().orElse(null);
			var profile = computer.installedProfile().orElse(null);
			if (epoch == null || member == null || profile == null || !computer.structureOnline()
				|| !computer.radioTopologyEligible()) {
				externalListeners.unload(computer);
				retireRadioRoutes(radioTopology.retire(computer.radioTopologyRegistration()), null);
				return;
			}
			RadioEndpoint.Key key = new RadioEndpoint.Key(epoch.groupId(), member);
			RadioEndpoint endpoint = computerEndpoint(computer, epoch.groupId(), member, source,
				profile);
			boolean exactTopologyOwner = radioTopology.owns(computer.radioTopologyRegistration(), endpoint);
			boolean exactRouterOwner = radioRouter.registeredEndpoint(key).filter(endpoint::equals)
				.isPresent();
			boolean restoredExactRouter = false;
			if (!exactRouterOwner && radioRouter.register(endpoint)) {
				exactRouterOwner = true;
				restoredExactRouter = true;
			}
			if (!exactRouterOwner || !exactTopologyOwner && !restoredExactRouter) {
				OptionalLong nextGeneration = radioRouter.nextEndpointGeneration(key);
				if (nextGeneration.isEmpty()) return;
				computer.ensureRadioEndpointGeneration(Math.max(computer.radioEndpointGeneration(),
					nextGeneration.getAsLong()));
				endpoint = computerEndpoint(computer, epoch.groupId(), member, source, profile);
			}
			/* Commit order is deliberately router -> topology -> map: no half-published frame. */
			if (!radioRouter.register(endpoint)) return;
			retireRadioRoutes(radioTopology.observe(computer.radioTopologyRegistration(), endpoint,
				topologyPose(endpoint), server.overworld().getGameTime()), endpoint);
			radioEndpoints.put(endpoint.key(), endpoint);
			radioComputerOwners.put(endpoint.key(), new RadioComputerOwner(computer, endpoint,
				computer.radioTopologyRegistration(), computer.listenerOwner()));
			externalListeners.observe(computer, endpoint.frequency(), source);
		}

		/** FULL ticket availability precedes deferred chunk-unload callbacks. Retire the exact
		 * registered owner at that boundary, never load a chunk to keep a radio publication alive. */
		private void reconcileLoadedRadioOwners() {
			for (RadioComputerOwner owner : List.copyOf(radioComputerOwners.values())) {
				SpaceAddress source = owner.endpoint().address();
				ServerLevel level = server.getLevel(source.dimension());
				if (level != null && !owner.computer().owner().isRemoved()
					&& owner.registration().equals(owner.computer().radioTopologyRegistration())
					&& SubLevelCompat.getLoadedBlockEntity(level, source.localPos()) == owner.computer().owner()
					&& SubLevelCompat.matchesSpace(level, source.localPos(), source.subLevelId())) continue;
				externalListeners.unload(owner.listenerOwner());
				retireRadioRoutes(radioTopology.unload(owner.registration(),
					server.overworld().getGameTime()), null);
			}
		}

		private void computerUnloaded(ProductionComputer computer) {
			requireServerThread();
			if (lifecycle.technicalUnload() || !acceptsWork()) return;
			externalListeners.unload(computer);
			retireRadioRoutes(radioTopology.unload(computer.radioTopologyRegistration(),
				server.overworld().getGameTime()), null);
		}

		private void computerRetired(ProductionComputer computer) {
			requireServerThread();
			if (!acceptsWork()) return;
			externalListeners.unload(computer);
			retireRadioRoutes(radioTopology.retire(computer.radioTopologyRegistration()), null);
		}

		/** Re-publish a Cluster only through its already-owned primary Computer lifecycle. */
		private void redriveClusterTopology(UUID clusterId) {
			clusterId = Objects.requireNonNull(clusterId, "cluster id");
			for (ClusterMember member : ClusterMemberIndex.members(server, clusterId,
				ClusterMemberType.COMPUTER_COORDINATOR))
				if (member instanceof ComputerCoordinatorMember coordinator)
					coordinator.topologyDependencyChanged();
		}

		/** A Cluster primary-frequency mutation republished every exact loaded Computer owner. */
		private void redriveClusterPrimaryFrequency(UUID clusterId) {
			clusterId = Objects.requireNonNull(clusterId, "cluster id");
			for (ClusterMember member : ClusterMemberIndex.members(server, clusterId,
				ClusterMemberType.COMPUTER_COORDINATOR))
				if (member instanceof ComputerCoordinatorMember coordinator)
					coordinator.primaryFrequencyDependencyChanged();
		}

		private RadioTopologyFrame radioTopologyFrame() {
			return radioTopology.frame();
		}

		private boolean radioEndpointRegisteredForTest(RadioEndpoint.Key key) {
			return radioRouter.registeredEndpoint(Objects.requireNonNull(key, "key")).isPresent();
		}

		private Optional<RadioEndpoint> radioEndpointForTest(RadioEndpoint.Key key) {
			return Optional.ofNullable(radioEndpoints.get(Objects.requireNonNull(key, "key")));
		}

		private List<ExternalListenerService.ListenerProjection> externalListenerProjectionForTest(
			UUID computerId) {
			return externalListeners.projection(Objects.requireNonNull(computerId, "computer id"));
		}

		private List<ExternalListenerService.Publication> externalListenerPublicationsForTest(
			UUID computerId) {
			return externalListeners.publications(Objects.requireNonNull(computerId, "computer id"));
		}

		private boolean externalListenerRelayCandidateForTest(RadioEndpoint.Key relayKey) {
			return externalListeners.relayCandidates().stream().anyMatch(candidate ->
				candidate.endpoint().key().equals(Objects.requireNonNull(relayKey, "relay key")));
		}

		private Optional<SubnetBoundaryService.SubnetBoundaryProjection> publicSubnetProjectionForTest(
			RadioEndpoint.Key activeListenerRelayKey) {
			requireServerThread();
			if (!acceptsWork()) return Optional.empty();
			ExternalListenerService.Publication publication = externalListeners.activePublication(
				Objects.requireNonNull(activeListenerRelayKey, "active listener relay key")).orElse(null);
			if (publication == null) return Optional.empty();
			SubnetBoundaryBinding binding = publication.boundaryBinding();
			if (radioRouter.boundaryBinding(binding.sourceClusterId()).filter(binding::equals).isEmpty())
				return Optional.empty();
			UUID publishedClusterId = binding.targetClusterId();
			UUID internalNetworkId = binding.targetNetworkId();
			CommunicationFrequency listenerFrequency = binding.listenerFrequency();
			ClusterEngineState state = runtime.snapshot(publishedClusterId).orElse(null);
			if (state == null || state.members().stream().noneMatch(ClusterEngineState.MemberSnapshot::loaded))
				return Optional.empty();
			List<DurableProductionLibrary> publicLibraries = productionLibraries.values().stream()
				.filter(library -> library.clusterId().equals(publishedClusterId))
				.filter(library -> library.catalog().loaded()
					&& library.reachability() == LibraryRegistration.Reachability.REACHABLE).toList();
			ArrayList<SubnetBoundaryService.PublicServiceSummary> services = new ArrayList<>();
			if (agents.values().stream().anyMatch(agent -> agent.formation().ownerClusterId()
				.equals(publishedClusterId) && agent.process().available()))
				services.add(SubnetBoundaryService.PublicServiceSummary.of(
					SubnetBoundaryService.PublicService.AGENT));
			if (!publicLibraries.isEmpty())
				services.add(SubnetBoundaryService.PublicServiceSummary.of(
					SubnetBoundaryService.PublicService.LIBRARY));
			if (state.goals().values().stream().anyMatch(goal -> goal.state() != GoalState.TERMINAL))
				services.add(SubnetBoundaryService.PublicServiceSummary.of(
					SubnetBoundaryService.PublicService.TASK_ENTRY));
			if (publicLibraries.stream().anyMatch(library -> !library.logisticsIds().isEmpty()))
				services.add(SubnetBoundaryService.PublicServiceSummary.of(
					SubnetBoundaryService.PublicService.LOGISTICS_METADATA));
			return Optional.of(new SubnetBoundaryService(internalNetworkId, publishedClusterId,
				listenerFrequency, services).project());
		}

		private RadioLinkPolicy.LinkResult radioLinkForTest(RadioEndpoint source,
			RadioEndpoint target) {
			return radioLinkPolicy.link(Objects.requireNonNull(source, "source"),
				Objects.requireNonNull(target, "target"), radioTopologyFrame());
		}

		private static RadioTopologyTracker.Pose topologyPose(RadioEndpoint endpoint) {
			SpaceAddress source = endpoint.address();
			return new RadioTopologyTracker.Pose(Optional.ofNullable(source.subLevelId()),
				Vec3.atCenterOf(source.localPos()));
		}

		private RadioEndpoint computerEndpoint(ProductionComputer computer, UUID clusterId,
			UUID memberId, SpaceAddress source, ProductionResourceProfile profile) {
			return new RadioEndpoint(clusterId, memberId, source, computer.radioEndpointGeneration(),
				primaryFrequencyResolver.primaryFrequency(clusterId), profile.processSlots(),
				profile.capabilities());
		}

		private static RadioTopologyLifecycle.Registration listenerTopologyRegistration(
			ExternalListenerService.ListenerOwner owner, RadioEndpoint endpoint) {
			return new RadioTopologyLifecycle.Registration(owner.ownerToken(), endpoint.memberId());
		}

		/** The one server-thread boundary allowed to dereference live Level/Sable pose state. */
		@Nullable
		private RadioTopologyTracker.ResolvedPose resolveCurrentRadioTopology(
			RadioEndpoint endpoint, RadioTopologyTracker.Pose ignoredObservedPose) {
			requireServerThread();
			if (!radioTopology.isCurrent(endpoint)) return null;
			SpaceAddress source = endpoint.address();
			ServerLevel level = server.getLevel(source.dimension());
			if (level == null || !(SubLevelCompat.getLoadedBlockEntity(level, source.localPos())
				instanceof HistoricalProductionHost physical)) return null;
			var computer = physical.production();
			var profile = computer.installedProfile().orElse(null);
			boolean listenerEndpoint = externalListeners.ownsTopologyEndpoint(endpoint, computer);
			if (!listenerEndpoint && (!computer.computerId().filter(endpoint.memberId()::equals).isPresent()
				|| computer.epoch().filter(epoch -> endpoint.clusterId().equals(epoch.groupId())).isEmpty()
				|| profile == null
				|| profile.processSlots() != endpoint.listenerCapacity()
				|| !profile.capabilities().equals(endpoint.capabilities())
				|| !computer.structureOnline()
				|| computer.radioEndpointGeneration() != endpoint.generation())) return null;
			if (listenerEndpoint && (profile == null || !computer.structureOnline())) return null;
			SpaceAddress actualSource = SpaceAddress.capture(level, computer.owner().getBlockPos());
			if (!source.equals(actualSource) || !SubLevelCompat.matchesSpace(level, source.localPos(),
				source.subLevelId())) return null;
			SubLevelAccess structure = SubLevelCompat.getContaining(level, source.localPos(),
				source.subLevelId());
			if (source.subLevelId() != null && structure == null) return null;
			return RadioTopologyProjection.resolve(endpoint, new RadioTopologyProjection.Evidence(
				listenerEndpoint ? endpoint.key()
					: new RadioEndpoint.Key(endpoint.clusterId(), computer.computerId().orElseThrow()),
				listenerEndpoint ? endpoint.generation() : computer.radioEndpointGeneration(), actualSource,
				SubLevelCompat.toWorld(structure, Vec3.atCenterOf(source.localPos())),
				Optional.ofNullable(structure).map(SubLevelAccess::getUniqueId),
				listenerEndpoint ? new RadioTopologyProjection.ProfileProof(endpoint.listenerCapacity(),
					endpoint.capabilities()) : new RadioTopologyProjection.ProfileProof(profile.processSlots(),
					profile.capabilities())));
		}

		private void retireRadioRoutes(List<RadioEndpoint> retired,
			@Nullable RadioEndpoint replacement) {
			for (RadioEndpoint endpoint : retired) {
				if (replacement != null && retainsRouterRegistration(endpoint, replacement)) continue;
				if (radioEndpoints.remove(endpoint.key(), endpoint)) {
					radioComputerOwners.computeIfPresent(endpoint.key(), (key, owner) ->
						owner.endpoint().equals(endpoint) ? null : owner);
					radioRouter.unregister(endpoint.key(), endpoint.generation());
				}
			}
		}

		private static boolean retainsRouterRegistration(RadioEndpoint existing,
			RadioEndpoint replacement) {
			return existing.key().equals(replacement.key())
				&& existing.generation() == replacement.generation()
				&& existing.address().equals(replacement.address());
		}

		private CommunicationFrequency requesterFrequency(UUID clusterId, @Nullable UUID memberId) {
			Objects.requireNonNull(clusterId, "clusterId");
			return primaryFrequencyResolver.primaryFrequency(clusterId);
		}

		private ClusterCommunicationCommandService.AuthorityStatus validateCommunicationAuthority(
			ClusterCommunicationCommandService.CommandAuthority authority) {
			requireServerThread();
			if (!acceptsWork()) return ClusterCommunicationCommandService.AuthorityStatus.AUTHORITY_OFFLINE;
			Optional<UUID> formedEpoch = runtime.snapshot(authority.clusterId())
				.map(ClusterEngineState::epochId);
			ArrayList<ClusterMember> authorityAndCoordinators = new ArrayList<>(
				ClusterMemberIndex.members(server, authority.clusterId(), authority.authority().type()));
			if (authority.authority().type() != ClusterMemberType.COMPUTER_COORDINATOR)
				authorityAndCoordinators.addAll(ClusterMemberIndex.members(server,
					authority.clusterId(), ClusterMemberType.COMPUTER_COORDINATOR));
			List<ProductionCommunicationAuthorityPolicy.AuthorityMember> authorities =
				authorityAndCoordinators.stream().map(member -> {
						Optional<ProductionCommunicationAuthorityPolicy.ComputerCoordinator> computer =
							member instanceof ComputerCoordinatorMember coordinator
								? Optional.of(new ProductionCommunicationAuthorityPolicy
									.ComputerCoordinator(coordinator.hasExactEpoch(
										authority.clusterId(), authority.epochId()),
										coordinator.coordinatorOnline()))
								: Optional.empty();
						return new ProductionCommunicationAuthorityPolicy.AuthorityMember(
							member.memberType(), member.memberId(),
							Optional.ofNullable(member.bindingState()), member.hasValidBindingState(),
							computer);
					}).toList();
			return ProductionCommunicationAuthorityPolicy.validate(authority,
				new ProductionCommunicationAuthorityPolicy.Evidence(formedEpoch, authorities,
					List.of()));
		}

		private Optional<ClusterCommunicationConfiguration> communicationConfiguration(
			UUID clusterId) {
			return communicationDirectory.configuration(Objects.requireNonNull(clusterId, "clusterId"));
		}

		private AgentRevocationDrainState agentRevocationDrainState(UUID clusterId) {
			requireServerThread();
			return new ProductionAgentRevocationEvidenceAdapter(journal, registryAccess,
				recovery::snapshot, () -> runtime.pendingAgentRuns().snapshot(),
				() -> agents.values().stream().map(AgentCoordinator::snapshot).toList(),
				() -> resetServices.values().stream().map(AgentResetService::snapshot).toList())
				.query(clusterId);
		}

		private boolean hasFormedAgentEndpoints(UUID clusterId) {
			return ProductionAgentFormationPreflight.hasExactFormation(clusterId,
				List.copyOf(endpoints.values()));
		}

		private void communicationConfigurationChanged(ClusterCommunicationConfiguration previous,
			ClusterCommunicationConfiguration next) {
			requireServerThread();
			if (!acceptsWork()) return;
			panelDirectoryGeneration = Math.incrementExact(panelDirectoryGeneration);
			if (!previous.primaryFrequency().equals(next.primaryFrequency()))
				redriveClusterPrimaryFrequency(next.clusterId());
			new ProductionAgentRoleLifecycle(List.copyOf(endpoints.values()), agents, resetServices,
				agentDirectory, this::rebuildAgent).configurationChanged(previous, next);
			for (AgentCoordinator coordinator : agents.values()) {
				if (!coordinator.formation().ownerClusterId().equals(next.clusterId())) continue;
				if (next.role() == ClusterServiceRole.AGENT)
					coordinator.publishInputRegistrations(agentDirectory, next);
				else coordinator.withdrawInputRegistrations(agentDirectory);
			}
		}

		private void advanceRadio() {
			Map<UUID, UUID> computationOwners = remoteExpansion.messageOwners();
			Map<UUID, RemoteRequestKey> recoveryOwners = remoteRecoveryMessageOwners();
			LinkedHashSet<UUID> attempted = new LinkedHashSet<>();
			for (RadioRouter.Delivery delivery : radioOutbox.pump(radioRouter,
				server.overworld().getGameTime(), radioTopologyFrame())) {
				attempted.add(delivery.envelope().messageId());
				applyDeliveredRadioEnvelope(delivery.envelope(), computationOwners, recoveryOwners);
			}
			for (DurableRadioOutbox.Entry entry : radioOutbox.deliveredButUnapplied(
				config.maxCommittedIntentsPerTick(), Optional.ofNullable(deliveredRadioRetryCursor))) {
				deliveredRadioRetryCursor = entry.envelope().messageId();
				if (attempted.add(entry.envelope().messageId())) applyDeliveredRadioEnvelope(
					entry.envelope(), computationOwners, recoveryOwners);
			}
		}



		private Map<UUID, RemoteRequestKey> remoteRecoveryMessageOwners() {
			LinkedHashMap<UUID, RemoteRequestKey> owners = new LinkedHashMap<>();
			for (RemoteRecoveryExchange exchange : remoteRecoveryExchanges.values())
				for (UUID messageId : recoveryMessageIds(exchange))
					if (owners.put(messageId, exchange.key()) != null)
						throw new IllegalStateException("duplicate remote recovery message owner");
			return Map.copyOf(owners);
		}

		private void applyDeliveredRadioEnvelope(RadioEnvelope envelope,
			Map<UUID, UUID> computationOwners,
			Map<UUID, RemoteRequestKey> recoveryOwners) {
			if (remoteAgentDispatches.containsKey(envelope.messageId())) return;
			RemoteComputationExchange computation = remoteExpansion.exchange(computationOwners.get(envelope.messageId())).orElse(null);
			if (computation != null) {
				remoteExpansion.receive(computation, envelope);
				return;
			}
			RemoteRecoveryExchange exchange = remoteRecoveryExchanges.get(
				recoveryOwners.get(envelope.messageId()));
			if (exchange != null) {
				applyRemoteRecoveryDelivery(exchange, envelope);
				return;
			}
			if (genericRadioProtocolHasDurableRecipientStore(envelope.payload()))
				radioOutbox.markApplied(envelope.messageId());
		}

		private void removeRetiredRunArtifactsFromMemory(UUID runId) {
			inputContracts.remove(runId);
			outputContracts.remove(runId);
			outputPolicies.remove(runId);
			outputProbes.remove(runId);
			signalProbes.remove(runId);
			outputNativeReceipts.entrySet().removeIf(entry -> entry.getValue().runId().equals(runId));
			outputCapacities.remove(runId);
			requestKeysByRun.remove(runId);
			planDispatch.forgetSubmitted(runId);
			safeCreateRetries.remove(runId);
		}

		private void advanceRemoteRecoveryExchanges() {
			for (RemoteRecoveryExchange exchange : List.copyOf(remoteRecoveryExchanges.values())) {
				retireDurablyConsumedRecoveryMessages(exchange);
				switch (exchange.phase()) {
					case OFFER_PENDING -> radioOutbox.offer(exchange.offerEnvelope(),
						exchange.offerRelays());
					case TERMINAL_PENDING -> radioOutbox.offer(exchange.terminalEnvelope(),
						exchange.terminalRelays());
					case ACK_READY -> {
						if (droppedRecoveryAcknowledgements.remove(exchange.goalId())) {
							persistRemoteRecoveryExchange(exchange.withPhase(
								RemoteRecoveryPhase.ACK_LOST));
						} else if (!radioEnvelopeEndpointsAvailable(exchange.acknowledgementEnvelope())) {
							persistRemoteRecoveryExchange(exchange.withPhase(
								RemoteRecoveryPhase.ACK_LOST));
						} else radioOutbox.offer(exchange.acknowledgementEnvelope(),
							exchange.acknowledgementRelays());
					}
					case STATUS_QUERY_PENDING -> {
						if (radioEnvelopeEndpointsAvailable(exchange.statusQueryEnvelope()))
							radioOutbox.offer(exchange.statusQueryEnvelope(),
								exchange.statusQueryRelays());
					}
					case STATUS_CONFIRMED -> {
						if (radioEnvelopeEndpointsAvailable(exchange.acknowledgementEnvelope()))
							radioOutbox.offer(exchange.acknowledgementEnvelope(),
								exchange.acknowledgementRelays());
					}
					case ACK_LOST, RECONCILED -> {}
				}
			}
		}

		private void applyRemoteRecoveryDelivery(RemoteRecoveryExchange exchange,
			RadioEnvelope envelope) {
			if (envelope.messageId().equals(exchange.offerEnvelope().messageId())) {
				if (exchange.phase() == RemoteRecoveryPhase.OFFER_PENDING) {
					publishRecoveryTransition(() -> persistRemoteRecoveryExchange(
						exchange.withPhase(RemoteRecoveryPhase.TERMINAL_PENDING)),
						messageId -> radioOutbox.markApplied(messageId),
						List.of(envelope.messageId()));
				}
				return;
			}
			if (envelope.messageId().equals(exchange.terminalEnvelope().messageId())) {
				if (exchange.phase() == RemoteRecoveryPhase.TERMINAL_PENDING) {
					publishRecoveryTransition(() -> persistRemoteRecoveryExchange(
						exchange.withPhase(RemoteRecoveryPhase.ACK_READY)),
						messageId -> radioOutbox.markApplied(messageId),
						List.of(envelope.messageId()));
				}
				return;
			}
			if (envelope.messageId().equals(exchange.statusQueryEnvelope().messageId())) {
				AgentProtocolMessage.Status status = recovery.protocolStatus(
					new AgentProtocolMessage.StatusQuery(exchange.key()));
				if (status.code() == AgentProtocolMessage.StatusCode.PRESENT
					&& status.record().filter(RemoteAgentRecord::isTerminal)
						.filter(record -> record.revision() == exchange.terminalRevision()).isPresent()) {
					publishRecoveryTransition(() -> persistRemoteRecoveryExchange(
						exchange.withPhase(RemoteRecoveryPhase.STATUS_CONFIRMED)),
						messageId -> radioOutbox.markApplied(messageId),
						List.of(envelope.messageId()));
				} else if (status.code() == AgentProtocolMessage.StatusCode.ACKNOWLEDGED_TOMBSTONE
					&& recovery.acknowledge(new AgentProtocolMessage.EvidenceAck(exchange.key(),
						exchange.terminalRevision()))) {
					publishRecoveryTransition(() -> persistRemoteRecoveryExchange(
						exchange.withPhase(RemoteRecoveryPhase.RECONCILED)),
						messageId -> radioOutbox.markApplied(messageId),
						List.of(envelope.messageId(),
							exchange.acknowledgementEnvelope().messageId()));
				}
				return;
			}
			if (envelope.messageId().equals(exchange.acknowledgementEnvelope().messageId())
				&& recovery.acknowledge(new AgentProtocolMessage.EvidenceAck(exchange.key(),
					exchange.terminalRevision()))) {
				publishRecoveryTransition(() -> persistRemoteRecoveryExchange(
					exchange.withPhase(RemoteRecoveryPhase.RECONCILED)),
					messageId -> radioOutbox.markApplied(messageId),
					List.of(envelope.messageId()));
			}
		}

		private void retireDurablyConsumedRecoveryMessages(RemoteRecoveryExchange exchange) {
			List<UUID> ordered = List.of(exchange.offerEnvelope().messageId(),
				exchange.terminalEnvelope().messageId(), exchange.statusQueryEnvelope().messageId(),
				exchange.acknowledgementEnvelope().messageId());
			for (UUID messageId : ordered.subList(0,
				retiredRecoveryMessagePrefix(exchange.phase()))) radioOutbox.markApplied(messageId);
		}

		private boolean radioEnvelopeEndpointsAvailable(RadioEnvelope envelope) {
			RadioEndpoint source = radioEndpoints.get(envelope.sourceHop().key());
			RadioEndpoint target = radioEndpoints.get(envelope.targetHop().key());
			return source != null && target != null
				&& source.generation() == envelope.sourceGeneration()
				&& target.generation() == envelope.targetGeneration();
		}

		private static Set<UUID> recoveryMessageIds(RemoteRecoveryExchange exchange) {
			return Set.of(exchange.offerEnvelope().messageId(), exchange.terminalEnvelope().messageId(),
				exchange.statusQueryEnvelope().messageId(),
				exchange.acknowledgementEnvelope().messageId());
		}

		private boolean routeRadio(RadioEnvelope envelope, List<RadioEndpoint.Hop> relays) {
			return offerDurableGenericRadio(envelope, relays);
		}

		/* Test composition uses this same lifecycle/admission path; only delivery stays observable. */
		private boolean offerRadioForTest(RadioEnvelope envelope, List<RadioEndpoint.Hop> relays) {
			return offerDurableGenericRadio(envelope, relays);
		}

		private boolean offerDurableGenericRadio(RadioEnvelope envelope, List<RadioEndpoint.Hop> relays) {
			requireServerThread();
			if (!acceptsWork()) return false;
			if (!genericRadioProtocolHasDurableRecipientStore(envelope.payload())) return false;
			try {
				return radioOutbox.offer(envelope, relays);
			} catch (IllegalArgumentException | IllegalStateException rejected) { return false; }
		}

		private List<RadioRouter.Delivery> pumpRadioOutboxForTest(long serverTick) {
			requireServerThread();
			return acceptsWork() ? radioOutbox.pump(radioRouter, serverTick) : List.of();
		}

		private boolean markRadioAppliedForTest(UUID messageId) {
			requireServerThread();
			return acceptsWork() && radioOutbox.markApplied(messageId);
		}

		private int retireAppliedRadioRoutesForTest() {
			requireServerThread();
			return acceptsWork() ? radioOutbox.retireAppliedRoutes(radioRouter) : 0;
		}

		private Optional<RadioRouter.RouteStatus> remoteAgentRadioStatus(UUID goalId) {
			Objects.requireNonNull(goalId, "goalId");
			Set<UUID> runIds = remoteAgentRunIds(goalId);
			return remoteAgentDispatches.values().stream()
				.filter(dispatch -> runIds.contains(dispatch.intent().runId()))
				.map(dispatch -> radioOutbox.status(dispatch.messageId()).orElse(null))
				.filter(Objects::nonNull).findFirst();
		}

		/** Read-only C1 observation over the existing durable outbox and router route progress. */
		private boolean remoteAgentRadioProgressedPastFirstRelay(UUID goalId) {
			Set<UUID> runIds = remoteAgentRunIds(Objects.requireNonNull(goalId, "goalId"));
			return remoteAgentDispatches.values().stream().filter(dispatch ->
				runIds.contains(dispatch.intent().runId()) && !dispatch.relays().isEmpty())
				.anyMatch(dispatch -> radioOutbox.status(dispatch.messageId()).orElse(null)
					== RadioRouter.RouteStatus.IN_TRANSIT
					&& radioRouter.routeProgress(dispatch.messageId()).nextHopIndex() > 1);
		}

		private Optional<RadioEndpoint.Key> remoteAgentCurrentRelayForTest(UUID goalId) {
			Set<UUID> runIds = remoteAgentRunIds(Objects.requireNonNull(goalId, "goalId"));
			return remoteAgentDispatches.values().stream().filter(dispatch ->
				runIds.contains(dispatch.intent().runId()) && radioOutbox.status(dispatch.messageId())
					.orElse(null) == RadioRouter.RouteStatus.IN_TRANSIT)
				.map(dispatch -> radioRouter.routeProgress(dispatch.messageId()).heldRelayListener())
				.flatMap(Optional::stream).findFirst();
		}

		private Set<UUID> remoteAgentRunIds(UUID goalId) {
			return planRepository.snapshot().stream().filter(plan -> plan.goalId().equals(goalId))
				.map(plan -> plan.runs().currentRun()).filter(Objects::nonNull)
				.map(ProductionRun::runId).collect(Collectors.toSet());
		}

		private Optional<RadioRouter.RouteStatus> radioStatus(UUID messageId) {
			requireServerThread();
			return radioOutbox.status(messageId);
		}

		private Optional<UUID> radioGoalOwner(UUID leaseId) {
			requireServerThread();
			return radioRouter.goalOwner(leaseId);
		}

		private Optional<RemoteComputationExchange> remoteComputation(UUID goalId) {
			requireServerThread();
			UUID exactGoal = Objects.requireNonNull(goalId, "goalId");
			return remoteExpansion.exchanges().stream()
				.filter(exchange -> exchange.goalId().equals(exactGoal))
				.sorted(Comparator.comparing(RemoteComputationExchange::runId).reversed())
				.findFirst();
		}

		private Optional<RemoteComputationJob> remoteComputationJob(UUID goalId) {
			requireServerThread();
			UUID exactGoal = Objects.requireNonNull(goalId, "goalId");
			return remoteExpansion.jobs().stream()
				.filter(job -> job.lease().sourceGoalId().equals(exactGoal))
				.sorted(Comparator.comparing(RemoteComputationJob::runId).reversed())
				.findFirst();
		}

		private Optional<Job> localComputationJob(UUID goalId) {
			requireServerThread();
			return localExpansion.jobForGoal(goalId);
		}

		private int localComputationCount() {
			requireServerThread();
			return localExpansion.count();
		}

		private int remoteComputationActiveLeaseCount() {
			requireServerThread();
			return radioRouter.activeLeaseCount();
		}

		private int remoteComputationActiveRouteCount() {
			requireServerThread();
			return radioRouter.activeLeaseRouteCount();
		}

		private int remoteComputationActiveRouteCount(UUID leaseId) {
			requireServerThread();
			return radioRouter.activeLeaseRouteCount(leaseId);
		}

		private int retiredRemoteComputationCount() {
			requireServerThread();
			return remoteExpansion.retiredCount();
		}

		private int retiredRemoteComputationCount(UUID sourceGoalId) {
			requireServerThread();
			UUID exactGoalId = Objects.requireNonNull(sourceGoalId, "sourceGoalId");
			return remoteExpansion.retiredCount(exactGoalId);
		}

		private boolean radioCanCommitMaterial(RadioEndpoint.Key receiver, UUID leaseId,
			long revision) {
			requireServerThread();
			return radioRouter.canCommitMaterialIntent(receiver, leaseId, revision);
		}

		private Optional<ProductionStatus> productionStatus(UUID goalId) {
			requireServerThread();
			FrozenProductionPlan plan = planRepository.current(Objects.requireNonNull(goalId));
			if (plan == null) return Optional.empty();
			UUID currentRunId = plan.runs().currentRun() == null ? null
				: plan.runs().currentRun().runId();
			RecoveryLedger.State recoveryState = recovery.snapshot();
			List<Map.Entry<RemoteRequestKey, RemoteAgentRecord>> terminalEvidence =
				recoveryState.records().entrySet().stream()
					.filter(entry -> entry.getValue().isTerminal())
					.filter(entry -> entry.getValue().inputState()
						.map(state -> state.run().goalId().equals(goalId)).orElse(false)
						|| entry.getValue().outputState()
							.map(state -> state.run().goalId().equals(goalId)).orElse(false))
					.toList();
			GoalObservationState observation = goalSettlement.observation(goalId).orElse(null);
			UUID lastReceivedRunId = observation == null ? null
				: observation.lastReceivedRunId().orElse(null);
			ArrayList<RemoteRequestKey> terminalKeys = new ArrayList<>(terminalEvidence.stream()
				.map(Map.Entry::getKey).toList());
			if (lastReceivedRunId != null)
				recoveryState.acknowledgedTombstones().keySet().stream()
					.filter(key -> key.runId().equals(lastReceivedRunId))
					.forEach(terminalKeys::add);
			HashSet<UUID> liveRunIds = new HashSet<>(inputContracts.keySet());
			liveRunIds.addAll(outputContracts.keySet());
			liveRunIds.addAll(planDispatch.submittedRunIds());
			UUID runId = selectProductionStatusRunId(currentRunId, liveRunIds,
				terminalKeys).orElse(null);
			if (runId == null) return Optional.empty();
			RemoteAgentRecord terminal = terminalEvidence.stream()
				.filter(entry -> entry.getKey().runId().equals(runId))
				.max(Map.Entry.comparingByValue(Comparator.comparingLong(RemoteAgentRecord::revision)))
				.map(Map.Entry::getValue).orElse(null);
			AgentInputContract activeInput = inputContracts.get(runId);
			ProductionOutputContract activeOutput = outputContracts.get(runId);
			AgentInputContract.State input = activeInput == null
				? terminal == null ? null : terminal.inputState().orElse(null)
				: activeInput.snapshot();
			ProductionOutputContract.Snapshot output = activeOutput == null
				? terminal == null ? null : terminal.outputState().orElse(null)
				: activeOutput.snapshot();
			boolean retiredReceived = activeOutput == null && runId.equals(lastReceivedRunId);
			OutputProbeState probe = outputProbes.get(runId);
			return Optional.of(new ProductionStatus(runId, planDispatch.isSubmitted(runId),
				input == null ? Optional.empty() : Optional.of(input.phase()),
				retiredReceived ? Optional.of(ProductionOutputContract.Milestone.OUTPUT_RECEIVED)
					: output == null ? Optional.empty() : Optional.of(output.milestone()),
				retiredReceived || output == null ? Optional.empty() : output.waitReason(),
				probe == null ? Map.of() : probe.lastCounts()));
		}

		private boolean suspendNextInputDispatch(UUID clusterId) {
			requireServerThread();
			if (!acceptsWork()) return false;
			return suspendedInputDispatchClusters.add(Objects.requireNonNull(clusterId));
		}

		private Optional<CreateLogisticsGateway.CommitAttemptState> inputCommitAttemptState(
			UUID goalId) {
			requireServerThread();
			FrozenProductionPlan plan = planRepository.current(Objects.requireNonNull(goalId));
			if (plan == null) return Optional.empty();
			UUID runId = inputContracts.values().stream().map(AgentInputContract::snapshot)
				.filter(state -> state.run().goalId().equals(goalId))
				.map(state -> state.run().runId()).findFirst().orElse(null);
			if (runId == null && plan.runs().currentRun() != null)
				runId = plan.runs().currentRun().runId();
			if (runId == null) return Optional.empty();
			UUID exactRunId = runId;
			return journal.readPrefix("input_commit/", CreateLogisticsGateway.PersistedCommit.class,
				registryAccess).values().stream()
				.filter(commit -> commit.command().runId().equals(exactRunId))
				.map(CreateLogisticsGateway.PersistedCommit::state).findFirst();
		}

		private Optional<RemoteRecoveryPhase> remoteRecoveryStatus(UUID goalId) {
			requireServerThread();
			goalId = Objects.requireNonNull(goalId, "goalId");
			return ProductionGoalSettlement.visibleRemoteRecoveryPhase(goalId, remoteRecoveryExchanges,
				goalSettlement.observation(goalId).orElse(null), recovery.snapshot());
		}

		private boolean dropNextRecoveryAcknowledgement(UUID goalId) {
			requireServerThread();
			if (!acceptsWork()) return false;
			goalId = Objects.requireNonNull(goalId, "goalId");
			return planRepository.containsGoal(goalId)
				&& droppedRecoveryAcknowledgements.add(goalId);
		}

		private int recoveryCorrectnessOccupancy() {
			requireServerThread();
			return recovery.correctnessOccupancy();
		}

		private boolean constrainRecoveryAdmissionHeadroom(UUID clusterId,
			int additionalRecords) {
			requireServerThread();
			if (!acceptsWork()) return false;
			clusterId = Objects.requireNonNull(clusterId, "clusterId");
			if (additionalRecords <= 0 || recoveryAdmissionCeilings.containsKey(clusterId))
				return false;
			try {
				int ceiling = Math.addExact(recovery.correctnessOccupancy(), additionalRecords);
				if (ceiling > config.recoveryLedgerCapacity()) return false;
				recoveryAdmissionCeilings.put(clusterId, ceiling);
				return true;
			} catch (ArithmeticException invalid) { return false; }
		}

		private boolean restoreConfiguredRecoveryAdmission(UUID clusterId) {
			requireServerThread();
			if (!acceptsWork()) return false;
			return recoveryAdmissionCeilings.remove(Objects.requireNonNull(clusterId)) != null;
		}


		private List<RadioEndpoint.Hop> discoverRadioRelays(RadioEndpoint source,
			RadioEndpoint target, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency) {
			if (radioLinkPossible(source, target, frequency)) return List.of();
			LinkedHashMap<RadioEndpoint.Key, RadioEndpoint> candidates = new LinkedHashMap<>();
			radioEndpoints.values().forEach(endpoint -> candidates.put(endpoint.key(), endpoint));
			listenerRelayCandidates().forEach(endpoint -> candidates.put(endpoint.key(), endpoint));
			return candidates.values().stream().filter(candidate ->
				!candidate.key().equals(source.key()) && !candidate.key().equals(target.key())
					&& candidate.frequency().equals(frequency)
					&& candidate.supports(ProductionCapability.EXTENDED_RADIO)
					&& radioLinkPossible(source, candidate, frequency)
					&& radioLinkPossible(candidate, target, frequency))
				.sorted(Comparator.comparing(RadioEndpoint::memberId)).findFirst()
				.map(endpoint -> List.of(endpoint.hop())).orElse(List.of());
		}

		/** Exact active listener relays are a fenced production registry, never inferred from frames. */
		private List<RadioEndpoint> listenerRelayCandidates() {
			return externalListeners.relayCandidates().stream().filter(candidate ->
				radioRouter.registeredEndpoint(candidate.endpoint().key())
					.filter(candidate.endpoint()::equals).isPresent())
				.map(ExternalListenerRuntime.RelayCandidate::endpoint).toList();
		}

		private boolean radioLinkPossible(RadioEndpoint source, RadioEndpoint target,
			com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency) {
			RadioLinkPolicy.LinkResult link = radioLinkPolicy.link(source, target,
				radioTopologyFrame());
			if (!link.reachable()) return false;
			return source.clusterId().equals(target.clusterId()) || frequency.configured()
				&& source.frequency().equals(frequency) && target.frequency().equals(frequency);
		}

		private void intentResolved(ProductionEffect intent, boolean committed) {
			requireServerThread();
			if (!acceptsWork()) return;
			if (!(intent instanceof ProductionEffect.CreateDispatch dispatch)) return;
			if (committed) {
				rejectedCreateIntentsAwaitingConfirmation.remove(dispatch.runId());
				SafeCreateRetryProof retry = safeCreateRetries.get(dispatch.runId());
				if (retry != null && retry.intent().intentId().equals(dispatch.intentId())) {
					safeCreateRetries.remove(dispatch.runId(), retry);
					journal.remove(ProductionSafeRetryStore.JOURNAL_PREFIX + dispatch.runId());
				}
				return;
			}
			CreateLogisticsGateway.PersistedCommit inputAttempt = journal.read(
				"input_commit/" + intent.intentId(), CreateLogisticsGateway.PersistedCommit.class,
				registryAccess).orElse(null);
			Optional<SafeCreateRetryProof> safeRetry = inputAttempt == null ? Optional.empty()
				: SafeCreateRetryProof.from(dispatch, inputAttempt);
			boolean authorizedSafeNoMaterial = false;
			if (safeRetry.isPresent()) {
				AgentInputContract input = inputContracts.get(dispatch.runId());
				SafeCreateRetryProof exact = safeRetryRecovery.authorize(dispatch, inputAttempt,
					input == null ? Optional.empty() : Optional.of(input.snapshot())).orElse(null);
				if (exact != null) {
					safeCreateRetries.persistAndInstall(dispatch, inputAttempt, exact);
					authorizedSafeNoMaterial = true;
				}
			}
			boolean durableEvidence = (inputContracts.containsKey(dispatch.runId())
				&& !authorizedSafeNoMaterial)
				|| inputAttempt != null && !inputAttempt.allowsRetryWithoutMaterial()
				|| journal.read("bare_commit/" + intent.intentId(),
					CreateLogisticsGateway.PersistedBareCommit.class, registryAccess).isPresent();
			if (!durableEvidence) {
				PendingCreateRejection rejection = PendingCreateRejection.from(dispatch);
				PendingCreateRejection prior =
					rejectedCreateIntentsAwaitingConfirmation.get(dispatch.runId());
				if (prior != null && !prior.equals(rejection))
					throw new IllegalStateException("conflicting Create rejection confirmation");
				if (prior == null) {
					if (rejectedCreateIntentsAwaitingConfirmation.size()
						== ClusterEngineState.MAX_PENDING_INTENTS)
						throw new IllegalStateException("Create rejection confirmation invariant exceeded");
					rejectedCreateIntentsAwaitingConfirmation.put(dispatch.runId(), rejection);
					rejectedCreateConfirmationQueue.addLast(dispatch.runId());
				}
				planDispatch.forgetSubmitted(dispatch.runId());
			}
		}








		/**
		 * A loaded native input configured as an Agent endpoint owns its physical route even
		 * before the Cluster declares the AGENT service.  Declaration controls directory
		 * publication; it must never turn the same claimed Gauge back into a bare provider.
		 */
		private boolean loadedNativeAgentInputClaims(CreateRouteKey route) {
			route = Objects.requireNonNull(route, "route");
			for (WeakReference<FactoryPanelBlockEntity> reference : nativePanels.values()) {
				FactoryPanelBlockEntity panel = reference.get();
				if (!(panel instanceof FactoryPanelAgentAccess access) || panel.isRemoved() || panel.getLevel() == null) continue;
				FactoryPanelAgentAccess.Configuration configuration =
					access.createBiotech$getAgentConfiguration()
						.orElse(null);
				if (configuration == null
					|| configuration.role()
						!= com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole.INPUT)
					continue;
				UUID network = endpointNetwork(panel, configuration).orElse(null);
				if (network != null && route.equals(new CreateRouteKey(network,
					configuration.normalizedAddress()))) return true;
			}
			return false;
		}


		private boolean commitBare(ProductionEffect.CreateDispatch intent, Map<StackKey, Long> manifest,
			AgentResolution resolution, ClusterEngineState state, GoalRecord goal,
			FrozenProductionPlan plan) {
			CreateLogisticsGateway.BareDispatchRequest request =
				new CreateLogisticsGateway.BareDispatchRequest(intent.intentId(), intent.runId(),
					intent.route(), intent.generation(), 0, plan.patternKind(),
					plan.patternKind() == PatternKind.STANDARD, manifest);
			CreateLogisticsGateway.BareDispatchAdapter gateway =
				CreateLogisticsGateway.BareDispatchAdapter.usingCreate(ignored ->
					new CreateLogisticsGateway.BareCommitContext(
						agentDirectory.resolve(intent.route(), intent.clusterId(),
							requesterFrequency(intent.clusterId(), null)),
						0L,
						plan.patternKind(), plan.patternKind() == PatternKind.STANDARD,
						intent.generation()),
					server::isSameThread, journal.bareCommitStore(registryAccess));
			CreateLogisticsGateway.BareCommitResult result = gateway.commit(request);
			if (result.committed()) return true;
			return waitSource(state, goal,
				result.rejection() == CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_UNCERTAIN
					? WaitReason.RESET_REQUIRED : WaitReason.MATERIAL);
		}

		private boolean scheduleRemoteAgentDispatch(ProductionEffect.CreateDispatch intent,
			ClusterEngineState sourceState, GoalRecord goal, ProcessRecord process,
			com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration registration,
			com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency requesterFrequency) {
			RadioEndpoint source = radioEndpoints.get(new RadioEndpoint.Key(intent.clusterId(),
				process.owningComputerId()));
			AgentCoordinator targetCoordinator = agents.get(registration.agentId());
			UUID targetMember = targetCoordinator == null ? null
				: targetCoordinator.process().residentSlot().map(AgentProcess.ResidentSlot::computerId)
					.orElse(null);
			RadioEndpoint target = targetMember == null ? null : radioEndpoints.get(
				new RadioEndpoint.Key(registration.ownerClusterId(), targetMember));
			if (source == null || target == null || !requesterFrequency.configured()
				|| !source.frequency().equals(requesterFrequency)
				|| !target.frequency().equals(requesterFrequency)
				|| !registration.frequency().equals(requesterFrequency))
				return waitSource(sourceState, goal, WaitReason.ROUTE);
			UUID messageId = UUID.nameUUIDFromBytes(("agent-radio:" + intent.intentId())
				.getBytes(StandardCharsets.UTF_8));
			RadioEnvelope envelope = RadioEnvelope.create(messageId, source, target,
				requesterFrequency, new ProcessProtocolMessage.Data(
					"agent-dispatch:" + intent.intentId()));
			boolean direct = radioLinkPossible(source, target, requesterFrequency);
			List<RadioEndpoint.Hop> relays = discoverRadioRelays(source, target, requesterFrequency);
			if (!direct && relays.isEmpty()) return waitSource(sourceState, goal, WaitReason.ROUTE);
			RemoteAgentRadioDispatch proposed = new RemoteAgentRadioDispatch(messageId, intent,
				registration.agentId(), registration.agentGeneration(), requesterFrequency,
				envelope, relays, false);
			RemoteAgentRadioDispatch known = remoteAgentDispatches.get(messageId);
			if (known != null && !known.equals(proposed) && !known.equals(proposed.complete()))
				return false;
			if (known == null) {
				journal.write("remote_agent_dispatch/" + messageId, proposed,
					registryAccess);
				remoteAgentDispatches.put(messageId, proposed);
			}
			return radioOutbox.offer(envelope, relays);
		}

		private void advanceRemoteAgentDispatches() {
			for (RemoteAgentRadioDispatch dispatch : List.copyOf(remoteAgentDispatches.values())) {
				if (dispatch.committed()) {
					radioOutbox.markApplied(dispatch.messageId());
					continue;
				}
				radioOutbox.offer(dispatch.envelope(), dispatch.relays());
				if (radioOutbox.status(dispatch.messageId()).orElse(null)
					!= RadioRouter.RouteStatus.DELIVERED) continue;
				ProductionEffect.CreateDispatch intent = dispatch.intent();
				ClusterEngineState source = runtime.snapshot(intent.clusterId()).orElse(null);
				ProcessRecord process = source == null ? null : source.processes().get(intent.processId());
				GoalRecord goal = process == null ? null : source.goals().get(process.goalId());
				FrozenProductionPlan plan = goal == null ? null : planRepository.current(goal.goalId());
				if (source == null || goal == null || plan == null
					|| plan.selectedProvider().providerKind() != RouteCandidate.ProviderKind.AGENT) continue;
				Map<StackKey, Long> manifest;
				try { manifest = DispatchManifest.decode(intent.ingredients()); }
				catch (RuntimeException invalid) { continue; }
				boolean committed = ProductionInputDispatch.replayBeforeProvider(
					() -> inputDispatch.replay(intent, manifest, source, goal, plan,
						dispatch.requesterFrequency()),
					() -> {
						if (runtime.revalidateProductionProvider(intent.clusterId(),
							intent.route().logisticsNetworkId(), plan.selectedProvider()).isEmpty())
							return false;
						AgentResolution resolution = agentDirectory.resolve(intent.route(),
							intent.clusterId(), dispatch.requesterFrequency());
						if (!(resolution instanceof AgentResolution.Unique unique)
							|| !unique.registration().agentId().equals(dispatch.agentId())
							|| unique.registration().agentGeneration() != dispatch.agentGeneration())
							return false;
						return commitAgent(intent, manifest, source, goal, unique.registration(), plan);
					});
				if (!committed) continue;
				RemoteAgentRadioDispatch completed = dispatch.complete();
				journal.write("remote_agent_dispatch/" + dispatch.messageId(), completed,
					registryAccess);
				remoteAgentDispatches.put(dispatch.messageId(), completed);
				radioOutbox.markApplied(dispatch.messageId());
			}
		}

		private boolean commitAgent(ProductionEffect.CreateDispatch intent, Map<StackKey, Long> manifest,
			ClusterEngineState sourceState, GoalRecord goal,
			com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration registration,
			FrozenProductionPlan plan) {
			AgentCoordinator coordinator = agents.get(registration.agentId());
			if (coordinator == null)
				return waitSource(sourceState, goal, WaitReason.AGENT_UNLOADED);
			if (!coordinator.process().available())
				return waitSource(sourceState, goal, WaitReason.PROCESS);
			if (plan.stages().stream().anyMatch(stage -> coordinator.formation().inputs().stream()
				.noneMatch(input -> input.route().equals(stage.route()))))
				return waitSource(sourceState, goal, WaitReason.ROUTE);

			AgentInputContract existing = inputContracts.get(intent.runId());
			if (existing != null) {
				if (replayInputCommit(existing, intent)) return true;
				LOGGER.error("Agent input replay proof mismatch for run {} in phase {}",
					intent.runId(), existing.phase());
				existing.stopAfterIrreversibleBoundary(existing.snapshot().run().fence());
				RemoteRequestKey existingKey = requestKeysByRun.get(intent.runId());
				AgentResetService existingReset = resetServices.get(registration.agentId());
				if (existingKey != null && existingReset != null) existingReset.requireReset(existingKey);
				return waitSource(sourceState, goal, WaitReason.RESET_REQUIRED);
			}

			OutputSettings outputSettings = outputSettings(coordinator.formation()).orElse(null);
			if (outputSettings == null)
				return waitSource(sourceState, goal, WaitReason.ROUTE);
			ProductionRun frozen = plan.materializeCurrentRun(intent.generation());
			ProductionRun run = new ProductionRun(frozen.runId(), frozen.goalId(), frozen.patternId(),
				frozen.fence(), frozen.runMultiplier(), frozen.inputMode(), frozen.inputRoutes(),
				outputSettings.returnWarehouseRoute(), outputSettings.generation(), frozen.manifest());
			List<AgentInputContract.StageInput> stages = plan.expandedStages(run).stream()
				.map(stage -> new AgentInputContract.StageInput(stage.index(), stage.route(),
					stage.manifest())).toList();
			ProductionOutputContract output = ensureOutputContract(coordinator, sourceState, goal, run,
				outputSettings);
			if (output == null)
				return waitSource(sourceState, goal, WaitReason.ROUTE);

			ProductionRequestTicket ticket = new ProductionRequestTicket(intent.intentId(),
				intent.runId(), intent.generation(), intent.clusterId(), registration.agentId(),
				intent.route(), run.patternId(), run.inputMode(), run.runMultiplier(),
				runtime.pendingAgentRuns().nextSequence(),
				goal.policy().priority(), intent.generation());
			RemoteRequestKey key = new RemoteRequestKey(ticket.requestId(), ticket.runId(),
				ticket.fence(), sourceState.generation(), registration.agentGeneration());
			ProductionOutputCapacity capacity = outputCapacities.computeIfAbsent(run.runId(),
				ignored -> new ProductionOutputCapacity(sourceState.clusterId(), run.runId(),
					run.goalId()));
			ProductionOutputContract.StartResult outputStarted = output.milestone()
				== ProductionOutputContract.Milestone.WAITING_INPUT_READY
				? output.beginAtInputReady(capacity)
				: new ProductionOutputContract.StartResult(output.reattachCapacity(capacity),
					output.waitReason());
			if (!outputStarted.started() || !output.inputDispatchAllowed()) {
				return waitSource(sourceState, goal,
					outputStarted.waitReason().orElse(WaitReason.OUTPUT_COMPUTE));
			}
			AgentProtocolMessage.Offer protocolOffer = new AgentProtocolMessage.Offer(key, ticket);
			Integer scopedCeiling = recoveryAdmissionCeilings.get(sourceState.clusterId());
			RecoveryLedger.OfferResult offered = scopedCeiling == null
				? recovery.offer(protocolOffer, runtime.pendingAgentRuns(), registration)
				: recovery.offerWithinCapacity(protocolOffer, runtime.pendingAgentRuns(), registration,
					scopedCeiling);
			RecoveryOfferAction offerAction = recoveryOfferAction(offered.disposition());
			if (offerAction != RecoveryOfferAction.PROCEED) {
				output.abortBeforeInputDispatch();
				if (offerAction == RecoveryOfferAction.FAIL_RESET_REQUIRED)
					LOGGER.error("Rejected invalid recovery offer {} for run {}",
						offered.disposition(), intent.runId());
				return waitSource(sourceState, goal, recoveryOfferWaitReason(offerAction));
			}
			AgentResetService reset = resetServices.get(registration.agentId());
			if (reset == null || !reset.beginFutureContract(key)
					&& !reset.snapshot().activeKey().filter(key::equals).isPresent()) {
				recovery.rollbackAdmission(key, runtime.pendingAgentRuns(), coordinator.process());
				output.abortBeforeInputDispatch();
				return waitSource(sourceState, goal, WaitReason.RESET_REQUIRED);
			}
			if (!recovery.reserve(key, runtime.pendingAgentRuns(), coordinator.process())) {
				reset.abandonFutureContract(key);
				recovery.rollbackAdmission(key, runtime.pendingAgentRuns(), coordinator.process());
				output.abortBeforeInputDispatch();
				return waitSource(sourceState, goal, WaitReason.PROCESS);
			}
			AgentInputContract contract = new AgentInputContract(registration.agentId(),
				registration.agentGeneration(), output.snapshot().outputObservationEpoch(), run,
				plan.patternKind() == PatternKind.SEQUENCED
					? AgentInputContract.ExecutionShape.SEQUENCED
					: AgentInputContract.ExecutionShape.STANDARD,
				stages,
				config.inputStallTicks(), journal.inputContractStore(intent.runId(),
					registryAccess));
			inputContracts.put(intent.runId(), contract);
			requestKeysByRun.put(intent.runId(), key);
			Map<StackKey, Long> firstManifest = contract.currentStage().manifest();
			if (!contract.markCurrentStageReady(intent.generation(), firstManifest, Set.of(intent.route()),
				intent.generation())) {
				Optional<WaitReason> normalization = rollbackPreDispatchAdmission(intent.runId(), key,
					coordinator, reset, output);
				return waitSource(sourceState, goal,
					normalization.orElse(WaitReason.MATERIAL));
			}
			CreateDispatchCommand command = contract.beginDispatch(intent.intentId(),
				intent.generation()).orElse(null);
			if (command == null) {
				Optional<WaitReason> normalization = rollbackPreDispatchAdmission(intent.runId(), key,
					coordinator, reset, output);
				return waitSource(sourceState, goal,
					normalization.orElse(WaitReason.RESET_REQUIRED));
			}
			CreateLogisticsGateway.CommitResult committed = inputGateway(contract, firstManifest).commit(command);
			if (!committed.committed()) {
				WaitReason reason = switch (committed.rejection()) {
					case NATIVE_DISPATCH_UNCERTAIN, DURABLE_STORE_UNAVAILABLE,
						DURABLE_PHASE_MISMATCH, DURABLE_COMMAND_MISMATCH,
						INTENT_ID_COLLISION -> WaitReason.RESET_REQUIRED;
					case PACKAGER_UNLOADED -> WaitReason.AGENT_UNLOADED;
					case NATIVE_DISPATCH_REJECTED, SOURCE_SNAPSHOT_MISMATCH -> WaitReason.MATERIAL;
					default -> WaitReason.ROUTE;
				};
				CreateLogisticsGateway.PersistedCommit persisted = journal.inputCommitStore(
					registryAccess).load(intent.intentId()).orElse(null);
				if (reason != WaitReason.RESET_REQUIRED
					&& remoteMaterialHandoffOwnsRun(plan, intent.runId())) {
					Optional<SafeCreateRetryProof> exact = persisted == null ? Optional.empty()
						: safeRetryRecovery.authorize(intent, persisted,
							Optional.of(contract.snapshot()));
					if (exact.isEmpty()) reason = WaitReason.RESET_REQUIRED;
					else {
						safeCreateRetries.persistAndInstall(intent, persisted, exact.orElseThrow());
					}
				}
				if (reason == WaitReason.RESET_REQUIRED)
				{
					fenceReset(contract, reset, key);
					recovery.recordInput(key, contract.snapshot());
				}
				else {
					Optional<WaitReason> normalization = rollbackPreDispatchAdmission(intent.runId(), key,
						coordinator, reset, output);
					if (normalization.isPresent()) reason = normalization.orElseThrow();
				}
				return waitSource(sourceState, goal, reason);
			}
			if (!contract.confirmDispatch(committed.confirmationReceipt().orElseThrow())
				|| !contract.closeInput(intent.generation())) {
				fenceReset(contract, reset, key);
				return waitSource(sourceState, goal, WaitReason.RESET_REQUIRED);
			}
			recovery.recordInput(key, contract.snapshot());
			recovery.recordOutput(key, output.snapshot());
			if (plan.cancelAfterDrain()) {
				RecoveryLedger.CancelResult cancellation = beginPlanCancellationDrain(plan, key,
					recovery, runtime.pendingAgentRuns(), coordinator.process(), contract);
				boolean draining = cancellation == RecoveryLedger.CancelResult.CANCEL_DRAIN;
				if (cancellation == RecoveryLedger.CancelResult.ALREADY_TERMINAL) {
					RemoteAgentRecord terminal = recovery.status(key).orElse(null);
					draining = terminal != null && terminal.terminalOutcome().filter(
						RemoteAgentRecord.TerminalOutcome.CANCEL_DRAINED::equals).isPresent();
				}
				if (!draining || !reset.requireReset(key))
					return waitSource(sourceState, goal, WaitReason.RESET_REQUIRED);
				reconcileCancellationGoalFence(runtime, plan);
			}
			return true;
		}

		private Optional<WaitReason> rollbackPreDispatchAdmission(UUID runId, RemoteRequestKey key,
			AgentCoordinator coordinator, AgentResetService reset, ProductionOutputContract output) {
			AgentInputContract input = inputContracts.get(runId);
			return ProductionSafeRetryRecovery.normalizeSteps(output,
				() -> recovery.rollbackAdmission(key, runtime.pendingAgentRuns(),
					coordinator.process()),
				() -> reset.snapshot().activeKey().isEmpty()
					|| reset.abandonFutureContract(key),
				() -> {
					if (input != null) inputContracts.remove(runId, input);
					requestKeysByRun.remove(runId);
					journal.remove("input_contract/" + runId);
				},
				() -> {
					if (input != null)
						input.stopAfterIrreversibleBoundary(input.snapshot().run().fence());
					reset.requireReset(key);
				});
		}

		private void fenceReset(AgentInputContract contract, AgentResetService reset,
			RemoteRequestKey key) {
			contract.stopAfterIrreversibleBoundary(contract.snapshot().run().fence());
			reset.requireReset(key);
		}

		private boolean waitSource(ClusterEngineState sourceState, GoalRecord goal,
			WaitReason reason) {
			ClusterEngineState current = runtime.snapshot(sourceState.clusterId()).orElse(sourceState);
			runtime.waitGoalFromServerObservation(current.clusterId(), current.epochId(),
				current.generation(), goal.goalId(), reason);
			return false;
		}

		private boolean replayInputCommit(AgentInputContract contract,
			ProductionEffect.CreateDispatch intent) {
			AgentInputContract.State state = contract.snapshot();
			CreateDispatchCommand command = state.currentCommand().orElse(null);
			if (command == null || !command.intentId().equals(intent.intentId())) return false;
			CreateLogisticsGateway.PersistedCommit persisted = journal.inputCommitStore(
				registryAccess).load(intent.intentId()).orElse(null);
			return persisted != null
				&& persisted.state() == CreateLogisticsGateway.CommitAttemptState.COMPLETED
				&& persisted.result().filter(CreateLogisticsGateway.CommitResult::committed).isPresent();
		}

		private CreateLogisticsGateway inputGateway(AgentInputContract contract,
			Map<StackKey, Long> manifest) {
			return CreateLogisticsGateway.usingCreate(command -> {
				AgentInputContract.State durable = contract.snapshot();
				CreateDispatchCommand exact = durable.currentCommand().orElse(command);
				return new CreateLogisticsGateway.CommitContext(durable.agentId(),
					durable.agentResetGeneration(), durable.run().fence(),
					durable.stages().get(durable.stageIndex()).route(),
					hasLoadedPackager(command.route(), manifest),
					command.sourceInventorySnapshotGeneration(), durable.phase(), exact);
			}, server::isSameThread, journal.inputCommitStore(registryAccess), command -> {
				FrozenProductionPlan plan = planRepository.current(contract.snapshot().run().goalId());
				return plan == null || !suspendedInputDispatchClusters.remove(plan.clusterId());
			});
		}

		private boolean hasLoadedPackager(CreateRouteKey route, Map<StackKey, Long> manifest) {
			try {
				List<BigItemStack> orderStacks = new ArrayList<>();
				manifest.forEach((stack, count) -> {
					long remaining = count;
					while (remaining > 0) {
						int part = (int) Math.min(Integer.MAX_VALUE, remaining);
						orderStacks.add(new BigItemStack(stack.stack(), part)); remaining -= part;
					}
				});
				Multimap<PackagerBlockEntity, PackagingRequest> requests =
					LogisticsManager.findPackagersForRequest(route.logisticsNetworkId(),
						PackageOrderWithCrafts.simple(orderStacks), null, route.address());
				return !requests.isEmpty();
			} catch (RuntimeException unavailable) {
				return false;
			}
		}


		private Optional<OutputSettings> outputSettings(AgentFormation formation) {
			List<AgentEndpointState> outputs = formation.outputs();
			if (outputs.isEmpty()) return Optional.empty();
			AgentEndpointState first = outputs.getFirst();
			boolean coherent = outputs.stream().allMatch(output -> output.outputMode() == first.outputMode()
				&& output.completionMode() == first.completionMode()
				&& output.signalSelector().equals(first.signalSelector())
				&& output.outputPolicyGeneration() == first.outputPolicyGeneration()
				&& output.returnWarehouseRoute().equals(first.returnWarehouseRoute()));
			return coherent ? Optional.of(new OutputSettings(first.outputPolicyGeneration(),
				first.outputMode(), first.completionMode(), first.signalSelector(),
				first.returnWarehouseRoute()))
				: Optional.empty();
		}

		@Nullable
		private ProductionOutputContract ensureOutputContract(AgentCoordinator coordinator,
			ClusterEngineState sourceState, GoalRecord goal, ProductionRun run,
			OutputSettings settings) {
			ProductionOutputContract existing = outputContracts.get(run.runId());
			if (existing != null)
				return existing.snapshot().run().equals(run)
					&& existing.snapshot().agentId().equals(coordinator.formation().agentId())
					? existing : null;

			LinkedHashMap<CreateRouteKey, Optional<StackKey>> stacks = new LinkedHashMap<>();
			LinkedHashMap<CreateRouteKey, Long> baseline = new LinkedHashMap<>();
			LinkedHashMap<CreateRouteKey, ManagedOutputEndpoint> managedOutputs =
				new LinkedHashMap<>();
			LinkedHashMap<StackKey, Long> requiredCounts = new LinkedHashMap<>();
			LinkedHashSet<ObservedStock> observedStocks = new LinkedHashSet<>();
			for (AgentEndpointState endpoint : coordinator.formation().outputs()) {
				FactoryPanelBehaviour behaviour = outputBehaviour(endpoint).orElse(null);
				if (behaviour == null) return null;
				ManagedOutputEndpoint managed = managedOutputEndpoint(behaviour).orElse(null);
				if (managed == null) return null;
				ItemStack filter = behaviour.getFilter();
				Optional<StackKey> stack = filter.isEmpty() ? Optional.empty()
					: Optional.of(new StackKey(filter.copyWithCount(1)));
				long count = 0;
				if (stack.isPresent()) {
					StockObservationService.Observation observed = stockObservations.observe(
						new StockObservationScope.AttachedInventory(managed.packager(),
							managed.inventory(), stack.orElseThrow(), 0));
					if (observed.status()
						== StockObservationService.Status.WAIT_STOCK_OBSERVATION) return null;
					count = observed.actualCount();
				}
				if (stack.isPresent() && !observedStocks.add(new ObservedStock(
					endpoint.route().logisticsNetworkId(), stack.orElseThrow()))) stack = Optional.empty();
				stacks.put(endpoint.route(), stack);
				baseline.put(endpoint.route(), count);
				managedOutputs.put(endpoint.route(), managed);
				if (stack.isPresent()) {
					long target = Math.multiplyExact(Math.max(1L, behaviour.recipeOutput),
						run.runMultiplier());
					requiredCounts.merge(stack.orElseThrow(), target, Math::max);
				}
			}
			if (stacks.isEmpty()) return null;

			LinkedHashMap<StackKey, ProductionOutputPolicy.RequiredOutput> required =
				new LinkedHashMap<>();
			for (Map.Entry<StackKey, Long> entry : requiredCounts.entrySet()) {
				CreateRouteKey destination = settings.outputMode()
					== AgentEndpointState.OutputMode.DIRECT_REQUIRED
					? goal.deliveryRoute() : settings.returnWarehouseRoute();
				ProductionOutputPolicy.OutputReceiver receiver = outputReceiver(sourceState.clusterId(),
					destination, entry.getKey(), entry.getValue());
				if (receiver == null) return null;
				required.put(entry.getKey(), new ProductionOutputPolicy.RequiredOutput(destination,
					entry.getValue(), receiver));
			}

			Optional<StackKey> requested = exactRequestedPrimary(
				goalSettlement.observation(goal.goalId()).orElse(null), required.keySet());
			Optional<StackKey> primary = requested.isPresent() ? requested
				: required.keySet().stream().findFirst();
			boolean noOutput = required.isEmpty();
			ProductionOutputPolicy policy = new ProductionOutputPolicy(settings.generation(),
				settings.outputMode(), settings.completionMode(), settings.signalSelector(),
				settings.returnWarehouseRoute(),
				Set.copyOf(stacks.keySet()), required, Set.of(), primary, noOutput, false,
				config.quietWindowTicks(), config.firstOutputTimeoutTicks(),
				config.maxNoProgressRuns());
			UUID observationEpoch = UUID.randomUUID();
			OutputProbeState probe = new OutputProbeState(run.runId(), observationEpoch, 0,
				stacks, baseline, baseline, managedOutputs, Set.of());
			journal.write(OUTPUT_POLICY_PREFIX + run.runId(), new OutputPolicyState(run.runId(),
				policy), registryAccess);
			journal.write(OUTPUT_PROBE_PREFIX + run.runId(), probe, registryAccess);
			if (policy.signalSelector().isPresent()) {
				SignalProbeState signal = SignalProbeState.initial(run,
					policy.signalSelector().orElseThrow());
				journal.write(SIGNAL_PROBE_PREFIX + run.runId(), signal, registryAccess);
				signalProbes.put(run.runId(), signal);
			}
			long resetGeneration = Optional.ofNullable(resetServices.get(
				coordinator.formation().agentId())).map(service ->
					service.snapshot().agentResetGeneration())
				.orElse(coordinator.formation().agentGeneration());
			ProductionOutputContract contract = new ProductionOutputContract(coordinator.formation().agentId(),
				resetGeneration, observationEpoch, run, policy, 0,
				journal.outputContractStore(run.runId(), registryAccess));
			outputPolicies.put(run.runId(), policy);
			outputProbes.put(run.runId(), probe);
			outputContracts.put(run.runId(), contract);
			return contract;
		}

		@Nullable
		private ProductionOutputPolicy.OutputReceiver outputReceiver(UUID sourceClusterId,
			CreateRouteKey destination, StackKey stack, long producedCount) {
			AgentResolution resolution = agentDirectory.resolve(destination, sourceClusterId,
				requesterFrequency(sourceClusterId, null));
			if (resolution instanceof AgentResolution.Unique unique)
				return new ProductionOutputPolicy.OutputReceiver.Agent(unique.registration().agentId());
			if (!(resolution instanceof AgentResolution.NeverRegistered)) return null;
			StockObservationScope.NetworkSummary baselineScope =
				new StockObservationScope.NetworkSummary(destination.logisticsNetworkId(), stack, 0);
			StockObservationService.Observation observed = stockObservations.observe(baselineScope);
			if (observed.status() == StockObservationService.Status.WAIT_STOCK_OBSERVATION)
				return null;
			long target = Math.addExact(observed.actualCount(), producedCount);
			return new ProductionOutputPolicy.OutputReceiver.Bare(
				new ProductionOutputPolicy.BareReceiptProof.Network(
					new StockObservationScope.NetworkSummary(destination.logisticsNetworkId(),
						stack, target)));
		}

		private Optional<FactoryPanelBehaviour> outputBehaviour(AgentEndpointState endpoint) {
			WeakReference<FactoryPanelBlockEntity> reference = nativePanels.get(
				endpoint.endpointAddress());
			FactoryPanelBlockEntity panel = reference == null ? null : reference.get();
			if (panel == null || panel.isRemoved() || panel.getLevel() == null
				|| panel.panels == null) return Optional.empty();
			List<FactoryPanelBehaviour> exact = panel.panels.values().stream()
				.filter(FactoryPanelBehaviour::isActive)
				.filter(behaviour -> endpoint.route().logisticsNetworkId().equals(behaviour.network))
				.filter(behaviour -> behaviour.recipeAddress.isBlank()
					|| endpoint.route().address().equals(behaviour.recipeAddress)).toList();
			return exact.size() == 1 ? Optional.of(exact.getFirst()) : Optional.empty();
		}

		private Optional<ManagedOutputEndpoint> managedOutputEndpoint(
			FactoryPanelBehaviour behaviour) {
			if (!behaviour.panelBE().restocker) return Optional.empty();
			PackagerBlockEntity packager = behaviour.panelBE().getRestockedPackager();
			if (packager == null || packager.getLevel() == null
				|| packager.getLevel().getServer() != server) return Optional.empty();
			return InventoryIdentity.capture(packager, attachmentBindings).map(identity ->
				new ManagedOutputEndpoint(SpaceAddress.capture(packager.getLevel(),
					packager.getBlockPos()), identity));
		}

		private void advanceOutputContracts() {
			for (ProductionOutputContract contract : List.copyOf(outputContracts.values())) {
				try { advanceOutputContract(contract); }
				catch (RuntimeException failure) {
					LOGGER.error("Factory Agent output advancement failed for run {}",
						contract.snapshot().run().runId(), failure);
					fenceOutput(contract, WaitReason.RESET_REQUIRED);
				}
			}
		}

		private void advanceOutputContract(ProductionOutputContract contract) {
			ProductionOutputContract.Snapshot before = contract.snapshot();
			AgentCoordinator coordinator = agents.get(before.agentId());
			AgentResetService activeReset = resetServices.get(before.agentId());
			if (activeReset != null && activeReset.snapshot().agentResetGeneration()
				> before.agentResetGeneration()) return;
			OutputProbeState probe = outputProbes.get(before.run().runId());
			ProductionOutputPolicy policy = outputPolicies.get(before.run().runId());
			ClusterEngineState source = sourceState(before.run().runId()).orElse(null);
			GoalRecord goal = source == null ? null : source.goals().get(before.run().goalId());
			if (coordinator == null || probe == null || policy == null || source == null
				|| goal == null) {
				if (source != null && goal != null) waitSource(source, goal, WaitReason.AGENT_UNLOADED);
				return;
			}
			ProductionOutputCapacity capacity = outputCapacities.computeIfAbsent(before.run().runId(),
				ignored -> new ProductionOutputCapacity(source.clusterId(), before.run().runId(),
					before.run().goalId()));
			if (!prepareOutputObservation(contract, capacity,
				safeCreateRetries.containsKey(before.run().runId()))) return;
			if (contract.milestone() == ProductionOutputContract.Milestone.OBSERVING
				&& policy.signalSelector().isPresent())
				sampleOutputSignal(contract, policy);

			long sequence = probe.sequence();
			LinkedHashMap<CreateRouteKey, Long> counts = new LinkedHashMap<>();
			LinkedHashMap<CreateRouteKey, OutputLogisticsGateway.EndpointReadiness> readiness =
				new LinkedHashMap<>();
			boolean configurationChanged = false;
			for (AgentEndpointState endpoint : coordinator.formation().outputs()) {
				CreateRouteKey route = endpoint.route();
				if (!probe.stacks().containsKey(route)) continue;
				FactoryPanelBehaviour behaviour = outputBehaviour(endpoint).orElse(null);
				ManagedOutputEndpoint managed = behaviour == null ? null
					: managedOutputEndpoint(behaviour).orElse(null);
				boolean loaded = managed != null && matchesManagedOutput(probe, route,
					managed.packager(), managed.inventory());
				Optional<StackKey> frozen = probe.stacks().get(route);
				long current = probe.lastCounts().get(route);
				if (loaded && frozen.isPresent()) {
					StockObservationService.Observation observed = stockObservations.observe(
						new StockObservationScope.AttachedInventory(managed.packager(),
							managed.inventory(), frozen.orElseThrow(), 0));
					loaded = observed.status()
						!= StockObservationService.Status.WAIT_STOCK_OBSERVATION;
					if (loaded) current = observed.actualCount();
				}
				if (loaded) {
					ItemStack filter = behaviour.getFilter();
					Optional<StackKey> observed = filter.isEmpty() ? Optional.empty()
						: Optional.of(new StackKey(filter.copyWithCount(1)));
					configurationChanged |= !observed.equals(frozen);
				}
				counts.put(route, current);
				long previous = probe.lastCounts().get(route);
				if (loaded && current > previous && frozen.isPresent()) {
					sequence = Math.incrementExact(sequence);
					UUID eventId = outputEventId(before.run().runId(), route, sequence);
					contract.observeOutput(new ProductionOutputContract.OutputEvent(eventId,
						probe.observationEpoch(), route, frozen.orElseThrow(), current - previous));
				}
				readiness.put(route, new OutputLogisticsGateway.EndpointReadiness(loaded,
					probe.observationEpoch(), loaded && current <= previous));
			}
			if (!counts.keySet().equals(probe.stacks().keySet()) || configurationChanged) {
				LOGGER.error("Factory Agent output configuration changed for run {}; routes={}, frozen={}",
					before.run().runId(), counts.keySet(), probe.stacks().keySet());
				fenceOutput(contract, WaitReason.RESET_REQUIRED); return;
			}
			sequence = Math.incrementExact(sequence);
			probe = probe.sampled(sequence, counts);
			persistOutputProbe(probe);

			OutputLogisticsGateway gateway = observationGateway(contract, readiness, sequence,
				probe, counts);
			if (contract.milestone() == ProductionOutputContract.Milestone.OBSERVING) {
				OutputLogisticsGateway.ReadinessResult result = gateway.observeReadiness(
					contract.readinessRequest());
				result.snapshot().ifPresent(contract::advanceCompletionClock);
			}
			ProductionOutputContract.Snapshot state = contract.snapshot();
			if (state.milestone() == ProductionOutputContract.Milestone.OUTPUT_SEALED) {
				ProductionOutputContract.DispatchInstruction instruction = state.dispatchIntent()
					.orElseGet(() -> contract.nextDispatch().instruction().orElse(null));
				if (instruction != null) {
					ClusterEngineState current = runtime.snapshot(source.clusterId()).orElse(source);
					FrozenProductionPlan activePlan = planRepository.current(instruction.goalId());
					if (activePlan == null) {
						fenceOutput(contract, WaitReason.RESET_REQUIRED);
						return;
					}
					outputDispatch.commit(new ProductionEffect.OutputPackageRequest(instruction.intentId(),
						current.clusterId(), current.generation(), new ProductionEffect.TargetFence(
							current.epochId(), instruction.goalId(), policy.generation(),
							instruction.fence()), instruction.runId(), instruction.destination(),
						activePlan.output()));
				}
				state = contract.snapshot();
				if (state.pendingOutputs().isEmpty() && state.dispatchIntent().isEmpty()) {
					OutputProbeState latest = outputProbes.get(state.run().runId());
					OutputLogisticsGateway drainGateway = observationGateway(contract, readiness,
						sequence, latest, counts);
					drainGateway.observeDrain(contract.drainRequest()).snapshot()
						.ifPresent(contract::completeDispatch);
				}
			}
			reconcileOutputReceipts(contract, policy);
			RemoteRequestKey key = requestKeysByRun.get(before.run().runId());
			if (key != null && recovery.snapshot().records().containsKey(key))
				recovery.recordOutput(key, contract.snapshot());
			releaseOutputContract(contract, coordinator, source, goal, key);
		}

		private OutputLogisticsGateway observationGateway(ProductionOutputContract contract,
			Map<CreateRouteKey, OutputLogisticsGateway.EndpointReadiness> readiness,
			long sequence, OutputProbeState probe, Map<CreateRouteKey, Long> counts) {
			LinkedHashMap<CreateRouteKey, OutputLogisticsGateway.EndpointDrain> drain =
				new LinkedHashMap<>();
			probe.stacks().keySet().forEach(route -> drain.put(route,
				new OutputLogisticsGateway.EndpointDrain(readiness.get(route).loaded(),
					probe.observationEpoch(), readiness.get(route).loaded()
						&& counts.get(route) <= probe.baselineCounts().get(route))));
			return OutputLogisticsGateway.create(server::isSameThread,
				new OutputLogisticsGateway.Authority() {
					@Override public ProductionOutputContract.Snapshot captureContract() {
						return contract.snapshot();
					}
					@Override public OutputLogisticsGateway.ReadinessState captureReadiness(
						OutputLogisticsGateway.ReadinessRequest ignored) {
						boolean allReady = readiness.values().stream().allMatch(endpoint ->
							endpoint.loaded() && endpoint.observationEpoch().equals(probe.observationEpoch()));
						boolean allDrained = allReady && readiness.values().stream()
							.allMatch(OutputLogisticsGateway.EndpointReadiness::drained);
						return new OutputLogisticsGateway.ReadinessState(readiness, sequence,
							allReady ? 1 : 0, allDrained ? 1 : 0);
					}
					@Override public OutputLogisticsGateway.DrainState captureDrain(
						OutputLogisticsGateway.DrainRequest ignored) {
						return new OutputLogisticsGateway.DrainState(drain,
							packagerBuffersEmpty(probe));
					}
				}, ignored -> { throw new IllegalStateException("observation-only gateway"); },
				journal.outputCommitStore(registryAccess));
		}

		private Optional<PreparedOutput> prepareOutputMove(OutputProbeState probe,
			ProductionOutputContract.DispatchInstruction instruction) {
			CreateRouteKey sourceRoute = probe.stacks().entrySet().stream()
				.filter(entry -> entry.getValue().filter(instruction.stack()::equals).isPresent())
				.map(Map.Entry::getKey).findFirst().orElse(null);
			if (sourceRoute == null) return Optional.empty();
			ManagedOutputEndpoint managed = probe.managedOutputs().get(sourceRoute);
			if (managed == null || !(managed.packager().resolveBlockEntity(server)
				instanceof PackagerBlockEntity exactPackager)
				|| exactPackager.isRemoved() || exactPackager.getLevel() == null
				|| exactPackager.getLevel().getServer() != server) return Optional.empty();
			InventoryIdentity currentIdentity = InventoryIdentity.capture(exactPackager,
				attachmentBindings).orElse(null);
			if (currentIdentity == null || !matchesManagedOutput(probe, sourceRoute,
				managed.packager(), currentIdentity)) return Optional.empty();
			List<BigItemStack> stacks = new ArrayList<>();
			long remaining = instruction.count();
			while (remaining > 0) {
				int part = (int) Math.min(Integer.MAX_VALUE, remaining);
				stacks.add(new BigItemStack(instruction.stack().stack(), part));
				remaining -= part;
			}
			Multimap<PackagerBlockEntity, PackagingRequest> requests = exactPackagerRequests(
				exactPackager, sourceRoute.logisticsNetworkId(), stacks,
				instruction.destination().address(), instruction.intentId());
			long promised = requests.values().stream().mapToLong(PackagingRequest::getCount).sum();
			if (promised != instruction.count() || requests.isEmpty()
				|| exactPackager.isTooBusyFor(RequestType.PLAYER)) return Optional.empty();
			LinkedHashSet<SpaceAddress> packagers = requests.keySet().stream()
				.map(packager -> SpaceAddress.capture(packager.getLevel(), packager.getBlockPos()))
				.collect(Collectors.toCollection(LinkedHashSet::new));
			if (!probe.dispatchPackagers().isEmpty()
				&& !probe.dispatchPackagers().equals(Set.copyOf(packagers))) return Optional.empty();
			Map<Integer, LinkedHashSet<Integer>> links = new LinkedHashMap<>();
			for (PackagingRequest request : requests.values())
				links.computeIfAbsent(request.orderId(), ignored -> new LinkedHashSet<>())
					.add(request.linkIndex());
			List<NativeOrderLedger.NativeOrderIdentity> nativeOrders = links.entrySet().stream()
				.map(entry -> new NativeOrderLedger.NativeOrderIdentity(entry.getKey(),
					Set.copyOf(entry.getValue()))).toList();
			return Optional.of(new PreparedOutput(requests, packagers, nativeOrders));
		}

		private Multimap<PackagerBlockEntity, PackagingRequest> exactPackagerRequests(
			PackagerBlockEntity packager, UUID networkId, List<BigItemStack> stacks,
			String destinationAddress, UUID intentId) {
			List<PackagerLinkBlockEntity> attached = Arrays.stream(Direction.values())
				.map(direction -> packager.getBlockPos().relative(direction))
				.map(packager.getLevel()::getBlockEntity)
				.filter(PackagerLinkBlockEntity.class::isInstance)
				.map(PackagerLinkBlockEntity.class::cast)
				.filter(link -> link.getPackager() == packager)
				.filter(link -> networkId.equals(link.behaviour.freqId)).toList();
			Multimap<PackagerBlockEntity, PackagingRequest> requests = ArrayListMultimap.create();
			if (attached.size() != 1) return requests;
			PackagerLinkBlockEntity link = attached.getFirst();
			PackageOrderWithCrafts context = PackageOrderWithCrafts.simple(stacks);
			MutableBoolean finalLink = new MutableBoolean(false);
			int orderId = intentId.hashCode();
			for (int index = 0; index < stacks.size(); index++) {
				BigItemStack entry = stacks.get(index);
				Pair<PackagerBlockEntity, PackagingRequest> request = link.behaviour.processRequest(
					entry.stack, entry.count, destinationAddress, 0, finalLink, orderId, context, null);
				if (request == null || request.getSecond().getCount() != entry.count
					|| request.getFirst() != packager) return ArrayListMultimap.create();
				requests.put(request.getFirst(), request.getSecond());
				context = null;
				if (index == stacks.size() - 1) finalLink.setTrue();
			}
			return requests;
		}

		private void freezeOutputNativeReceipt(ProductionOutputContract.DispatchInstruction instruction,
			ProductionOutputPolicy policy, PreparedOutput prepared) {
			ProductionOutputPolicy.RequiredOutput required = policy.requiredOutputs().get(instruction.stack());
			if (required == null || !required.destination().equals(instruction.destination())
				|| !(required.receiver() instanceof ProductionOutputPolicy.OutputReceiver.Agent agent)) return;
			NativeOrderLedger ledger = new NativeOrderLedger(Map.of(instruction.stack(),
				instruction.count()), prepared.nativeOrders());
			OutputNativeReceiptState state = new OutputNativeReceiptState(instruction.intentId(),
				instruction.runId(), instruction.destination(), agent.agentId(),
				outputContracts.get(instruction.runId()).snapshot().outputObservationEpoch(),
				ledger.snapshot(), false);
			OutputNativeReceiptState prior = outputNativeReceipts.get(instruction.intentId());
			if (prior != null && !prior.equals(state))
				throw new IllegalStateException("output native order identity changed");
			if (prior == null) persistOutputNativeReceipt(state);
		}

		private void reconcileOutputReceipts(ProductionOutputContract contract,
			ProductionOutputPolicy policy) {
			if (contract.milestone() != ProductionOutputContract.Milestone.OUTPUT_DISPATCHED) return;
			policy.requiredOutputs().values().forEach(required -> {
				if (required.receiver() instanceof ProductionOutputPolicy.OutputReceiver.Bare bare)
					contract.reconcileStock(stockObservations.observe(bare.scope()));
			});
		}

		private void releaseOutputContract(ProductionOutputContract contract,
			AgentCoordinator coordinator, ClusterEngineState source, GoalRecord goal,
			@Nullable RemoteRequestKey key) {
			ProductionOutputContract.Snapshot state = contract.snapshot();
			FrozenProductionPlan activePlan = planRepository.current(state.run().goalId());
			if (state.milestone() != ProductionOutputContract.Milestone.OUTPUT_DISPATCHED
				&& state.milestone() != ProductionOutputContract.Milestone.OUTPUT_RECEIVED) return;
			if (key != null) {
				ProductionOutputLedger ledger = journal.read("output_ledger/" + state.run().runId(),
					ProductionOutputLedger.class, registryAccess).orElse(null);
				boolean cancellation = activePlan != null && activePlan.cancelAfterDrain();
				boolean released = ledger != null && (cancellation
					? completeCancellationDrain(key, state, ledger, coordinator, source)
					: recovery.releaseCompleted(key, state, ledger, coordinator.process()));
				if (released) {
					ensureRemoteRecoveryExchange(key, state.run().goalId());
					coordinator.reconcileResidentSlot();
					AgentResetService reset = resetServices.get(state.agentId());
					if (reset != null && !cancellation) reset.finishContract(key);
					FrozenProductionPlan plan = planRepository.current(state.run().goalId());
					if (plan != null && plan.runs().currentRun() != null
						&& plan.runs().currentRun().runId().equals(state.run().runId())) {
						if (plan.cancelAfterDrain()) {
							if (reset != null) reset.requireReset(key);
							waitSource(source, goal, WaitReason.RESET_REQUIRED);
						} else {
							FrozenProductionPlan next = plan.afterOutputSealed(state.run());
							planRepository.persist(next);
							planDispatch.forgetSubmitted(state.run().runId());
							ProductionPlanDispatch.resumeAfterSealedOutput(runtime, next);
						}
					}
				}
			}
			if (activePlan != null && (state.milestone() == ProductionOutputContract.Milestone.OUTPUT_DISPATCHED
				|| activePlan.cancelAfterDrain()))
				waitSource(source, goal, outputDeliveryWaitReason(activePlan));
		}

		private boolean completeCancellationDrain(RemoteRequestKey key,
			ProductionOutputContract.Snapshot output, ProductionOutputLedger ledger,
			AgentCoordinator coordinator, ClusterEngineState source) {
			AgentInputContract input = inputContracts.get(key.runId());
			OutputProbeState probe = outputProbes.get(key.runId());
			if (input == null || probe == null) return false;
			RecoveryLedger.CancelDrainEvidence evidence =
				new RecoveryLedger.CancelDrainEvidenceIssuer(server::isSameThread, request -> {
					if (!request.key().equals(key)
						|| !request.inputState().equals(input.snapshot())
						|| !request.outputState().equals(output)
						|| !request.outputLedger().equals(ledger)) return Optional.empty();
					boolean loaded = coordinator.formation().members().stream()
						.allMatch(AgentEndpointState::loaded);
					boolean buffersEmpty = packagerBuffersEmpty(probe);
					boolean destinationsEmpty = output.milestone()
						== ProductionOutputContract.Milestone.OUTPUT_RECEIVED;
					return Optional.of(new RecoveryLedger.ResolvedCancelDrainFacts(key,
						source.epochId(), loaded, buffersEmpty, destinationsEmpty));
				}).capture(key, input.snapshot(), output, ledger).orElse(null);
			return evidence != null && recovery.completeCancelDrain(evidence,
				coordinator.process());
		}

		private void ensureRemoteRecoveryExchange(RemoteRequestKey key, UUID goalId) {
			if (remoteRecoveryExchanges.containsKey(key)) return;
			RemoteAgentRecord terminal = recovery.status(key).filter(RemoteAgentRecord::isTerminal)
				.orElse(null);
			RemoteAgentRadioDispatch dispatch = remoteAgentDispatches.values().stream()
				.filter(candidate -> candidate.intent().runId().equals(key.runId()))
				.filter(RemoteAgentRadioDispatch::committed).findFirst().orElse(null);
			if (terminal == null || dispatch == null) return;
			RadioEndpoint source = radioEndpoints.get(dispatch.envelope().sourceHop().key());
			RadioEndpoint target = radioEndpoints.get(dispatch.envelope().targetHop().key());
			if (source == null || target == null) return;
			com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency = dispatch.requesterFrequency();
			UUID offerId = stableProtocolMessage("offer", key);
			UUID terminalId = stableProtocolMessage("terminal", key);
			UUID queryId = stableProtocolMessage("status-query", key);
			UUID ackId = stableProtocolMessage("evidence-ack", key);
			RecoveryProtocolMessage.PeerFence peerFence = new RecoveryProtocolMessage.PeerFence(
				source.hop(), target.hop());
			RadioEnvelope offerEnvelope = RadioEnvelope.create(offerId, source, target, frequency,
				new RecoveryProtocolMessage.Offer(key, peerFence, terminal.ticket()));
			RadioEnvelope terminalEnvelope = RadioEnvelope.create(terminalId, target, source, frequency,
				new RecoveryProtocolMessage.Terminal(key, peerFence, terminal));
			RadioEnvelope queryEnvelope = RadioEnvelope.create(queryId, source, target, frequency,
				new RecoveryProtocolMessage.StatusQuery(key, peerFence));
			RadioEnvelope ackEnvelope = RadioEnvelope.create(ackId, source, target, frequency,
				new RecoveryProtocolMessage.EvidenceAck(key, peerFence, terminal.revision()));
			RemoteRecoveryExchange exchange = new RemoteRecoveryExchange(key, goalId,
				terminal.revision(), offerEnvelope,
				discoverRadioRelays(source, target, frequency), terminalEnvelope,
				discoverRadioRelays(target, source, frequency), queryEnvelope,
				discoverRadioRelays(source, target, frequency), ackEnvelope,
				discoverRadioRelays(source, target, frequency),
				RemoteRecoveryPhase.OFFER_PENDING);
			persistRemoteRecoveryExchange(exchange);
		}

		private static UUID stableProtocolMessage(String kind, RemoteRequestKey key) {
			return UUID.nameUUIDFromBytes(("agent-" + kind + ":" + key)
				.getBytes(StandardCharsets.UTF_8));
		}

		private void persistRemoteRecoveryExchange(RemoteRecoveryExchange exchange) {
			journal.write("remote_recovery_exchange/" + exchange.key().requestId(), exchange,
				registryAccess);
			remoteRecoveryExchanges.put(exchange.key(), exchange);
		}

		private void fenceOutput(ProductionOutputContract contract, WaitReason reason) {
			ProductionOutputContract.Snapshot state = contract.snapshot();
			RemoteRequestKey key = requestKeysByRun.get(state.run().runId());
			AgentInputContract input = inputContracts.get(state.run().runId());
			AgentResetService reset = resetServices.get(state.agentId());
			if (key != null && reset != null) {
				if (input != null) input.stopAfterIrreversibleBoundary(state.run().fence());
				reset.requireReset(key);
			}
			sourceState(state.run().runId()).ifPresent(source -> {
				GoalRecord goal = source.goals().get(state.run().goalId());
				if (goal != null) waitSource(source, goal, reason);
			});
		}

		private Optional<ClusterEngineState> sourceState(UUID runId) {
			RemoteRequestKey key = requestKeysByRun.get(runId);
			RemoteAgentRecord record = key == null ? null : recovery.snapshot().records().get(key);
			Optional<UUID> recoverySource = Optional.ofNullable(record)
				.map(remote -> remote.ticket().sourceClusterId());
			ProductionOutputContract output = outputContracts.get(runId);
			AgentInputContract input = inputContracts.get(runId);
			ProductionRun run = output != null ? output.snapshot().run()
				: input == null ? null : input.snapshot().run();
			Optional<UUID> planSource = planRepository.sourceClusterForRun(runId, run);
			if (recoverySource.isPresent() && planSource.isPresent()
				&& !recoverySource.orElseThrow().equals(planSource.orElseThrow())) return Optional.empty();
			return recoverySource.or(() -> planSource).flatMap(runtime::snapshot);
		}

		private boolean packagerBuffersEmpty(OutputProbeState probe) {
			for (SpaceAddress address : probe.dispatchPackagers()) {
				if (!(address.resolveBlockEntity(server) instanceof PackagerBlockEntity packager)
					|| !packager.heldBox.isEmpty() || !packager.queuedExitingPackages.isEmpty())
					return false;
			}
			return true;
		}

		private void persistOutputProbe(OutputProbeState probe) {
			journal.write(OUTPUT_PROBE_PREFIX + probe.runId(), probe, registryAccess);
			outputProbes.put(probe.runId(), probe);
		}

		private void sampleOutputSignal(ProductionOutputContract contract, ProductionOutputPolicy policy) {
			ProductionRun active = contract.snapshot().run();
			AgentSignalSelector selector = policy.signalSelector().orElseThrow();
			SignalProbeState prior = signalProbes.get(active.runId());
			if (prior == null) return;
			ServerLevel level = server.getLevel(selector.source().dimension());
			boolean loaded = false;
			int strength = 0;
			if (level != null) {
				BlockPos worldPos = BlockPos.containing(selector.source().worldCenter(level));
				loaded = level.hasChunkAt(worldPos);
				if (loaded) strength = level.getSignal(worldPos, selector.side());
			}
			SignalSample sampled = prior.sample(active, selector, loaded, strength);
			SignalProbeState next = sampled.state();
			if (sampled.publish() && contract.publishSignal(active.fence()))
				next = next.markPublished();
			if (!next.equals(prior)) {
				journal.write(SIGNAL_PROBE_PREFIX + active.runId(), next, registryAccess);
				signalProbes.put(active.runId(), next);
			}
		}

		private void persistOutputNativeReceipt(OutputNativeReceiptState state) {
			journal.write("output_native_receipt/" + state.intentId(), state,
				registryAccess);
			outputNativeReceipts.put(state.intentId(), state);
		}

		private static UUID outputEventId(UUID runId, CreateRouteKey route, long sequence) {
			return UUID.nameUUIDFromBytes(("output-event:" + runId + ":"
				+ route.logisticsNetworkId() + ":" + route.address() + ":" + sequence)
				.getBytes(StandardCharsets.UTF_8));
		}

		private boolean publishProductionLibrary(UUID libraryId, UUID clusterId,
			Set<UUID> logisticsIds, long bindingRevision, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency, int priority,
			SpaceAddress address, boolean loaded, ProductionRecipeCatalog catalog) {
			requireServerThread();
			if (!acceptsWork()) return false;
			libraryId = Objects.requireNonNull(libraryId, "libraryId");
			clusterId = Objects.requireNonNull(clusterId, "clusterId");
			logisticsIds = Set.copyOf(Objects.requireNonNull(logisticsIds, "logisticsIds"));
			address = Objects.requireNonNull(address, "address");
			catalog = Objects.requireNonNull(catalog, "catalog");
			if (catalog.loaded() != loaded || !catalog.libraryId().equals(libraryId)) return false;
			DurableProductionLibrary prior = productionLibraries.get(libraryId);
			boolean same = prior != null && prior.clusterId().equals(clusterId)
				&& prior.logisticsIds().equals(logisticsIds)
				&& prior.bindingRevision() == bindingRevision && prior.frequency().equals(frequency)
				&& prior.priority() == priority && prior.address().equals(address)
				&& prior.reachability() == LibraryRegistration.Reachability.REACHABLE
				&& prior.catalog().equals(catalog);
			if (same) return true;
			if (prior != null && prior.directoryRevision() == Long.MAX_VALUE) return false;
			long directoryRevision = prior == null ? 0 : prior.directoryRevision() + 1;
			DurableProductionLibrary next = new DurableProductionLibrary(libraryId, clusterId,
				logisticsIds, bindingRevision, directoryRevision, frequency, priority, address,
				LibraryRegistration.Reachability.REACHABLE, catalog);
			journal.write(PRODUCTION_LIBRARY_PREFIX + libraryId, next, registryAccess);
			productionLibraries.put(libraryId, next);
			runtime.publishProductionLibraryAccess(clusterId, logisticsIds, directoryRevision,
				frequency, priority, address, next.reachability(), catalog);
			return true;
		}

		private Optional<UUID> endpointNetwork(FactoryPanelBlockEntity panel,
			FactoryPanelAgentAccess.Configuration configuration) {
			return endpointNetwork(panel, configuration.normalizedAddress());
		}

		private Optional<UUID> endpointNetwork(FactoryPanelBlockEntity panel, String address) {
			if (panel.panels == null) return Optional.empty();
			Set<UUID> active = panel.panels.values().stream().filter(FactoryPanelBehaviour::isActive)
				.filter(behaviour -> behaviour.recipeAddress.isBlank()
					|| behaviour.recipeAddress.equals(address))
				.map(behaviour -> behaviour.network).collect(Collectors.toCollection(HashSet::new));
			if (active.isEmpty()) active = panel.panels.values().stream()
				.filter(FactoryPanelBehaviour::isActive).map(behaviour -> behaviour.network)
				.collect(Collectors.toCollection(HashSet::new));
			return active.size() == 1 ? Optional.of(active.iterator().next()) : Optional.empty();
		}

		private void observePanelAddresses(FactoryPanelBlockEntity panel) {
			logisticsAddressObservations.unload(panel);
			if (panel.panels == null || panel.getLevel() == null || panel.isRemoved()) return;
			SpaceAddress address = SpaceAddress.capture(panel.getLevel(), panel.getBlockPos());
			AgentEndpointState endpoint = endpoints.get(address);
			if (endpoint != null)
				logisticsAddressObservations.observe(panel, Optional.empty(),
					new LogisticsNetworkAssociation(endpoint.route().logisticsNetworkId(),
						AssociationKind.AGENT_ENDPOINT));
		}

		private void refreshNativePanels() {
			for (Map.Entry<SpaceAddress, WeakReference<FactoryPanelBlockEntity>> entry :
				List.copyOf(nativePanels.entrySet())) {
				FactoryPanelBlockEntity panel = entry.getValue().get();
				if (panel == null || panel.isRemoved() || panel.getLevel() == null) {
					nativePanels.remove(entry.getKey());
					AgentEndpointState removed = endpoints.remove(entry.getKey());
					if (removed != null) {
						panelDirectoryGeneration = Math.incrementExact(panelDirectoryGeneration);
						rebuildAgent(removed.agentId());
					}
				}
			}
		}

		private void rebuildAgent(UUID agentId) {
			List<AgentEndpointState> members = endpoints.values().stream()
				.filter(endpoint -> endpoint.agentId().equals(agentId))
				.sorted(Comparator.comparing(AgentEndpointState::endpointAddress,
					AgentFormation.ADDRESS_ORDER)).toList();
			AgentCoordinator prior = agents.get(agentId);
			if (members.stream().map(AgentEndpointState::agentGeneration).distinct().count() > 1) {
				if (prior != null) {
					prior.withdrawInputRegistrations(agentDirectory);
					prior.process().close();
					agents.remove(agentId);
				}
				resetServices.remove(agentId);
				return;
			}
			boolean formable = members.stream().anyMatch(endpoint ->
				endpoint.role() == com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole.INPUT)
				&& members.stream().anyMatch(endpoint ->
					endpoint.role() == com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole.OUTPUT);
			if (!formable) {
				if (prior != null) {
					prior.withdrawInputRegistrations(agentDirectory);
					prior.process().close(); agents.remove(agentId);
				}
				resetServices.remove(agentId);
				return;
			}
			AgentFormation formation = AgentFormation.form(members);
			Optional<ClusterCommunicationConfiguration> declaration = communicationDirectory
				.configuration(formation.ownerClusterId());
			if (declaration.isEmpty() || declaration.orElseThrow().role() != ClusterServiceRole.AGENT) {
				if (prior != null) { prior.withdrawInputRegistrations(agentDirectory); prior.process().close(); }
				agents.remove(agentId); resetServices.remove(agentId); return;
			}
			AgentCoordinator coordinator = prior;
			if (coordinator == null) {
				coordinator = AgentCoordinator.formOrRestore(formation,
					slotAllocator(formation.agentId(), formation.ownerClusterId()),
					journal.agentCoordinatorStore(registryAccess),
					config.recoveryLedgerCapacity());
				agents.put(agentId, coordinator);
			} else if (!coordinator.formation().equals(formation)) {
				coordinator.refresh(formation);
			}
			if (!coordinator.process().available()) coordinator.recover(formation);
			coordinator.publishInputRegistrations(agentDirectory, declaration.orElseThrow());
			resetServices.computeIfAbsent(agentId, ignored -> new AgentResetService(config,
				agentId, formation.ownerClusterId(), formation.agentGeneration(), null, false,
				journal.resetStore(registryAccess)));
			reconcileRecoveredInputs(coordinator);
			reconcileRecoveredOutputs(coordinator);
		}

		private AgentProcess.SlotAllocator slotAllocator(UUID agentId, UUID clusterId) {
			return new AgentProcess.SlotAllocator() {
				@Override public Optional<AgentProcess.ResidentSlot> acquire(UUID requestedAgent,
					UUID requestedCluster,
					com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind kind) {
					if (!agentId.equals(requestedAgent) || !clusterId.equals(requestedCluster)
						|| kind != com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind.AGENT)
						return Optional.empty();
					return runtime.acquireAgentResidentSlot(agentId, clusterId);
				}
				@Override public boolean stillValid(AgentProcess.ResidentSlot slot) {
					return runtime.agentResidentSlotValid(agentId, clusterId, slot);
				}
				@Override public void release(AgentProcess.ResidentSlot slot) {
					runtime.releaseAgentResidentSlot(agentId, clusterId, slot);
				}
			};
		}

		private void reconcileRecoveredInputs(AgentCoordinator coordinator) {
			for (AgentInputContract contract : inputContracts.values()) {
				AgentInputContract.State state = contract.snapshot();
				if (!state.agentId().equals(coordinator.formation().agentId())) continue;
				RemoteRequestKey key = recovery.snapshot().records().keySet().stream()
					.filter(candidate -> candidate.runId().equals(state.run().runId()))
					.findFirst().orElse(null);
				if (key == null) continue;
				requestKeysByRun.put(state.run().runId(), key);
				CreateRouteKey route = state.stages().get(state.stageIndex()).route();
				ClusterEngineState source = sourceState(state.run().runId()).orElse(null);
				if (source == null) continue;
				AgentResolution resolution = agentDirectory.resolve(route, source.clusterId(),
					requesterFrequency(source.clusterId(), null));
				if (resolution instanceof AgentResolution.Unique unique) {
					recovery.reconcileResources(key, runtime.pendingAgentRuns(),
						unique.registration(), coordinator.process());
					if (state.phase() == AgentInputPhase.DISPATCH_INTENT)
						reconcileDispatchIntent(contract, coordinator, key);
				}
			}
		}

		private void reconcileRecoveredOutputs(AgentCoordinator coordinator) {
			for (ProductionOutputContract contract : outputContracts.values()) {
				ProductionOutputContract.Snapshot state = contract.snapshot();
				if (!state.agentId().equals(coordinator.formation().agentId())) continue;
				ClusterEngineState source = sourceState(state.run().runId()).orElse(null);
				if (source == null || !source.goals().containsKey(state.run().goalId())) continue;
				ProductionOutputCapacity capacity = outputCapacities.computeIfAbsent(
					state.run().runId(), ignored -> new ProductionOutputCapacity(source.clusterId(),
						state.run().runId(), state.run().goalId()));
				if (!prepareOutputObservation(contract, capacity,
					safeCreateRetries.containsKey(state.run().runId()))) continue;
				AgentInputContract input = inputContracts.get(state.run().runId());
				if (input != null && input.phase() == AgentInputPhase.INPUT_ENDED)
					contract.markInputEnded(state.run().fence());
			}
		}

		private void reconcileDispatchIntent(AgentInputContract contract,
			AgentCoordinator coordinator, RemoteRequestKey key) {
			AgentInputContract.State state = contract.snapshot();
			CreateDispatchCommand command = state.currentCommand().orElseThrow();
			Map<StackKey, Long> manifest = state.stages().get(state.stageIndex()).manifest();
			CreateLogisticsGateway.PersistedCommit persisted = journal.inputCommitStore(
				registryAccess).load(command.intentId()).orElse(null);
			if (persisted != null && persisted.allowsRetryWithoutMaterial()) {
				ClusterEngineState source = sourceState(state.run().runId()).orElse(null);
				ProductionEffect candidate = source == null ? null
					: source.pendingIntents().get(command.intentId());
				if (candidate instanceof ProductionEffect.CreateDispatch intent) {
					Optional<SafeCreateRetryProof> repaired = safeRetryRecovery.authorize(intent, persisted,
						Optional.of(state));
					if (repaired.isPresent()) {
						safeCreateRetries.persistAndInstall(intent, persisted, repaired.orElseThrow());
						return;
					}
				}
				AgentResetService reset = resetServices.get(state.agentId());
				if (reset != null) fenceReset(contract, reset, key);
				GoalRecord goal = source == null ? null : source.goals().get(state.run().goalId());
				if (goal != null) waitSource(source, goal, WaitReason.RESET_REQUIRED);
				return;
			}
			CreateLogisticsGateway.CommitResult result = inputGateway(contract, manifest).commit(command);
			if (result.committed() && contract.confirmDispatch(
				result.confirmationReceipt().orElseThrow()) && contract.closeInput(state.run().fence())) {
				recovery.recordInput(key, contract.snapshot());
				return;
			}
			AgentResetService reset = resetServices.get(state.agentId());
			if (reset != null) fenceReset(contract, reset, key);
			ClusterEngineState source = sourceState(state.run().runId()).orElse(null);
			GoalRecord goal = source == null ? null : source.goals().get(state.run().goalId());
			if (goal != null) waitSource(source, goal, WaitReason.RESET_REQUIRED);
		}

		@Override public Optional<CreateRouteKey> managedInputRoute(PackagerBlockEntity packager,
			String exactAddress) {
			requireServerThread();
			if (!acceptsWork()) return Optional.empty();
			if (packager.getLevel() == null || packager.getLevel().getServer() != server)
				return Optional.empty();
			Set<CreateRouteKey> activeRoutes = inputContracts.values().stream()
				.filter(this::acceptsNativeInput)
				.map(AgentInputContract::currentStage).map(AgentInputContract.StageInput::route)
				.collect(Collectors.toSet());
			return resolveManagedInputRoute(exactAddress, activeRoutes,
				Set.copyOf(Objects.requireNonNull(attachedNetworks.apply(packager),
					"attached packager networks")));
		}

		@Override public void publish(NativePackageObservation observation) {
			requireServerThread();
			if (!acceptsWork()) return;
			publishAgentOutputReceipts(observation);
			AgentInputContract contract = inputContracts.values().stream()
				.filter(candidate -> candidate.currentStage().route().equals(observation.route()))
				.filter(this::acceptsNativeInput)
				.findFirst().orElse(null);
			if (contract == null) return;
			contract.observe(observation);
			if (contract.phase() == AgentInputPhase.INPUT_CLOSED
				&& contract.endInput(contract.snapshot().run().fence())) {
				if (contract.advanceStage().isPresent()) dispatchNextStage(contract);
				else {
					ProductionOutputContract output = outputContracts.get(contract.snapshot().run().runId());
					if (output != null) output.markInputEnded(contract.snapshot().run().fence());
				}
			}
			RemoteRequestKey key = requestKeysByRun.get(contract.snapshot().run().runId());
			if (key != null) {
				recovery.recordInput(key, contract.snapshot());
				ProductionOutputContract output = outputContracts.get(contract.snapshot().run().runId());
				if (output != null) recovery.recordOutput(key, output.snapshot());
			}
		}

		private void dispatchNextStage(AgentInputContract contract) {
			AgentInputContract.State state = contract.snapshot();
			AgentInputContract.StageInput stage = contract.currentStage();
			long fence = state.run().fence();
			RemoteRequestKey key = requestKeysByRun.get(state.run().runId());
			AgentResetService reset = resetServices.get(state.agentId());
			if (!contract.markCurrentStageReady(fence, stage.manifest(), Set.of(stage.route()), fence)) {
				if (key != null && reset != null) fenceReset(contract, reset, key);
				return;
			}
			UUID intentId = UUID.nameUUIDFromBytes(("sequenced-input:" + state.run().runId() + ":"
				+ stage.index()).getBytes(StandardCharsets.UTF_8));
			CreateDispatchCommand command = contract.beginDispatch(intentId, fence).orElse(null);
			if (command == null) {
				if (key != null && reset != null) fenceReset(contract, reset, key);
				return;
			}
			CreateLogisticsGateway.CommitResult result = inputGateway(contract, stage.manifest())
				.commit(command);
			if (!result.committed() || !contract.confirmDispatch(
				result.confirmationReceipt().orElseThrow()) || !contract.closeInput(fence)) {
				if (key != null && reset != null) fenceReset(contract, reset, key);
			}
		}

		private boolean acceptsNativeInput(AgentInputContract contract) {
			return switch (contract.phase()) {
				case DISPATCH_CONFIRMED, INPUT_CLOSED, INPUT_STALLED, RESET_REQUIRED -> true;
				case QUEUED, READY, DISPATCH_INTENT, INPUT_ENDED -> false;
			};
		}

		private void publishAgentOutputReceipts(NativePackageObservation observation) {
			for (OutputNativeReceiptState receipt : List.copyOf(outputNativeReceipts.values())) {
				if (receipt.ackPublished() || !receipt.destination().equals(observation.route())) continue;
				OutputNativeReceiptState observed = observeOutputReceipt(receipt, observation);
				if (!observed.equals(receipt)) {
					receipt = observed;
					persistOutputNativeReceipt(receipt);
				}
				if (!outputReceiptComplete(receipt)) continue;
				UUID receiptRunId = receipt.runId();
				CreateRouteKey receiptDestination = receipt.destination();
				UUID receiverAgentId = receipt.receiverAgentId();
				ProductionOutputContract contract = outputContracts.get(receiptRunId);
				ProductionOutputPolicy policy = outputPolicies.get(receiptRunId);
				if (contract == null || policy == null || outputNativeReceipts.values().stream()
					.anyMatch(other -> other.runId().equals(receiptRunId)
						&& other.destination().equals(receiptDestination) && other.ackPublished())) continue;
				LinkedHashMap<StackKey, Long> accepted = new LinkedHashMap<>();
				for (OutputNativeReceiptState candidate : outputNativeReceipts.values()) {
					if (!candidate.runId().equals(receiptRunId)
						|| !candidate.destination().equals(receiptDestination)) continue;
					NativeOrderLedger candidateLedger = NativeOrderLedger.restore(candidate.nativeOrders());
					if (!candidateLedger.complete()) continue;
					candidate.nativeOrders().expectedManifest().forEach((stack, count) ->
						accepted.merge(stack, count, Math::addExact));
				}
				boolean targetReached = policy.requiredOutputs().entrySet().stream()
					.filter(entry -> entry.getValue().destination().equals(receiptDestination))
					.filter(entry -> entry.getValue().receiver()
						instanceof ProductionOutputPolicy.OutputReceiver.Agent agent
							&& agent.agentId().equals(receiverAgentId))
					.allMatch(entry -> accepted.getOrDefault(entry.getKey(), 0L)
						>= entry.getValue().targetCount());
				if (!targetReached) continue;
				ProductionOutputContract.Snapshot state = contract.snapshot();
				if (contract.publishAgentReceipt(new ProductionOutputContract.AgentReceiptAck(
					receiverAgentId, receiptDestination, receiptRunId,
					state.run().fence(), receipt.observationEpoch())))
					persistOutputNativeReceipt(receipt.acknowledged());
			}
		}

		@Override public void recordRecoveryRequired(
			NativePackageObservation.RecoveryContext context,
			@Nullable NativePackageObservation observation, Throwable failure) {
			requireServerThread();
			if (!acceptsWork()) return;
			RecoveryFailureState state = new RecoveryFailureState(context.route(), context.address(),
				context.packagerPosition(), observation == null ? Optional.empty()
					: Optional.of(observation), failure.getClass().getName() + ": "
						+ Objects.toString(failure.getMessage(), ""));
			journal.write(NATIVE_FAILURE_PREFIX + UUID.randomUUID(), state,
				registryAccess);
		}



		@Nullable private static UUID selectedAgentId(@Nullable SelectedProviderDescriptor provider) {
			if (provider == null || provider.providerKind() != RouteCandidate.ProviderKind.AGENT
				|| !provider.providerKey().startsWith("agent/")) return null;
			int end = provider.providerKey().indexOf('/', "agent/".length());
			if (end < 0) return null;
			try { return UUID.fromString(provider.providerKey().substring("agent/".length(), end)); }
			catch (IllegalArgumentException invalid) { return null; }
		}

		private static OptionalLong selectedAgentGeneration(
			@Nullable SelectedProviderDescriptor provider) {
			if (provider == null || provider.providerKind() != RouteCandidate.ProviderKind.AGENT
				|| !provider.providerKey().startsWith("agent/")) return OptionalLong.empty();
			int idEnd = provider.providerKey().indexOf('/', "agent/".length());
			if (idEnd < 0) return OptionalLong.empty();
			int generationEnd = provider.providerKey().indexOf('/', idEnd + 1);
			if (generationEnd < 0) return OptionalLong.empty();
			try {
				long generation = Long.parseLong(provider.providerKey().substring(idEnd + 1,
					generationEnd));
				return generation < 0 ? OptionalLong.empty() : OptionalLong.of(generation);
			} catch (NumberFormatException invalid) {
				return OptionalLong.empty();
			}
		}


		private boolean rejectPanelSubmission(UUID clusterId, String diagnostic) {
			lastPanelSubmissionDiagnostics.put(Objects.requireNonNull(clusterId),
				Objects.requireNonNull(diagnostic));
			return false;
		}

		/** Resolve a loaded Create restocker address to its exact attachment; otherwise use network stock. */
		private Optional<StockObservationScope> goalObservationScope(UUID network,
			String deliveryAddress, StackKey item) {
			LinkedHashSet<ManagedOutputEndpoint> attached = new LinkedHashSet<>();
			for (WeakReference<FactoryPanelBlockEntity> reference : nativePanels.values()) {
				FactoryPanelBlockEntity panel = reference.get();
				if (panel == null || panel.isRemoved() || !panel.restocker || panel.panels == null)
					continue;
				for (FactoryPanelBehaviour behaviour : panel.panels.values()) {
					if (!behaviour.isActive() || !network.equals(behaviour.network)
						|| !deliveryAddress.equals(behaviour.recipeAddress)) continue;
					ManagedOutputEndpoint managed = managedOutputEndpoint(behaviour).orElse(null);
					if (managed == null) return Optional.empty();
					attached.add(managed);
				}
			}
			if (attached.size() > 1) return Optional.empty();
			if (attached.size() == 1) {
				ManagedOutputEndpoint managed = attached.getFirst();
				return Optional.of(new StockObservationScope.AttachedInventory(managed.packager(),
					managed.inventory(), item, 0));
			}
			return Optional.of(new StockObservationScope.NetworkSummary(network, item, 0));
		}

		private Map<StackKey, Long> availableManifest(PatternRecord pattern, UUID network) {
			ArrayList<com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient>
				ingredients = new ArrayList<>(pattern.inputs());
			if (pattern.body() instanceof SequencedPatternBody sequenced)
				sequenced.stages().forEach(stage -> ingredients.addAll(stage.inputs()));
			InventorySummary summary = LogisticsManager.getSummaryOfNetwork(network, false);
			LinkedHashMap<StackKey, Long> available = new LinkedHashMap<>();
			for (var ingredient : ingredients) {
				List<BigItemStack> candidates;
				if (ingredient.itemId() != null) {
					Item item = BuiltInRegistries.ITEM.get(ingredient.itemId());
					candidates = item == null || item == Items.AIR ? List.of()
						: summary.getItemMap().getOrDefault(item, List.of());
				} else candidates = summary.getStacks();
				for (BigItemStack candidate : candidates) {
					if (candidate.count <= 0 || !ingredient.matches(candidate.stack)) continue;
					StackKey exact = new StackKey(candidate.stack);
					available.merge(exact, (long) candidate.count, Math::max);
					if (available.size() > config.pendingRunCapacity())
						throw new IllegalStateException(
							"exact available manifest exceeds configured bound");
				}
			}
			return Map.copyOf(available);
		}

		/** Commits a force reset only after the local endpoint handler has proven player proximity. */
		private List<AgentDiagnostic> agentDiagnostics(UUID clusterId) {
			return agents.values().stream().filter(coordinator -> coordinator.formation()
				.ownerClusterId().equals(clusterId)).sorted(Comparator.comparing(coordinator ->
					coordinator.formation().agentId())).map(coordinator -> {
				AgentFormation formation = coordinator.formation();
				AgentResetService reset = resetServices.get(formation.agentId());
				return new AgentDiagnostic(formation.agentId(), coordinator.process().residentSlot()
					.map(AgentProcess.ResidentSlot::processId).orElse(null), reset == null ? null
						: reset.snapshot().activeKey().map(RemoteRequestKey::runId).orElse(null),
					reset == null ? formation.agentGeneration()
						: reset.snapshot().agentResetGeneration(),
					reset == null ? 0 : reset.snapshot().revision());
			}).toList();
		}

		private List<GoalDiagnostic> goalDiagnostics(UUID clusterId) {
			ClusterEngineState state = runtime.snapshotForPanelView(clusterId).orElse(null);
			if (state == null) return List.of();
			return state.goals().values().stream().sorted(Comparator.comparingLong(goal ->
				state.submissionSequences().get(goal.goalId()))).map(goal -> {
				GoalObservationState evidence = goalSettlement.observation(goal.goalId()).orElse(null);
				long requested = evidence == null ? goal.submission().multiplier()
					: evidence.targetCount();
				long received = evidence == null ? 0 : evidence.creditedCount();
				SelectedProviderDescriptor selected = Optional.ofNullable(planRepository.current(goal.goalId()))
					.map(FrozenProductionPlan::selectedProvider).orElseGet(() ->
						productionAdmissions.values().stream().filter(admission -> admission.goalId()
							.equals(goal.goalId())).flatMap(admission -> admission.selectedProvider().stream())
							.findFirst().orElse(null));
				ProviderDiagnostic provider = selected == null
					? new ProviderDiagnostic(null, null, "", 0)
					: new ProviderDiagnostic(selected.libraryId(), selected.providerKind(),
						selected.providerKey(), selected.effectivePriority());
				List<ProcessDiagnostic> processes = state.processes().values().stream()
					.filter(process -> process.goalId().equals(goal.goalId()))
					.sorted(Comparator.comparing(ProcessRecord::processId))
					.map(process -> new ProcessDiagnostic(process.processId(), process.kind(), process.state(),
						process.waitReason())).toList();
				return new GoalDiagnostic(goal.goalId(), requested, received, requested - received,
					goal.state(), goal.waitReason(), provider, processes);
			}).toList();
		}




		private static String stableEndpoint(SpaceAddress address) {
			return address.dimension().location() + "/"
				+ (address.subLevelId() == null ? "root" : address.subLevelId()) + "/"
				+ address.localPos().getX() + "," + address.localPos().getY() + ","
				+ address.localPos().getZ();
		}

		private Optional<Item> item(String key) {
			ResourceLocation id = ResourceLocation.tryParse(key);
			if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return Optional.empty();
			Item item = BuiltInRegistries.ITEM.get(id);
			return item == Items.AIR ? Optional.empty() : Optional.of(item);
		}

		private void requireServerThread() {
			if (!server.isSameThread())
				throw new IllegalStateException("factory capture left server thread");
		}

		private final class ProductionOutputCapacity
			implements ProductionOutputContract.OutputProcessCapacity {
			private final UUID clusterId;
			private final UUID runId;
			private final UUID goalId;

			private ProductionOutputCapacity(UUID clusterId, UUID runId, UUID goalId) {
				this.clusterId = Objects.requireNonNull(clusterId, "clusterId");
				this.runId = Objects.requireNonNull(runId, "runId");
				this.goalId = Objects.requireNonNull(goalId, "goalId");
			}

			@Override public Optional<ProductionOutputContract.ProcessLease> tryAcquireObservation(
				UUID requestedRun, UUID requestedGoal, CreateRouteKey outputAddress) {
				return acquire(requestedRun, requestedGoal, outputAddress,
					ProductionOutputContract.LeasePurpose.QUIET_OBSERVATION);
			}

			@Override public Optional<ProductionOutputContract.ProcessLease> tryAcquireOutputDestination(
				UUID requestedRun, UUID requestedGoal, CreateRouteKey destination) {
				return acquire(requestedRun, requestedGoal, destination,
					ProductionOutputContract.LeasePurpose.OUTPUT_DESTINATION);
			}

			private Optional<ProductionOutputContract.ProcessLease> acquire(UUID requestedRun,
				UUID requestedGoal, CreateRouteKey address,
				ProductionOutputContract.LeasePurpose purpose) {
				if (!runId.equals(requestedRun) || !goalId.equals(requestedGoal))
					return Optional.empty();
				return runtime.acquireOutputProcess(clusterId, runId, goalId, address, purpose);
			}

			@Override public boolean isLeaseActive(ProductionOutputContract.ProcessLease lease,
				ProductionOutputContract.LeasePurpose purpose, UUID requestedRun, UUID requestedGoal) {
				return runId.equals(requestedRun) && goalId.equals(requestedGoal)
					&& runtime.outputProcessValid(clusterId, runId, goalId, lease, purpose);
			}

			@Override public void release(ProductionOutputContract.ProcessLease lease) {
				for (ProductionOutputContract.LeasePurpose purpose :
					ProductionOutputContract.LeasePurpose.values())
					if (runtime.releaseOutputProcess(clusterId, runId, goalId, lease, purpose)) return;
			}
		}

		private record OutputSettings(long generation, AgentEndpointState.OutputMode outputMode,
			AgentCompletionMode completionMode, Optional<AgentSignalSelector> signalSelector,
			CreateRouteKey returnWarehouseRoute) {}
		private record ObservedStock(UUID network, StackKey stack) {}
		private record PreparedOutput(Multimap<PackagerBlockEntity, PackagingRequest> requests,
			Set<SpaceAddress> packagers,
			List<NativeOrderLedger.NativeOrderIdentity> nativeOrders) {}

		@Override public void close() {
			if (closed) return;
			lifecycle.markStopped();
			closed = true;
			for (AgentCoordinator coordinator : agents.values()) {
				coordinator.withdrawInputRegistrations(agentDirectory);
				coordinator.process().close();
			}
			agents.clear(); endpoints.clear(); nativePanels.clear(); outputCapacities.clear();
			deactivateExternalBindings();
		}
	}

	public record RecoveryFailureState(Optional<CreateRouteKey> route, Optional<String> address,
		Optional<net.minecraft.core.BlockPos> packagerPosition,
		Optional<NativePackageObservation> observation, String detail) {
		public RecoveryFailureState {
			route = Objects.requireNonNull(route, "route");
			address = Objects.requireNonNull(address, "address");
			packagerPosition = Objects.requireNonNull(packagerPosition, "packagerPosition");
			observation = Objects.requireNonNull(observation, "observation");
			detail = Objects.requireNonNull(detail, "detail");
			if (detail.isBlank() || detail.length() > WorldFact.MAX_TEXT_LENGTH)
				throw new IllegalArgumentException("Invalid native observation recovery detail");
		}
	}
}
