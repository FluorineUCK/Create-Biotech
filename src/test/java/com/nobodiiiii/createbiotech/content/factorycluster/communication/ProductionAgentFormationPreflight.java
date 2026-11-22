package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentFormation;

/** Exact loaded-endpoint formation preflight used before persisting an AGENT declaration. */
public final class ProductionAgentFormationPreflight {
	private ProductionAgentFormationPreflight() {}

	public static boolean hasExactFormation(UUID clusterId,
		List<AgentEndpointState> endpointSnapshots) {
		UUID exactCluster = Objects.requireNonNull(clusterId, "clusterId");
		List<AgentEndpointState> loaded = List.copyOf(Objects.requireNonNull(endpointSnapshots,
			"endpointSnapshots")).stream().filter(AgentEndpointState::loaded)
			.filter(endpoint -> endpoint.ownerClusterId().equals(exactCluster)).toList();
		if (loaded.isEmpty()) return false;
		LinkedHashSet<SpaceAddress> addresses = new LinkedHashSet<>();
		LinkedHashMap<UUID, java.util.ArrayList<AgentEndpointState>> byAgent =
			new LinkedHashMap<>();
		for (AgentEndpointState endpoint : loaded) {
			if (!addresses.add(endpoint.endpointAddress())) return false;
			byAgent.computeIfAbsent(endpoint.agentId(), ignored -> new java.util.ArrayList<>())
				.add(endpoint);
		}
		for (List<AgentEndpointState> members : byAgent.values()) {
			try {
				AgentFormation.form(members);
			} catch (IllegalArgumentException invalidFormation) {
				return false;
			}
		}
		return !byAgent.isEmpty();
	}
}
