package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import java.util.Objects;
import java.util.UUID;

/** Immutable player or parent request before it is admitted as a Goal. */
public record GoalSubmission(UUID goalId, UUID patternId, long multiplier, InputMode inputMode) {
	public GoalSubmission {
		goalId = Objects.requireNonNull(goalId, "goalId");
		patternId = Objects.requireNonNull(patternId, "patternId");
		if (multiplier <= 0)
			throw new IllegalArgumentException("Goal multiplier must be positive");
		inputMode = Objects.requireNonNull(inputMode, "inputMode");
	}
}
