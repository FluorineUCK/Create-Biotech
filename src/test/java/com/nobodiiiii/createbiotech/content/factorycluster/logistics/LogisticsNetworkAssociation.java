package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import java.util.Objects;
import java.util.UUID;

/** Server-resolved proof that an address carrier belongs to exactly one logistics network. */
public record LogisticsNetworkAssociation(UUID logisticsNetworkId,
	AssociationKind associationKind) {
	public LogisticsNetworkAssociation {
		logisticsNetworkId = Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
		associationKind = Objects.requireNonNull(associationKind, "associationKind");
	}

	public enum AssociationKind {
		DIRECT_LINK,
		ADJACENT_PACKAGER_LINK,
		AGENT_ENDPOINT
	}
}
