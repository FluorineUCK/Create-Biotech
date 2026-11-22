package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.Optional;

public record PageInspection(String fingerprint, Optional<ParseResult> changedResult) {
	public PageInspection {
		changedResult = Optional.ofNullable(changedResult).orElseThrow();
	}
}
