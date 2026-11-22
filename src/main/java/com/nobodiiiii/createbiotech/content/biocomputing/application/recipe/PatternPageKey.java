package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.Objects;

public record PatternPageKey(RecipeSourceLocation shelf, int slot, int page) {
	public PatternPageKey {
		shelf = Objects.requireNonNull(shelf, "shelf");
		if (slot < 0 || slot >= 6 || page < 0 || page >= 100)
			throw new IllegalArgumentException("Invalid chiseled bookshelf page address");
	}
}
