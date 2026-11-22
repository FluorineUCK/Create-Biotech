package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import java.util.UUID;

/**
 * Read-only, server-supplied query for an exact Pattern priority qualification.
 * Implementations must use authoritative production state, not player data or a selected route.
 * This is not a lease, a world-operation permit, or a generic node/serialized value.
 */
@FunctionalInterface
public interface PatternPriorityAccess {
    PatternPriorityAccess NONE = (owner, library, pattern, generation) -> false;

    boolean authorizes(UUID ownerClusterId, UUID libraryId, UUID patternId, long catalogGeneration);
}
