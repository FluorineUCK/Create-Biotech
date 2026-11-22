package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;

import java.util.Objects;

import net.minecraft.world.item.component.WritableBookContent;

/** Bounded diagnostic copy of a syntactically valid but unsupported Pattern page. */
public record UnsupportedPatternEnvelope(int version, String type, PatternPageKey source,
	String fingerprint, String rawText) {
	public static final int MAX_TYPE_LENGTH = 64;
	public static final int MAX_RAW_TEXT_LENGTH = WritableBookContent.PAGE_EDIT_LENGTH;

	public UnsupportedPatternEnvelope {
		if (version != 2)
			throw new IllegalArgumentException("Unsupported Pattern envelope must use current version 2");
		type = Objects.requireNonNull(type, "type");
		if (type.isBlank() || !type.equals(type.strip()) || type.length() > MAX_TYPE_LENGTH)
			throw new IllegalArgumentException("Invalid Pattern type");
		if (PatternKind.fromSerializedName(type).isPresent())
			throw new IllegalArgumentException("Supported Pattern type cannot use unsupported envelope");
		source = Objects.requireNonNull(source, "source");
		fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
		if (!isFingerprint(fingerprint))
			throw new IllegalArgumentException("Invalid Pattern fingerprint");
		rawText = Objects.requireNonNull(rawText, "rawText");
		if (rawText.length() > MAX_RAW_TEXT_LENGTH)
			throw new IllegalArgumentException("Unsupported Pattern text is not bounded");
	}

	private static boolean isFingerprint(String value) {
		if (value.length() != 64)
			return false;
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f')))
				return false;
		}
		return true;
	}
}
