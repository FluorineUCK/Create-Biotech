package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.Objects;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;

public record PatternPageKey(SpaceAddress shelf, int slot, int page) {
	public PatternPageKey {
		shelf = Objects.requireNonNull(shelf, "shelf");
		if (slot < 0 || slot >= 6 || page < 0 || page >= 100)
			throw new IllegalArgumentException("Invalid chiseled bookshelf page address");
	}
}
