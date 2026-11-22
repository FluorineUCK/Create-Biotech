package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternStage;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;

/** Immutable, page-bounded recipe/source facts; live Library priority and paid proof are external. */
public record PatternBrowserEntry(PatternRecord pattern) {
	public PatternBrowserEntry {
		pattern = Objects.requireNonNull(pattern, "pattern");
	}

	public UUID patternId() { return pattern.patternId(); }
	public PatternKind kind() { return pattern.kind(); }
	public PatternPageKey source() { return pattern.source(); }
	/** Selectors retain item/tag and component predicates, not fabricated exact input stacks. */
	public List<PatternIngredient> inputs() { return pattern.inputs(); }
	public List<PatternOutput> outputs() { return pattern.outputs(); }
	public OptionalInt storedPriorityOverride() { return pattern.priorityOverride(); }
	public List<PatternStage> stages() {
		return pattern.body() instanceof SequencedPatternBody sequenced ? sequenced.stages() : List.of();
	}
	public List<String> addresses() {
		return pattern.body() instanceof StandardPatternBody standard ? List.of(standard.address())
			: stages().stream().map(PatternStage::address).toList();
	}
}
