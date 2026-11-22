package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;

public record StandardPatternBody(String address, CraftingSlots grid) implements PatternBody {
	public StandardPatternBody(String address) { this(address, CraftingSlots.NONE); }
	public StandardPatternBody {
		java.util.Objects.requireNonNull(grid);
		address = RecipeAddressRules.requireNormalized(address);
	}
}
