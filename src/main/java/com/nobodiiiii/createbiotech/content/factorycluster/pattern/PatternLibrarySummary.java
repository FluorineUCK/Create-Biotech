package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.List;
import java.util.Objects;

/** Bounded, traversal-free status projection for pattern-library screens. */
public record PatternLibrarySummary(
	PatternLibraryScanner.StructureState reason,
	int capacity,
	int pages,
	int indexedPages,
	int indexedPatterns,
	int errorPages,
	int queuedPages,
	boolean scanning,
	List<PatternPageError> errors
) {
	public static final int MAX_ERRORS = 128;

	public PatternLibrarySummary {
		reason = Objects.requireNonNull(reason, "reason");
		errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
		if (capacity < 0 || pages < 0 || indexedPages < 0 || indexedPatterns < 0
			|| errorPages < 0 || queuedPages < 0 || indexedPages > pages
			|| errorPages > pages || queuedPages > pages || errors.size() > MAX_ERRORS
			|| errors.size() > errorPages)
			throw new IllegalArgumentException("Invalid pattern-library summary");
	}
}
