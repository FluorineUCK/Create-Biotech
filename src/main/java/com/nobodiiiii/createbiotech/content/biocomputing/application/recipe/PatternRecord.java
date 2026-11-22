package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRepresentationBounds;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPackageCapacity;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

public record PatternRecord(UUID patternId, PatternPageKey source, List<PatternIngredient> inputs,
	List<PatternOutput> outputs, PatternBody body, OptionalInt priorityOverride) {
	public PatternRecord {
		patternId = Objects.requireNonNull(patternId, "patternId");
		source = Objects.requireNonNull(source, "source");
		inputs = List.copyOf(inputs);
		outputs = List.copyOf(outputs);
		if (outputs.isEmpty())
			throw new IllegalArgumentException("Pattern must have output");
		body = Objects.requireNonNull(body, "body");
		priorityOverride = Objects.requireNonNull(priorityOverride, "priorityOverride");
		PatternRepresentationBounds.requireRecordFits(inputs, outputs, body);
		PatternPackageCapacity.requirePatternFits(inputs, body);
	}

	public PatternOutput mainOutput() {
		return outputs.getFirst();
	}

	public PatternKind kind() {
		return body instanceof StandardPatternBody ? PatternKind.STANDARD : PatternKind.SEQUENCED;
	}
}
