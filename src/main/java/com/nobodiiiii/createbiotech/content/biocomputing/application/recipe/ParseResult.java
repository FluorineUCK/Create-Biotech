package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

public sealed interface ParseResult permits ParseResult.Blank, ParseResult.Valid, ParseResult.Invalid,
	ParseResult.Unsupported {
	record Blank(String fingerprint) implements ParseResult {}
	record Valid(PatternRecord pattern, String fingerprint) implements ParseResult {}
	record Invalid(PatternPageError error, String fingerprint) implements ParseResult {}
	record Unsupported(UnsupportedPatternEnvelope envelope, PatternPageError error,
		String fingerprint) implements ParseResult {}
}

