package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-side seam for resolving a Cluster's primary cross-Cluster frequency.
 * Task 1 has no durable Cluster directory, so all cross-Cluster resolution is
 * deliberately unconfigured.
 */
@FunctionalInterface
public interface ClusterPrimaryFrequencyResolver {
	CommunicationFrequency primaryFrequency(UUID clusterId);

	static ClusterPrimaryFrequencyResolver unconfigured() {
		return clusterId -> {
			Objects.requireNonNull(clusterId, "clusterId");
			return CommunicationFrequency.UNCONFIGURED;
		};
	}
}
