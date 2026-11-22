package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

/** One exact, generation-fenced member that can participate in an explicit radio route. */
public record RadioEndpoint(UUID clusterId, UUID memberId, SpaceAddress address,
	long generation, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency, int listenerCapacity,
	Set<ProductionCapability> capabilities) {
	public RadioEndpoint {
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		memberId = Objects.requireNonNull(memberId, "memberId");
		address = Objects.requireNonNull(address, "address");
		frequency = Objects.requireNonNull(frequency, "frequency");
		if (generation < 0)
			throw new IllegalArgumentException("endpoint generation cannot be negative");
		if (listenerCapacity < 0)
			throw new IllegalArgumentException("listener capacity cannot be negative");
		capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
	}

	public Key key() {
		return new Key(clusterId, memberId);
	}

	public Hop hop() {
		return new Hop(key(), generation);
	}

	public boolean supports(ProductionCapability capability) {
		return capabilities.contains(Objects.requireNonNull(capability, "capability"));
	}

	public record Key(UUID clusterId, UUID memberId) {
		public Key {
			clusterId = Objects.requireNonNull(clusterId, "clusterId");
			memberId = Objects.requireNonNull(memberId, "memberId");
		}
	}

	/** A route freezes both exact identity and membership generation. */
	public record Hop(Key key, long generation) {
		public Hop {
			key = Objects.requireNonNull(key, "key");
			if (generation < 0)
				throw new IllegalArgumentException("hop generation cannot be negative");
		}
	}
}
