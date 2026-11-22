package com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing;

import java.util.Objects;
import java.util.UUID;

/**
 * Durable exact identity of the provider selected for one fenced route attempt.
 * A descriptor is execution evidence only while an authoritative resolver can
 * reproduce the same scoped source.
 */
public record SelectedProviderDescriptor(RouteSelectionScope selectionScope,
	UUID libraryId, UUID patternId, String providerKey,
	RouteCandidate.ProviderKind providerKind, int effectivePriority,
	RouteCandidate.Availability availability) {
	public SelectedProviderDescriptor {
		selectionScope = Objects.requireNonNull(selectionScope, "selectionScope");
		RouteCandidate selected = new RouteCandidate(libraryId, patternId, providerKey,
			providerKind, effectivePriority, availability, RouteCandidate.Blocker.NONE);
		libraryId = selected.libraryId();
		patternId = selected.patternId();
		providerKey = selected.providerKey();
		providerKind = selected.providerKind();
		availability = selected.availability();
		if (availability != RouteCandidate.Availability.AVAILABLE)
			throw new IllegalArgumentException("A selected provider must be available");
	}

	public static SelectedProviderDescriptor selected(RouteSelectionScope scope,
		RouteCandidate candidate) {
		Objects.requireNonNull(candidate, "candidate");
		if (candidate.blocker() != RouteCandidate.Blocker.NONE)
			throw new IllegalArgumentException("A selected provider cannot be blocked");
		return new SelectedProviderDescriptor(scope, candidate.libraryId(), candidate.patternId(),
			candidate.providerKey(), candidate.providerKind(), candidate.effectivePriority(),
			candidate.availability());
	}

	public RouteCandidate candidate() {
		return new RouteCandidate(libraryId, patternId, providerKey, providerKind,
			effectivePriority, availability, RouteCandidate.Blocker.NONE);
	}

	/** Exact scope plus candidate comparison used after every durable/reload boundary. */
	public boolean matches(ProductionRouteTable.ProviderSource source) {
		return source != null && selectionScope.equals(source.scope())
			&& candidate().equals(source.candidate());
	}
}
