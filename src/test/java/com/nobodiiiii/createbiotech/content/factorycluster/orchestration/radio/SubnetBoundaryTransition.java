package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

/** Durable structured evidence that must exactly match a currently registered router binding. */
record SubnetBoundaryTransition(UUID sourceClusterId, UUID sourceNetworkId,
	UUID targetNetworkId, UUID targetClusterId, CommunicationFrequency listenerFrequency,
	RadioEndpoint.Hop sourceHop, RadioEndpoint.Hop targetHop, SubnetRouteTrace previousTrace) {
	SubnetBoundaryTransition {
		sourceClusterId = Objects.requireNonNull(sourceClusterId, "sourceClusterId");
		sourceNetworkId = Objects.requireNonNull(sourceNetworkId, "sourceNetworkId");
		targetNetworkId = Objects.requireNonNull(targetNetworkId, "targetNetworkId");
		targetClusterId = Objects.requireNonNull(targetClusterId, "targetClusterId");
		listenerFrequency = Objects.requireNonNull(listenerFrequency, "listenerFrequency");
		sourceHop = Objects.requireNonNull(sourceHop, "sourceHop");
		targetHop = Objects.requireNonNull(targetHop, "targetHop");
		previousTrace = Objects.requireNonNull(previousTrace, "previousTrace");
		if (!previousTrace.hasVisitedNetwork(sourceNetworkId)
			|| !previousTrace.hasVisitedCluster(sourceClusterId)
			|| previousTrace.hasVisitedNetwork(targetNetworkId)
			|| previousTrace.hasVisitedCluster(targetClusterId))
			throw new IllegalArgumentException("boundary transition does not begin at its declared source");
		if (!sourceHop.key().clusterId().equals(sourceClusterId)
			|| !targetHop.key().clusterId().equals(targetClusterId))
			throw new IllegalArgumentException("boundary transition hops differ from their declared Clusters");
	}

	static SubnetBoundaryTransition from(SubnetBoundaryBinding binding,
		SubnetRouteTrace previousTrace) {
		binding = Objects.requireNonNull(binding, "binding");
		previousTrace = Objects.requireNonNull(previousTrace, "previousTrace");
		return new SubnetBoundaryTransition(binding.sourceClusterId(), binding.sourceNetworkId(),
			binding.targetNetworkId(), binding.targetClusterId(), binding.listenerFrequency(),
			binding.sourceHop(), binding.targetHop(), previousTrace);
	}

	SubnetBoundaryBinding binding() {
		return new SubnetBoundaryBinding(sourceClusterId, sourceNetworkId, targetNetworkId,
			targetClusterId, listenerFrequency, sourceHop, targetHop);
	}

	boolean exactlyExtends(SubnetRouteTrace trace) {
		trace = Objects.requireNonNull(trace, "trace");
		try {
			return trace.equals(previousTrace.visit(targetNetworkId, targetClusterId));
		} catch (IllegalArgumentException | IllegalStateException invalid) {
			return false;
		}
	}
}
