package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.List;
import java.util.Objects;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner.State;

/** Bounded projection of recipe maintenance, separate from general book storage capacity. */
public record RecipeLibrarySummary(State reason, int capacity, int pages, int indexedPages,
    int indexedPatterns, int errorPages, int queuedPages, boolean scanning, List<PatternPageError> errors) {
    public static final int MAX_ERRORS = RecipeIndexSnapshot.MAX_ERRORS;

    public RecipeLibrarySummary {
        Objects.requireNonNull(reason, "reason");
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        if (capacity < 0 || pages < 0 || indexedPages < 0 || indexedPatterns < 0
            || errorPages < 0 || queuedPages < 0 || indexedPages > pages
            || errorPages > pages || queuedPages > pages || errors.size() > MAX_ERRORS
            || errors.size() > errorPages)
            throw new IllegalArgumentException("Invalid recipe-library summary");
    }

    public static RecipeLibrarySummary capture(RecipePageIndex data, State reason, int capacity) {
        int indexedPages = data.indexPassComplete() ? data.pageOrder().size() : data.fingerprintCursor();
        return new RecipeLibrarySummary(reason, Math.max(0, capacity), data.pageOrder().size(), indexedPages,
            data.indexedPatternCount(), data.errorPageCount(), data.indexPassComplete() ? 0 : data.pageOrder().size() - data.fingerprintCursor(),
            !data.indexPassComplete(), data.pageErrors());
    }
}
