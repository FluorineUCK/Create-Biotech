package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.Objects;

/** Canonical destination text shared by recipe declarations and production requests. */
public final class RecipeAddressRules {
	private RecipeAddressRules() {}
	public static String requireNormalized(String raw) {
		String normalized = Objects.requireNonNull(raw, "raw").trim();
		if (normalized.isBlank() || normalized.length() > 25
			|| normalized.codePoints().anyMatch(Character::isISOControl))
			throw new IllegalArgumentException("Invalid factory address");
		return normalized;
	}
}
