package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.Locale;
import java.util.Optional;

public enum PatternKind {
	STANDARD,
	SEQUENCED;

	public String serializedName() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static Optional<PatternKind> fromSerializedName(String value) {
		for (PatternKind kind : values())
			if (kind.serializedName().equals(value))
				return Optional.of(kind);
		return Optional.empty();
	}
}
