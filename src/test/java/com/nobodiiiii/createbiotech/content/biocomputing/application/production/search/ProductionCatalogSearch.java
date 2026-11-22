package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Production-only, one-item traversal over frozen facts; never reads a world or executes production. */
public final class ProductionCatalogSearch {
    private ProductionCatalogSearch() {}

    public static Advance advance(ProductionRecipeCatalog catalog, PatternQuery query) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(query, "query");
        if (!query.libraryId().equals(catalog.libraryId()) || !catalog.loaded())
            return new Advance(Optional.of(PatternReply.unavailable(query)), Optional.empty());
        if (query.formationGeneration() != catalog.formationGeneration()
                || query.queryGeneration() != catalog.catalogGeneration()) {
            var restart = new PatternReply(query.queryId(), query.requesterComputerId(), query.logisticsId(),
                catalog.libraryId(), catalog.catalogGeneration(), PatternReply.Status.RESTART_REQUIRED,
                List.of(), 0, catalog.formationGeneration(), query.searchGeneration(), query.attemptId());
            return new Advance(Optional.of(restart), Optional.empty());
        }
        if (query.pageCursor() >= catalog.patterns().size())
            return new Advance(Optional.of(PatternReply.groupEnd(query, query.pageCursor())), Optional.empty());
        var candidate = catalog.patterns().get(query.pageCursor());
        int next = query.pageCursor() + 1;
        if (candidate.mainOutput().stack().equals(query.requestedStackKey()))
            return new Advance(Optional.of(PatternReply.matchBatch(query, List.of(candidate), next)),
                Optional.of(query.continueAt(next)));
        if (next < catalog.patterns().size())
            return new Advance(Optional.empty(), Optional.of(query.continueAt(next)));
        return new Advance(Optional.of(PatternReply.groupEnd(query, next)), Optional.empty());
    }

    public record Advance(Optional<PatternReply> reply, Optional<PatternQuery> continuation) {
        public Advance { Objects.requireNonNull(reply); Objects.requireNonNull(continuation); }
    }
}
