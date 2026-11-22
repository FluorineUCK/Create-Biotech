package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.List;

/** Last completed recipe facts, independent of browser widgets and production query protocols. */
public record RecipeIndexSnapshot(long generation, boolean reindexing, List<PatternRecord> records,
	int invalidPages, int unsupportedPages, List<PatternPageError> errors) {
	/** Existing diagnostic preview representation bound, not the number of indexed errors. */
	public static final int MAX_ERRORS = 128;

	public RecipeIndexSnapshot {
		records = List.copyOf(records);
		errors = List.copyOf(errors);
		if (generation < 0 || invalidPages < 0 || unsupportedPages < 0
			|| (long) invalidPages + unsupportedPages > Integer.MAX_VALUE
			|| errors.size() > MAX_ERRORS || errors.size() > (long) invalidPages + unsupportedPages)
			throw new IllegalArgumentException("Invalid recipe index snapshot");
	}

	public RecipeIndexSnapshot withReindexing(boolean value) {
		return value == reindexing ? this
			: new RecipeIndexSnapshot(generation, value, records, invalidPages, unsupportedPages, errors);
	}

	public int errorPages() { return invalidPages + unsupportedPages; }
}
