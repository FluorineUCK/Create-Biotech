package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeIndexSnapshot;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternPriorityAccess;

/** Read-only cache projection. This service has no world, query, parser or scheduler capability. */
public final class PatternBrowserService {
    private final BrowserFence opening;
    private final Supplier<RecipeIndexSnapshot> snapshots;
    private final int libraryPriority;
    private final UUID clusterId;
    private final Supplier<? extends PatternPriorityAccess> coordination;
    public PatternBrowserService(BrowserFence opening, Supplier<RecipeIndexSnapshot> snapshots) {
        this(opening, snapshots, 0);
    }
    public PatternBrowserService(BrowserFence opening, Supplier<RecipeIndexSnapshot> snapshots, int localPriority) {
        this(opening, snapshots, localPriority, null, () -> PatternPriorityAccess.NONE);
    }
    public PatternBrowserService(BrowserFence opening, Supplier<RecipeIndexSnapshot> snapshots,
        int libraryPriority, UUID clusterId, Supplier<? extends PatternPriorityAccess> coordination) {
        this.opening = Objects.requireNonNull(opening); this.snapshots = Objects.requireNonNull(snapshots);
        this.libraryPriority = libraryPriority; this.clusterId = clusterId; this.coordination = Objects.requireNonNull(coordination);
    }
    public PatternBrowserPage page(BrowserFence fence, PatternBrowserFilter filter, int page) { return page(fence, filter, page, Optional.empty()); }
    public PatternBrowserPage page(BrowserFence fence, PatternBrowserFilter filter, int page, Optional<UUID> selection) {
        Objects.requireNonNull(fence); Objects.requireNonNull(filter); Objects.requireNonNull(selection);
        if (!opening.sameSession(fence)) return PatternBrowserPage.empty(PatternBrowserPage.Status.DENIED, fence, page, false);
        var snapshot = snapshots.get();
        var current = opening.withGeneration(snapshot.generation());
        if (fence.generation() != snapshot.generation())
            return PatternBrowserPage.empty(PatternBrowserPage.Status.STALE, current, page, snapshot.reindexing());
        if (page < 0 || (long) page * PatternBrowserPage.PAGE_SIZE > Integer.MAX_VALUE)
            return PatternBrowserPage.empty(PatternBrowserPage.Status.INVALID_PAGE, current, page, snapshot.reindexing());
        // Traverse immutable cached facts only; never enqueue or restart production work.
        int matches = 0;
        long offset = (long) page * PatternBrowserPage.PAGE_SIZE;
        var visible = new java.util.ArrayList<PatternBrowserEntry>(PatternBrowserPage.PAGE_SIZE);
        for (var record : snapshot.records()) {
            var entry = new PatternBrowserEntry(record);
            if (!filter.matches(entry)) continue;
            if (matches >= offset && visible.size() < PatternBrowserPage.PAGE_SIZE) visible.add(entry);
            matches++;
        }
        if (page > 0 && offset >= matches)
            return PatternBrowserPage.empty(PatternBrowserPage.Status.INVALID_PAGE, current, page, snapshot.reindexing());
        var paid = coordination.get();
        var rows = visible.stream().map(entry -> PatternBrowserSummary.from(entry, libraryPriority,
            clusterId != null && paid.authorizes(clusterId, current.libraryId(), entry.patternId(), current.generation()))).toList();
        var detail = selection.flatMap(id -> visible.stream().filter(entry -> entry.patternId().equals(id)).findFirst());
        return new PatternBrowserPage(PatternBrowserPage.Status.OK, current, page, matches, snapshot.records().size(),
            snapshot.reindexing(), snapshot.invalidPages(), snapshot.unsupportedPages(), rows, detail);
    }
}
