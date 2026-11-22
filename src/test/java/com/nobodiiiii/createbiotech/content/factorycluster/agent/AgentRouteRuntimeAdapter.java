package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;

/** Server-thread Agent collector for Task 5's scoped, runtime-owned route table. */
public final class AgentRouteRuntimeAdapter {
	private final ScopedCollector collector;

	public AgentRouteRuntimeAdapter(ScopedCollector collector) {
		this.collector = Objects.requireNonNull(collector, "collector");
	}

	public AgentRouteRuntimeAdapter(FactoryRuntimeService runtime, UUID requesterClusterId) {
		this(new RuntimeCollector(runtime, requesterClusterId));
	}

	public boolean publish(RouteSelectionScope scope, UUID libraryId, UUID patternId,
		CreateRouteKey targetRoute, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency requesterFrequency,
		AgentAddressDirectory directory, List<AgentCoordinator> loadedAgents) {
		List<ProductionRouteTable.ProviderSource> feed = collect(scope, libraryId, patternId,
			targetRoute, requesterFrequency, directory, loadedAgents);
		if (!collector.replace(scope, feed)) return false;
		return collector.complete(scope);
	}

	/** Returns the real bounded Agent snapshot without completing/replacing a shared collector. */
	public List<ProductionRouteTable.ProviderSource> collect(RouteSelectionScope scope,
		UUID libraryId, UUID patternId, CreateRouteKey targetRoute, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency requesterFrequency,
		AgentAddressDirectory directory, List<AgentCoordinator> loadedAgents) {
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(libraryId, "libraryId");
		Objects.requireNonNull(patternId, "patternId");
		Objects.requireNonNull(targetRoute, "targetRoute");
		Objects.requireNonNull(directory, "directory");
		loadedAgents = List.copyOf(Objects.requireNonNull(loadedAgents, "loadedAgents"));

		return switch (
			directory.resolve(targetRoute, collector.clusterId(), requesterFrequency)) {
			case AgentResolution.Unique unique -> sourceForUnique(scope, libraryId, patternId,
				targetRoute, unique.registration(), loadedAgents);
			case AgentResolution.Conflict ignored -> List.of(blocked(scope, libraryId, patternId,
				"agent-conflict/" + stableRoute(targetRoute), RouteCandidate.Blocker.AGENT_CONFLICT));
			case AgentResolution.KnownUnavailable ignored -> List.of(blocked(scope, libraryId,
				patternId, "agent-unavailable/" + stableRoute(targetRoute),
				RouteCandidate.Blocker.UNREACHABLE_KNOWN_ROUTE));
			case AgentResolution.NeverRegistered ignored -> List.of();
		};
	}

	private static List<ProductionRouteTable.ProviderSource> sourceForUnique(
		RouteSelectionScope scope, UUID libraryId, UUID patternId, CreateRouteKey targetRoute,
		AgentAddressRegistration registration, List<AgentCoordinator> loadedAgents) {
		AgentCoordinator coordinator = loadedAgents.stream()
			.filter(candidate -> candidate.formation().agentId().equals(registration.agentId())
				&& candidate.formation().ownerClusterId().equals(registration.ownerClusterId())
				&& candidate.ownsRegistration(registration))
			.findFirst().orElse(null);
		if (coordinator == null)
			return List.of(blocked(scope, libraryId, patternId,
				"agent-unavailable/" + stableRoute(targetRoute),
				RouteCandidate.Blocker.UNREACHABLE_KNOWN_ROUTE));
		AgentEndpointState endpoint = coordinator.formation().inputs().stream()
			.filter(input -> input.route().equals(targetRoute)).findFirst().orElse(null);
		if (endpoint == null)
			return List.of(blocked(scope, libraryId, patternId,
				"agent-unavailable/" + stableRoute(targetRoute),
				RouteCandidate.Blocker.UNREACHABLE_KNOWN_ROUTE));
		RouteCandidate.Availability availability = !coordinator.process().available()
			? RouteCandidate.Availability.UNAVAILABLE
			: coordinator.process().activeContract().isPresent()
				? RouteCandidate.Availability.BUSY : RouteCandidate.Availability.AVAILABLE;
		RouteCandidate.Blocker blocker = availability == RouteCandidate.Availability.UNAVAILABLE
			? RouteCandidate.Blocker.UNREACHABLE_KNOWN_ROUTE : RouteCandidate.Blocker.NONE;
		return List.of(new ProductionRouteTable.ProviderSource(scope, libraryId, patternId,
			"agent/" + registration.agentId() + "/" + registration.agentGeneration()
				+ "/" + stableRoute(targetRoute),
			RouteCandidate.ProviderKind.AGENT, endpoint.routePriority(), availability, blocker));
	}

	private static ProductionRouteTable.ProviderSource blocked(RouteSelectionScope scope,
		UUID libraryId, UUID patternId, String providerKey, RouteCandidate.Blocker blocker) {
		return new ProductionRouteTable.ProviderSource(scope, libraryId, patternId, providerKey,
			RouteCandidate.ProviderKind.AGENT, 0, RouteCandidate.Availability.UNAVAILABLE, blocker);
	}

	private static String stableRoute(CreateRouteKey route) {
		return route.logisticsNetworkId() + "/" + route.address();
	}

	public interface ScopedCollector {
		UUID clusterId();
		boolean replace(RouteSelectionScope scope,
			List<ProductionRouteTable.ProviderSource> sources);
		boolean complete(RouteSelectionScope scope);
	}

	private record RuntimeCollector(FactoryRuntimeService runtime, UUID clusterId)
		implements ScopedCollector {
		private RuntimeCollector {
			runtime = Objects.requireNonNull(runtime, "runtime");
			clusterId = Objects.requireNonNull(clusterId, "clusterId");
		}
		@Override public UUID clusterId() { return clusterId; }
		@Override public boolean replace(RouteSelectionScope scope,
			List<ProductionRouteTable.ProviderSource> sources) {
			return runtime.replaceRouteSources(clusterId, scope, RouteCandidate.ProviderKind.AGENT,
				sources);
		}
		@Override public boolean complete(RouteSelectionScope scope) {
			return runtime.completeRouteCollector(clusterId, scope,
				RouteCandidate.ProviderKind.AGENT);
		}
	}
}
