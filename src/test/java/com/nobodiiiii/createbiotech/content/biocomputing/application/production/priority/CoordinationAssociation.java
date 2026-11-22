package com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority;

import java.util.Objects;
import java.util.UUID;

/** Durable exact Pattern association for one authoritative production priority reservation. */
public record CoordinationAssociation(UUID processId, UUID libraryId, UUID patternId,
    long catalogGeneration) {
    public CoordinationAssociation {
        Objects.requireNonNull(processId); Objects.requireNonNull(libraryId); Objects.requireNonNull(patternId);
        if (catalogGeneration < 0) throw new IllegalArgumentException("negative catalog generation");
    }
}
