package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;

/** Immutable bounded production catalog, captured by the host; it does not execute queries. */
public record ProductionRecipeCatalog(UUID libraryId, long formationGeneration,
	long catalogGeneration, boolean loaded, List<PatternRecord> patterns) {
	public static final int MAX_PATTERNS = 4096;
	public ProductionRecipeCatalog {
		Objects.requireNonNull(libraryId);
		if (formationGeneration < 0 || catalogGeneration < 0)
			throw new IllegalArgumentException("negative Library catalog generation");
		patterns = List.copyOf(Objects.requireNonNull(patterns));
		if (patterns.size() > MAX_PATTERNS) throw new IllegalArgumentException("Library catalog exceeds bound");
	}

}
