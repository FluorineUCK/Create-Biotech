package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

/**
 * Exact server-minted authority scope shared by the independent Task command domains.
 *
 * <p>The legacy mixed packet adapts this scope for its compatibility-only handler. New Task and
 * Goal domains must depend on this type rather than the legacy packet.</p>
 */
public record FactoryPanelCommandScope(UUID clusterId, UUID epochId, long snapshotGeneration,
	long viewRevision, UUID panelId, SpaceAddress panelAddress, UUID menuSessionId,
	Optional<SpaceAddress> actorAddress) {
	public FactoryPanelCommandScope {
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		epochId = Objects.requireNonNull(epochId, "epochId");
		panelId = Objects.requireNonNull(panelId, "panelId");
		panelAddress = Objects.requireNonNull(panelAddress, "panelAddress");
		menuSessionId = Objects.requireNonNull(menuSessionId, "menuSessionId");
		actorAddress = Objects.requireNonNull(actorAddress, "actorAddress");
		if (snapshotGeneration < 0 || viewRevision < 0)
			throw new IllegalArgumentException("negative Panel command fence");
	}

	public FactoryPanelCommandScope(UUID clusterId, UUID epochId, long snapshotGeneration,
		long viewRevision, UUID panelId, SpaceAddress panelAddress, UUID menuSessionId) {
		this(clusterId, epochId, snapshotGeneration, viewRevision, panelId, panelAddress,
			menuSessionId, Optional.empty());
	}

	public boolean actorWithin(SpaceAddress endpoint, double maximumDistanceSquared) {
		endpoint = Objects.requireNonNull(endpoint, "endpoint");
		if (maximumDistanceSquared < 0 || !Double.isFinite(maximumDistanceSquared))
			throw new IllegalArgumentException("invalid local authority distance");
		SpaceAddress actor = actorAddress.orElse(null);
		return actor != null && actor.dimension().equals(endpoint.dimension())
			&& Objects.equals(actor.subLevelId(), endpoint.subLevelId())
			&& actor.localPos().distSqr(endpoint.localPos()) <= maximumDistanceSquared;
	}
}
