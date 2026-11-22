package com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Freezes collector participation before any provider feed is accepted. */
public record RouteSelectionRequest(RouteSelectionScope scope,
	Set<RouteCandidate.ProviderKind> requiredCollectors,
	Set<RouteCandidate.ProviderKind> optionalCollectors) {
	public RouteSelectionRequest {
		scope = Objects.requireNonNull(scope, "scope");
		requiredCollectors = Set.copyOf(Objects.requireNonNull(requiredCollectors, "requiredCollectors"));
		optionalCollectors = Set.copyOf(Objects.requireNonNull(optionalCollectors, "optionalCollectors"));
		EnumSet<RouteCandidate.ProviderKind> assigned = EnumSet.noneOf(RouteCandidate.ProviderKind.class);
		assigned.addAll(requiredCollectors);
		if (!java.util.Collections.disjoint(requiredCollectors, optionalCollectors))
			throw new IllegalArgumentException("collector cannot be both required and optional");
		assigned.addAll(optionalCollectors);
		if (!assigned.equals(EnumSet.allOf(RouteCandidate.ProviderKind.class)))
			throw new IllegalArgumentException("every provider collector must be frozen");
	}

	public static RouteSelectionRequest allRequired(RouteSelectionScope scope) {
		return new RouteSelectionRequest(scope, EnumSet.allOf(RouteCandidate.ProviderKind.class), Set.of());
	}

	/** Pattern must yield a route; production provider collectors may explicitly complete empty. */
	public static RouteSelectionRequest patternOnly(RouteSelectionScope scope) {
		return new RouteSelectionRequest(scope, EnumSet.of(RouteCandidate.ProviderKind.PATTERN),
			EnumSet.of(RouteCandidate.ProviderKind.AGENT, RouteCandidate.ProviderKind.FACTORY_GAUGE,
				RouteCandidate.ProviderKind.ORDINARY_PROVIDER));
	}
}
