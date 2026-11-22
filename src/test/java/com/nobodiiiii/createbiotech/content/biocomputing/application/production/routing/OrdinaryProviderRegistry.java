package com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/**
 * Server-owned registry for production providers that are neither Patterns, declared Agents,
 * nor native Create Factory Gauges. Registrations describe only provider routing state; they do
 * not reinterpret recipe ingredients or Create dispatch order.
 */
public final class OrdinaryProviderRegistry {
	public static final int MAX_REGISTRATIONS = ProductionRouteTable.MAX_SOURCES;
	private final LinkedHashMap<UUID, Registration> registrations = new LinkedHashMap<>();

	public synchronized boolean register(Registration registration) {
		registration = Objects.requireNonNull(registration, "registration");
		Registration known = registrations.get(registration.providerId());
		if (known != null) return known.equals(registration);
		if (registrations.size() >= MAX_REGISTRATIONS)
			throw new IllegalStateException("ordinary provider registry exceeds bound");
		registrations.put(registration.providerId(), registration);
		return true;
	}

	public synchronized boolean unregister(UUID providerId) {
		return registrations.remove(Objects.requireNonNull(providerId, "providerId")) != null;
	}

	public synchronized List<Registration> snapshot() {
		return List.copyOf(registrations.values());
	}

	/** Collects one exact output/network snapshot under the current production selection scope. */
	public synchronized List<ProductionRouteTable.ProviderSource> collect(
		RouteSelectionScope scope, UUID logisticsNetworkId, UUID libraryId, UUID patternId) {
		RouteSelectionScope exactScope = Objects.requireNonNull(scope, "scope");
		UUID exactNetwork = Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
		UUID exactLibrary = Objects.requireNonNull(libraryId, "libraryId");
		UUID exactPattern = Objects.requireNonNull(patternId, "patternId");
		StackKey exactOutput = exactScope.requestedStackKey();
		return registrations.values().stream()
			.filter(registration -> registration.logisticsNetworkId().equals(exactNetwork)
				&& registration.output().equals(exactOutput))
			.map(registration -> new ProductionRouteTable.ProviderSource(exactScope, exactLibrary,
				exactPattern, "ordinary/" + registration.providerId(),
				RouteCandidate.ProviderKind.ORDINARY_PROVIDER, registration.routePriority(),
				registration.availability(), RouteCandidate.Blocker.NONE))
			.toList();
	}

	public record Registration(UUID providerId, UUID logisticsNetworkId, StackKey output,
		int routePriority, RouteCandidate.Availability availability) {
		public Registration {
			providerId = Objects.requireNonNull(providerId, "providerId");
			logisticsNetworkId = Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
			output = Objects.requireNonNull(output, "output");
			availability = Objects.requireNonNull(availability, "availability");
		}
	}
}
