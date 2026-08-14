package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.Objects;

import net.minecraft.util.StringUtil;

public record PatternPageError(PatternPageKey source, PatternErrorReason reason, String detail) {
	public PatternPageError {
		source = Objects.requireNonNull(source, "source");
		reason = Objects.requireNonNull(reason, "reason");
		detail = StringUtil.truncateStringIfNecessary(Objects.requireNonNull(detail, "detail"), 96, false);
	}
}
