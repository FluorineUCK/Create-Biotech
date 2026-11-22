package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.Objects;
import java.util.UUID;

/** Durable, revision-fenced communication configuration owned by one Cluster. */
public record ClusterCommunicationConfiguration(UUID clusterId,
	CommunicationFrequency primaryFrequency, ClusterServiceRole role, long revision) {
	public ClusterCommunicationConfiguration {
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		primaryFrequency = Objects.requireNonNull(primaryFrequency, "primaryFrequency");
		role = Objects.requireNonNull(role, "role");
		if (revision < 0) throw new IllegalArgumentException("negative revision");
	}
}
