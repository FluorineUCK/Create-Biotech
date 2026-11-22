package com.nobodiiiii.createbiotech.content.biocomputing.application.production.material;

import java.util.Objects;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Exact immutable material description; not a world operation or object reference. */
public record OrderedIngredient(StackKey stack, long amount) {
	public OrderedIngredient {
		stack = Objects.requireNonNull(stack, "stack");
		if (amount <= 0) throw new IllegalArgumentException("ingredient amount must be positive");
	}
}
