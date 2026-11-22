package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.List;

public record SequencedPatternBody(List<PatternStage> stages) implements PatternBody {
	public SequencedPatternBody {
		stages = List.copyOf(stages);
		PatternRepresentationBounds.requireListFits(stages);
		if (stages.isEmpty())
			throw new IllegalArgumentException("Sequenced Pattern must have at least one stage");
		for (int index = 0; index < stages.size(); index++)
			if (stages.get(index).index() != index)
				throw new IllegalArgumentException("Pattern stage indexes must be contiguous from zero");
	}
}
