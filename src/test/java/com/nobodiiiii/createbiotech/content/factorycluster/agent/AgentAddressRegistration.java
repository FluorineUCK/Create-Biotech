package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;

/** One generation-tagged Agent claim on a real Create input route. */
public record AgentAddressRegistration(CreateRouteKey route, UUID agentId,
	UUID ownerClusterId, long agentGeneration, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency, LoadState loadState,
	Reachability reachability) {
	public AgentAddressRegistration {
		route = Objects.requireNonNull(route, "route");
		agentId = Objects.requireNonNull(agentId, "agentId");
		ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
		frequency = Objects.requireNonNull(frequency, "frequency");
		if (agentGeneration < 0)
			throw new IllegalArgumentException("Agent generation cannot be negative");
		loadState = Objects.requireNonNull(loadState, "loadState");
		reachability = Objects.requireNonNull(reachability, "reachability");
	}

	public boolean availableTo(UUID requesterClusterId, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency requesterFrequency) {
		requesterClusterId = Objects.requireNonNull(requesterClusterId, "requesterClusterId");
		return loadState == LoadState.LOADED && (ownerClusterId.equals(requesterClusterId)
			|| (requesterFrequency.configured() && frequency.equals(requesterFrequency)
				&& reachability == Reachability.REACHABLE));
	}

	public enum LoadState { LOADED, UNLOADED }
	public enum Reachability { REACHABLE, UNREACHABLE }
}
