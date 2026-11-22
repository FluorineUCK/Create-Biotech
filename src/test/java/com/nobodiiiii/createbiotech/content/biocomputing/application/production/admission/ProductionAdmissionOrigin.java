package com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

/** Durable command provenance, not a synthetic Panel identity or an alternative task queue. */
public record ProductionAdmissionOrigin(Kind kind, UUID sourceId, SpaceAddress sourceAddress,
    UUID requestId, Optional<SpaceAddress> actorAddress, long requestedItems) {
    public enum Kind { PANEL, STOCK_KEEPER }
    public ProductionAdmissionOrigin {
        Objects.requireNonNull(kind); Objects.requireNonNull(sourceId);
        Objects.requireNonNull(sourceAddress); Objects.requireNonNull(requestId); Objects.requireNonNull(actorAddress);
        if (kind == Kind.PANEL ? requestedItems != 0 : requestedItems <= 0)
            throw new IllegalArgumentException("Invalid admission quantity");
    }
    public static ProductionAdmissionOrigin panel(UUID panel, SpaceAddress address, UUID session,
        Optional<SpaceAddress> actor) {
        return new ProductionAdmissionOrigin(Kind.PANEL, panel, address, session, actor, 0);
    }
    public static ProductionAdmissionOrigin stockKeeper(UUID player, SpaceAddress ticker, UUID request, long items) {
        return new ProductionAdmissionOrigin(Kind.STOCK_KEEPER, player, ticker, request, Optional.empty(), items);
    }
    /** Stock Keeper orders item quantities; indivisible recipes may produce a final partial-batch surplus. */
    public long multiplier(long panelBatches, long outputPerOperation) {
        if (panelBatches <= 0 || outputPerOperation <= 0) throw new IllegalArgumentException("Non-positive production quantity");
        return kind == Kind.PANEL ? panelBatches : 1 + (requestedItems - 1) / outputPerOperation;
    }
}
