package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

/** Immutable server-thread snapshot of one native Create Factory Panel endpoint. */
public record AgentEndpointState(SpaceAddress endpointAddress, UUID agentId,
	UUID ownerClusterId, long agentGeneration, AgentEndpointRole role, CreateRouteKey route,
	int routePriority, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency, AgentCompletionMode completionMode,
	Optional<AgentSignalSelector> signalSelector, OutputMode outputMode,
	long outputPolicyGeneration,
	CreateRouteKey returnWarehouseRoute, boolean loaded, boolean reachable) {
	public static AgentEndpointState fromNative(SpaceAddress endpointAddress,
		UUID ownerClusterId, UUID logisticsNetworkId,
		FactoryPanelAgentAccess.Configuration configuration, boolean loaded,
		boolean reachable) {
		Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
		Objects.requireNonNull(configuration, "configuration");
		if (!Objects.requireNonNull(ownerClusterId, "ownerClusterId")
			.equals(configuration.ownerClusterId()))
			throw new IllegalArgumentException("Native endpoint owner differs from its current configuration");
		return new AgentEndpointState(endpointAddress, configuration.agentId(), ownerClusterId,
			configuration.agentGeneration(), configuration.role(),
			new CreateRouteKey(logisticsNetworkId, configuration.normalizedAddress()),
			configuration.routePriority(), configuration.frequency(), configuration.completionMode(),
			configuration.signalSelector(), configuration.outputMode(),
			configuration.outputPolicyGeneration(), configuration.returnWarehouseRoute(), loaded,
			reachable);
	}

	public AgentEndpointState {
		endpointAddress = Objects.requireNonNull(endpointAddress, "endpointAddress");
		agentId = Objects.requireNonNull(agentId, "agentId");
		ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
		if (agentGeneration < 0 || outputPolicyGeneration < 0)
			throw new IllegalArgumentException("Agent generations cannot be negative");
		role = Objects.requireNonNull(role, "role");
		route = Objects.requireNonNull(route, "route");
		frequency = Objects.requireNonNull(frequency, "frequency");
		completionMode = Objects.requireNonNull(completionMode, "completionMode");
		signalSelector = Objects.requireNonNull(signalSelector, "signalSelector");
		if ((completionMode == AgentCompletionMode.SIGNAL) != signalSelector.isPresent())
			throw new IllegalArgumentException("SIGNAL endpoint needs exactly one selector");
		outputMode = Objects.requireNonNull(outputMode, "outputMode");
		returnWarehouseRoute = Objects.requireNonNull(returnWarehouseRoute,
			"returnWarehouseRoute");
	}

	public AgentEndpointState withAgentIdentity(UUID nextAgentId, UUID nextOwnerClusterId,
		long nextGeneration) {
		return new AgentEndpointState(endpointAddress, nextAgentId, nextOwnerClusterId,
			nextGeneration, role, route, routePriority, frequency, completionMode, signalSelector,
			outputMode, outputPolicyGeneration, returnWarehouseRoute, loaded, reachable);
	}

	public enum OutputMode {
		WAREHOUSE_ALL,
		DIRECT_REQUIRED
	}
}
