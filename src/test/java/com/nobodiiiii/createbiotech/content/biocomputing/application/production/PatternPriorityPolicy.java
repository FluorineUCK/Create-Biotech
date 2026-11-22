package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import java.util.Objects;
import java.util.OptionalInt;

/** Production-only precedence; authorization is checked by the host, not granted by this calculation. */
public final class PatternPriorityPolicy {

	private PatternPriorityPolicy() {}

	/** Keeps inactive stored overrides visible without using them to select a production route. */
	public static Resolution resolve(OptionalInt storedOverride, int libraryPriority,
		boolean coordinationAuthorized) {
		Objects.requireNonNull(storedOverride, "storedOverride");
		boolean active = coordinationAuthorized && storedOverride.isPresent();
		return new Resolution(storedOverride, active,
			active ? storedOverride.getAsInt() : libraryPriority);
	}

	public record Resolution(OptionalInt storedOverride, boolean active, int effectivePriority) {
		public Resolution {
			storedOverride = Objects.requireNonNull(storedOverride, "storedOverride");
			if (active && storedOverride.isEmpty())
				throw new IllegalArgumentException("An absent Pattern override cannot be active");
		}
	}
}
