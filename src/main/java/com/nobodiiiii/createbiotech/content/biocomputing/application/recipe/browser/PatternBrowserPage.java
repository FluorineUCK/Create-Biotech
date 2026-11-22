package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PatternBrowserPage(Status status, BrowserFence fence, int pageIndex, int totalMatches,
    int totalPatterns, boolean reindexing, int invalidPages, int unsupportedPages,
    List<PatternBrowserSummary> entries, Optional<PatternBrowserEntry> detail) {
    public static final int PAGE_SIZE = 32;
    public enum Status { OK, STALE, DENIED, INVALID_PAGE, TOO_LARGE }
    public PatternBrowserPage {
        Objects.requireNonNull(status); Objects.requireNonNull(fence); Objects.requireNonNull(detail);
        entries = List.copyOf(entries);
        if (pageIndex < 0 || totalMatches < 0 || totalPatterns < totalMatches || invalidPages < 0 || unsupportedPages < 0
            || entries.size() > PAGE_SIZE || entries.size() > totalMatches
            || status != Status.OK && (!entries.isEmpty() || detail.isPresent())
            || detail.isPresent() && entries.stream().noneMatch(row -> row.patternId().equals(detail.get().patternId())))
            throw new IllegalArgumentException("invalid browser page");
    }
    public static PatternBrowserPage empty(Status status, BrowserFence fence, int page, boolean reindexing) {
        return new PatternBrowserPage(status, fence, Math.max(0, page), 0, 0, reindexing, 0, 0, List.of(), Optional.empty());
    }
}
