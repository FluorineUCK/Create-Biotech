package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/** The production command validator for Cluster Agent declaration and revocation. */
public final class ProductionAgentRoleTransitionPolicy
	implements ClusterCommunicationCommandService.RoleTransitionPolicy {
	private final Predicate<UUID> hasFormedAgent;
	private final Function<UUID, AgentRevocationDrainState> drainState;

	public ProductionAgentRoleTransitionPolicy(Predicate<UUID> hasFormedAgent,
		Function<UUID, AgentRevocationDrainState> drainState) {
		this.hasFormedAgent = Objects.requireNonNull(hasFormedAgent, "hasFormedAgent");
		this.drainState = Objects.requireNonNull(drainState, "drainState");
	}

	@Override
	public ClusterCommunicationCommandService.RoleTransitionResult validate(
		ClusterCommunicationCommandService.CommandAuthority authority,
		ClusterCommunicationConfiguration current, ClusterServiceRole nextRole) {
		authority = Objects.requireNonNull(authority, "authority");
		current = Objects.requireNonNull(current, "current");
		nextRole = Objects.requireNonNull(nextRole, "nextRole");
		if (nextRole == ClusterServiceRole.AGENT)
			return hasFormedAgent.test(authority.clusterId())
				? ClusterCommunicationCommandService.RoleTransitionResult.ALLOWED
				: ClusterCommunicationCommandService.RoleTransitionResult.AGENT_FORMATION_REQUIRED;
		AgentRevocationDrainState audited = Objects.requireNonNull(
			drainState.apply(authority.clusterId()), "drain state");
		if (!audited.clusterId().equals(authority.clusterId()))
			return ClusterCommunicationCommandService.RoleTransitionResult.UNSAFE_REVOCATION;
		return audited.safeToRevoke()
			? ClusterCommunicationCommandService.RoleTransitionResult.ALLOWED
			: ClusterCommunicationCommandService.RoleTransitionResult.UNSAFE_REVOCATION;
	}
}
