package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;

/**
 * Immutable loop-prevention evidence carried with traffic that crosses subnet boundaries.
 * Network and Cluster identity remain distinct: neither identity may reappear on a forward.
 */
public record SubnetRouteTrace(Set<UUID> visitedNetworks, Set<UUID> visitedClusters) {
	public SubnetRouteTrace {
		visitedNetworks = boundedCopy(visitedNetworks, "visitedNetworks");
		visitedClusters = boundedCopy(visitedClusters, "visitedClusters");
		if (visitedNetworks.isEmpty() || visitedClusters.isEmpty())
			throw new IllegalArgumentException("a subnet trace needs a network and Cluster origin");
	}

	public static SubnetRouteTrace start(UUID networkId, UUID clusterId) {
		return new SubnetRouteTrace(Set.of(requireIdentity(networkId, "networkId")),
			Set.of(requireIdentity(clusterId, "clusterId")));
	}

	public boolean hasVisitedNetwork(UUID networkId) {
		return visitedNetworks.contains(requireIdentity(networkId, "networkId"));
	}

	public boolean hasVisitedCluster(UUID clusterId) {
		return visitedClusters.contains(requireIdentity(clusterId, "clusterId"));
	}

	public boolean canVisit(UUID networkId, UUID clusterId) {
		return !hasVisitedNetwork(networkId) && !hasVisitedCluster(clusterId)
			&& visitedNetworks.size() < FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES
			&& visitedClusters.size() < FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES;
	}

	/** Adds exactly one new network and Cluster identity, or rejects the route before forwarding. */
	public SubnetRouteTrace visit(UUID networkId, UUID clusterId) {
		networkId = requireIdentity(networkId, "networkId");
		clusterId = requireIdentity(clusterId, "clusterId");
		if (hasVisitedNetwork(networkId) || hasVisitedCluster(clusterId))
			throw new IllegalStateException("a subnet route cannot revisit a network or Cluster");
		if (visitedNetworks.size() >= FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES
			|| visitedClusters.size() >= FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
			throw new IllegalStateException("subnet route trace representation is full");
		Set<UUID> nextNetworks = new LinkedHashSet<>(visitedNetworks);
		nextNetworks.add(networkId);
		Set<UUID> nextClusters = new LinkedHashSet<>(visitedClusters);
		nextClusters.add(clusterId);
		return new SubnetRouteTrace(nextNetworks, nextClusters);
	}

	private static Set<UUID> boundedCopy(Set<UUID> identities, String name) {
		identities = Objects.requireNonNull(identities, name);
		if (identities.size() > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
			throw new IllegalArgumentException(name + " exceeds the durable representation bound");
		LinkedHashSet<UUID> copied = new LinkedHashSet<>();
		for (UUID identity : identities)
			copied.add(requireIdentity(identity, name + " entry"));
		return Set.copyOf(copied);
	}

	private static UUID requireIdentity(UUID identity, String name) {
		return Objects.requireNonNull(identity, name);
	}
}
