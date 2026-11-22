package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Rows carry the exact main display stack, but not a copy of the entire recipe. */
public record PatternBrowserSummary(UUID patternId, PatternKind kind, PatternPageKey source,
    StackKey mainOutput, int outputCount, OptionalInt storedPriorityOverride,
    boolean overrideActive, int effectivePriority) {
    public PatternBrowserSummary {
        Objects.requireNonNull(patternId); Objects.requireNonNull(kind); Objects.requireNonNull(source);
        Objects.requireNonNull(mainOutput); Objects.requireNonNull(storedPriorityOverride);
        if (outputCount <= 0 || overrideActive && storedPriorityOverride.isEmpty()) throw new IllegalArgumentException("invalid browser summary");
    }
    public static PatternBrowserSummary from(PatternBrowserEntry entry, int libraryPriority, boolean paid) {
        var output = entry.pattern().mainOutput();
        boolean active = paid && entry.storedPriorityOverride().isPresent();
        return new PatternBrowserSummary(entry.patternId(), entry.kind(), entry.source(),
            output.stack(), output.count(), entry.storedPriorityOverride(),
            active, active ? entry.storedPriorityOverride().getAsInt() : libraryPriority);
    }
}
