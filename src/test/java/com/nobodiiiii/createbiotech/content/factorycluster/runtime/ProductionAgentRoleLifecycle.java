package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCoordinator;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResetService;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;

/** Owns the live state boundary created and destroyed by a Cluster AGENT declaration. */
public final class ProductionAgentRoleLifecycle {
	private final List<AgentEndpointState> endpoints;
	private final Map<UUID, AgentCoordinator> agents;
	private final Map<UUID, AgentResetService> resetServices;
	private final AgentAddressDirectory directory;
	private final Consumer<UUID> rebuilder;

	public ProductionAgentRoleLifecycle(List<AgentEndpointState> endpoints,
		Map<UUID, AgentCoordinator> agents, Map<UUID, AgentResetService> resetServices,
		AgentAddressDirectory directory, Consumer<UUID> rebuilder) {
		this.endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
		this.agents = Objects.requireNonNull(agents, "agents");
		this.resetServices = Objects.requireNonNull(resetServices, "resetServices");
		this.directory = Objects.requireNonNull(directory, "directory");
		this.rebuilder = Objects.requireNonNull(rebuilder, "rebuilder");
	}

	public void configurationChanged(ClusterCommunicationConfiguration previous,
		ClusterCommunicationConfiguration next) {
		ClusterCommunicationConfiguration exactPrevious = Objects.requireNonNull(previous,
			"previous");
		ClusterCommunicationConfiguration exactNext = Objects.requireNonNull(next, "next");
		if (!exactPrevious.clusterId().equals(exactNext.clusterId()))
			throw new IllegalArgumentException("communication change crossed Clusters");
		if (exactPrevious.role() == exactNext.role()) return;
		if (exactPrevious.role() == ClusterServiceRole.AGENT) teardown(exactNext.clusterId());
		if (exactNext.role() == ClusterServiceRole.AGENT)
			endpoints.stream().filter(endpoint -> endpoint.loaded()
				&& endpoint.ownerClusterId().equals(exactNext.clusterId()))
				.map(AgentEndpointState::agentId).distinct().forEach(rebuilder);
	}

	private void teardown(UUID clusterId) {
		List<UUID> ownedAgents = agents.entrySet().stream()
			.filter(entry -> entry.getValue().formation().ownerClusterId().equals(clusterId))
			.map(Map.Entry::getKey).toList();
		for (UUID agentId : ownedAgents) {
			AgentCoordinator coordinator = agents.remove(agentId);
			coordinator.withdrawInputRegistrations(directory);
			coordinator.process().close();
			resetServices.remove(agentId);
		}
		resetServices.entrySet().removeIf(entry ->
			entry.getValue().snapshot().ownerClusterId().equals(clusterId));
	}
}
