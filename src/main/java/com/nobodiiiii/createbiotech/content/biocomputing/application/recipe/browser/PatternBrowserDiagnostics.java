package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import java.util.Objects;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner.State;

/** Reads only the Core's maintained counters; not a browser-triggered structure scan. */
public record PatternBrowserDiagnostics(State structure, int ordinaryShelves, int chiseledShelves,
    int searchBudget, int replyQueues, int activeQueries, int readyReplies) {
    public PatternBrowserDiagnostics {
        Objects.requireNonNull(structure);
        if (ordinaryShelves < 0 || ordinaryShelves > 1024 || chiseledShelves < 0 || chiseledShelves > 1024
            || searchBudget < 0 || searchBudget > 10000 || replyQueues < 0 || replyQueues > 1024
            || activeQueries < 0 || readyReplies < 0) throw new IllegalArgumentException("invalid browser diagnostics");
    }
    public static PatternBrowserDiagnostics empty() { return new PatternBrowserDiagnostics(State.UNFORMED, 0, 0, 0, 0, 0, 0); }
}
