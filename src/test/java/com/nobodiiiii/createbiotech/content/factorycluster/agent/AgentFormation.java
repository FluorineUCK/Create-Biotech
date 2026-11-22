package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;

/** A coreless logical Agent formed from its loaded native endpoints. */
public record AgentFormation(UUID agentId, UUID ownerClusterId, long agentGeneration,
	SpaceAddress coordinatorAddress, List<AgentEndpointState> inputs,
	List<AgentEndpointState> outputs, List<AgentEndpointState> members) {
	public static final Comparator<SpaceAddress> ADDRESS_ORDER = Comparator
		.comparing((SpaceAddress address) -> address.dimension().location().toString())
		.thenComparing(address -> address.subLevelId() == null ? "" : address.subLevelId().toString())
		.thenComparingInt(address -> address.localPos().getX())
		.thenComparingInt(address -> address.localPos().getY())
		.thenComparingInt(address -> address.localPos().getZ());
	private static final Comparator<AgentEndpointState> ENDPOINT_ORDER =
		Comparator.comparing(AgentEndpointState::endpointAddress, ADDRESS_ORDER);

	public AgentFormation {
		agentId = Objects.requireNonNull(agentId, "agentId");
		ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
		if (agentGeneration < 0)
			throw new IllegalArgumentException("Agent generation cannot be negative");
		coordinatorAddress = Objects.requireNonNull(coordinatorAddress, "coordinatorAddress");
		inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
		outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
		members = List.copyOf(Objects.requireNonNull(members, "members"));
		UUID expectedAgentId = agentId;
		UUID expectedOwnerClusterId = ownerClusterId;
		if (inputs.isEmpty() || outputs.isEmpty())
			throw new IllegalArgumentException("An Agent requires loaded input and output endpoints");
		Set<AgentEndpointState> partition = new HashSet<>(inputs);
		partition.addAll(outputs);
		if (members.size() != inputs.size() + outputs.size()
			|| inputs.stream().anyMatch(endpoint -> endpoint.role() != AgentEndpointRole.INPUT)
			|| outputs.stream().anyMatch(endpoint -> endpoint.role() != AgentEndpointRole.OUTPUT)
			|| partition.size() != members.size()
			|| !partition.equals(new HashSet<>(members))
			|| !members.equals(members.stream().sorted(ENDPOINT_ORDER).toList())
			|| members.stream().anyMatch(member -> !member.loaded())
			|| members.stream().anyMatch(member -> !member.agentId().equals(expectedAgentId)
				|| !member.ownerClusterId().equals(expectedOwnerClusterId)
				|| member.agentGeneration() != agentGeneration))
			throw new IllegalArgumentException("Invalid Agent membership snapshot");
		if (!coordinatorAddress.equals(members.getFirst().endpointAddress()))
			throw new IllegalArgumentException("Coordinator must be the lowest loaded endpoint");
	}

	public static AgentFormation form(List<AgentEndpointState> endpointSnapshots) {
		endpointSnapshots = List.copyOf(Objects.requireNonNull(endpointSnapshots,
			"endpointSnapshots"));
		List<AgentEndpointState> loaded = endpointSnapshots.stream()
			.filter(AgentEndpointState::loaded).sorted(ENDPOINT_ORDER).toList();
		if (loaded.isEmpty())
			throw new IllegalArgumentException("An Agent has no loaded endpoints");
		AgentEndpointState first = loaded.getFirst();
		Set<SpaceAddress> addresses = new HashSet<>();
		for (AgentEndpointState endpoint : loaded) {
			if (!endpoint.agentId().equals(first.agentId())
				|| !endpoint.ownerClusterId().equals(first.ownerClusterId())
				|| endpoint.agentGeneration() != first.agentGeneration())
				throw new IllegalArgumentException("Loaded endpoints disagree on Agent identity");
			if (!addresses.add(endpoint.endpointAddress()))
				throw new IllegalArgumentException("Duplicate physical Agent endpoint");
		}
		ArrayList<AgentEndpointState> inputs = new ArrayList<>();
		ArrayList<AgentEndpointState> outputs = new ArrayList<>();
		for (AgentEndpointState endpoint : loaded)
			(endpoint.role() == AgentEndpointRole.INPUT ? inputs : outputs).add(endpoint);
		return new AgentFormation(first.agentId(), first.ownerClusterId(),
			first.agentGeneration(), first.endpointAddress(), inputs, outputs, loaded);
	}

	public Set<SpaceAddress> membershipAddresses() {
		return members.stream().map(AgentEndpointState::endpointAddress)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	/** The stable identity published when this Cluster declares itself as an Agent. */
	public AgentServiceIdentity requireDeclaredAgent(
		ClusterCommunicationConfiguration configuration) {
		configuration = Objects.requireNonNull(configuration, "configuration");
		if (!ownerClusterId.equals(configuration.clusterId())
			|| configuration.role() != ClusterServiceRole.AGENT)
			throw new IllegalArgumentException("Agent formation differs from Cluster declaration");
		return new AgentServiceIdentity(agentId, agentGeneration);
	}

	public record AgentServiceIdentity(UUID agentId, long generation) {
		public AgentServiceIdentity {
			agentId = Objects.requireNonNull(agentId, "agentId");
			if (generation < 0) throw new IllegalArgumentException("negative Agent generation");
		}
	}

}
