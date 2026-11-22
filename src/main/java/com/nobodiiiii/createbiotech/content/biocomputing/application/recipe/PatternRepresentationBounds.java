package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;

import java.util.List;

import net.minecraft.world.item.component.WritableBookContent;

/** Persistence safety bounds derived from the only player-authored Pattern source: one writable-book page. */
public final class PatternRepresentationBounds {
	public static final int MAX_ENTRIES = WritableBookContent.PAGE_EDIT_LENGTH;

	private PatternRepresentationBounds() {}

	static void requireListFits(List<?> values) {
		if (values.size() > MAX_ENTRIES)
			throw new IllegalArgumentException("Pattern collection exceeds page representation budget");
	}

	public static void requireRecordFits(List<PatternIngredient> inputs, List<PatternOutput> outputs,
		PatternBody body) {
		if (body instanceof StandardPatternBody standard) standard.grid().validate(inputs);
		try {
			int entries = Math.addExact(inputs.size(), outputs.size());
			if (body instanceof SequencedPatternBody sequenced) {
				entries = Math.addExact(entries, sequenced.stages().size());
				for (PatternStage stage : sequenced.stages())
					entries = Math.addExact(entries, stage.inputs().size());
			}
			if (entries > MAX_ENTRIES)
				throw new IllegalArgumentException("Pattern exceeds page representation budget");
		} catch (ArithmeticException overflow) {
			throw new IllegalArgumentException("Pattern representation calculation overflow", overflow);
		}
	}
}
