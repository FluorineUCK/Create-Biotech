package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;

import java.util.List;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;

public record PatternStage(int index, String address, List<PatternIngredient> inputs) {
	public PatternStage {
		if (index < 0)
			throw new IllegalArgumentException("Pattern stage index must be non-negative");
		address = RecipeAddressRules.requireNormalized(address);
		inputs = List.copyOf(inputs);
		PatternRepresentationBounds.requireListFits(inputs);
	}
}
