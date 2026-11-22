package com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing;

import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/** One production route, independent of whether its provider is native or Biotech-owned. */
public record RouteCandidate(UUID libraryId, UUID patternId, String providerKey,
	ProviderKind providerKind, int effectivePriority, Availability availability,
	Blocker blocker) {
	public static final Comparator<RouteCandidate> STABLE_IDENTITY = Comparator
		.comparing(RouteCandidate::libraryId)
		.thenComparing(RouteCandidate::patternId)
		.thenComparing(RouteCandidate::providerKey);

	public RouteCandidate {
		libraryId = Objects.requireNonNull(libraryId, "libraryId");
		patternId = Objects.requireNonNull(patternId, "patternId");
		providerKey = Objects.requireNonNull(providerKey, "providerKey").trim();
		if (providerKey.isEmpty())
			throw new IllegalArgumentException("Provider key cannot be blank");
		providerKind = Objects.requireNonNull(providerKind, "providerKind");
		availability = Objects.requireNonNull(availability, "availability");
		blocker = Objects.requireNonNull(blocker, "blocker");
		if (availability == Availability.AVAILABLE && blocker != Blocker.NONE)
			throw new IllegalArgumentException("An available route cannot be hard-blocked");
	}

	public String stableKey() {
		return libraryId + "/" + patternId + "/" + providerKey;
	}

	public enum ProviderKind { PATTERN, AGENT, FACTORY_GAUGE, ORDINARY_PROVIDER }

	public enum Availability {
		AVAILABLE, BUSY, UNAVAILABLE;

		int order() {
			return ordinal();
		}
	}

	public enum Blocker {
		NONE,
		AGENT_CONFLICT,
		UNSUPPORTED_PATTERN,
		MISSING_REQUIRED_AGENT,
		UNREACHABLE_KNOWN_ROUTE
	}
}
