package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import java.util.Objects;
import java.util.UUID;

/** Frozen production reservations only; each Computer still owns its independent generic resources. */
public record ProductionEpoch(UUID epochId, UUID groupId, UUID structureId, UUID coordinatorId,
    ProductionLayout layout) {
    public ProductionEpoch {
        Objects.requireNonNull(epochId); Objects.requireNonNull(groupId); Objects.requireNonNull(structureId);
        Objects.requireNonNull(coordinatorId); Objects.requireNonNull(layout);
        if (!coordinatorId.equals(layout.members().getFirst().computerId())
            || layout.members().stream().anyMatch(member -> member.profile() == null))
            throw new IllegalArgumentException("frozen members require their own profiles and minimum-UUID coordinator");
    }
    public ProductionLayout.Address coordinatorAddress() { return layout.members().getFirst().address(); }
    public static ProductionEpoch freeze(UUID groupId, UUID structureId, ProductionLayout layout) {
        return new ProductionEpoch(UUID.randomUUID(), groupId, structureId, layout.members().getFirst().computerId(), layout);
    }
    public ProductionEpoch relocate(ProductionLayout current) {
        return new ProductionEpoch(epochId, groupId, structureId, coordinatorId,
            ProductionEpochRelocation.relocate(layout, Objects.requireNonNull(current)));
    }
}
