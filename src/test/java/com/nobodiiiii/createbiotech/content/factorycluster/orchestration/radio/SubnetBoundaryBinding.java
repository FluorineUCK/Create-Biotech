package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

/**
 * Authoritative composition-root registration for one directed subnet crossing. Listener
 * lifecycle owns when this is installed; the router owns validation of every resulting route.
 */
public record SubnetBoundaryBinding(UUID sourceClusterId, UUID sourceNetworkId,
	UUID targetNetworkId, UUID targetClusterId, CommunicationFrequency listenerFrequency,
	RadioEndpoint.Hop sourceHop, RadioEndpoint.Hop targetHop) {
	public SubnetBoundaryBinding {
		sourceClusterId = Objects.requireNonNull(sourceClusterId, "sourceClusterId");
		sourceNetworkId = Objects.requireNonNull(sourceNetworkId, "sourceNetworkId");
		targetNetworkId = Objects.requireNonNull(targetNetworkId, "targetNetworkId");
		targetClusterId = Objects.requireNonNull(targetClusterId, "targetClusterId");
		listenerFrequency = Objects.requireNonNull(listenerFrequency, "listenerFrequency");
		sourceHop = Objects.requireNonNull(sourceHop, "sourceHop");
		targetHop = Objects.requireNonNull(targetHop, "targetHop");
		if (sourceClusterId.equals(targetClusterId))
			throw new IllegalArgumentException("a subnet boundary cannot target its source Cluster");
		if (sourceNetworkId.equals(targetNetworkId))
			throw new IllegalArgumentException("a subnet boundary cannot merge one network into itself");
		if (!listenerFrequency.configured())
			throw new IllegalArgumentException("a subnet boundary needs a configured listener frequency");
		if (!sourceHop.key().clusterId().equals(sourceClusterId)
			|| !targetHop.key().clusterId().equals(targetClusterId))
			throw new IllegalArgumentException("boundary gateway hops must match their declared Clusters");
	}
}
