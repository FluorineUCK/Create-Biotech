package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PatternRecord(UUID patternId, PatternPageKey source, List<PatternIngredient> inputs,
	List<PatternOutput> outputs, String recipeAddress) {
	public PatternRecord {
		patternId = Objects.requireNonNull(patternId, "patternId");
		source = Objects.requireNonNull(source, "source");
		inputs = List.copyOf(inputs);
		outputs = List.copyOf(outputs);
		if (outputs.isEmpty())
			throw new IllegalArgumentException("Pattern must have output");
		recipeAddress = Objects.requireNonNull(recipeAddress, "recipeAddress");
		if (recipeAddress.isBlank() || !recipeAddress.equals(recipeAddress.strip()) || recipeAddress.length() > 25)
			throw new IllegalArgumentException("Invalid recipe address");
	}

	public PatternOutput mainOutput() {
		return outputs.getFirst();
	}
}
