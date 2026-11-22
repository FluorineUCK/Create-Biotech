package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.util.List;
import java.util.Objects;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;

/** The exhaustive Agent address decision. Only {@link NeverRegistered} permits bare fallback. */
public sealed interface AgentResolution permits AgentResolution.Unique,
	AgentResolution.Conflict, AgentResolution.KnownUnavailable,
	AgentResolution.NeverRegistered {
	CreateRouteKey route();

	record Unique(CreateRouteKey route, AgentAddressRegistration registration)
		implements AgentResolution {
		public Unique {
			route = Objects.requireNonNull(route, "route");
			registration = Objects.requireNonNull(registration, "registration");
			if (!route.equals(registration.route()))
				throw new IllegalArgumentException("registration belongs to another route");
		}
	}

	record Conflict(CreateRouteKey route, List<AgentAddressRegistration> registrations)
		implements AgentResolution {
		public Conflict {
			CreateRouteKey checkedRoute = Objects.requireNonNull(route, "route");
			registrations = List.copyOf(Objects.requireNonNull(registrations, "registrations"));
			if (registrations.size() < 2 || registrations.stream()
				.anyMatch(registration -> !checkedRoute.equals(registration.route())))
				throw new IllegalArgumentException("a conflict needs multiple claims on one route");
			route = checkedRoute;
		}
	}

	record KnownUnavailable(CreateRouteKey route,
		List<AgentAddressRegistration> activeRegistrations, long newestKnownGeneration)
		implements AgentResolution {
		public KnownUnavailable {
			CreateRouteKey checkedRoute = Objects.requireNonNull(route, "route");
			activeRegistrations = List.copyOf(Objects.requireNonNull(activeRegistrations,
				"activeRegistrations"));
			if (newestKnownGeneration < 0 || activeRegistrations.stream()
				.anyMatch(registration -> !checkedRoute.equals(registration.route())))
				throw new IllegalArgumentException("invalid unavailable route evidence");
			route = checkedRoute;
		}
	}

	record NeverRegistered(CreateRouteKey route) implements AgentResolution {
		public NeverRegistered { route = Objects.requireNonNull(route, "route"); }
	}
}
