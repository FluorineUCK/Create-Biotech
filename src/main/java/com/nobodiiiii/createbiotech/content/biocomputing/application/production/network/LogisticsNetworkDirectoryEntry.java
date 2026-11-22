package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.FactoryPublicTextPolicy;

/** A durable, globally monotonic identity for one observed Create logistics network. */
public record LogisticsNetworkDirectoryEntry(UUID logisticsNetworkId, long ordinal,
	Optional<String> customAlias, long revision) {
	public static final int MAX_ALIAS_LENGTH = 64;
	public LogisticsNetworkDirectoryEntry {
		Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
		customAlias = Objects.requireNonNull(customAlias, "customAlias");
		if (ordinal <= 0 || revision < 0) throw new IllegalArgumentException("invalid directory entry");
		customAlias = customAlias.map(LogisticsNetworkDirectoryEntry::canonicalAlias);
	}

	/** Canonical global alias rule shared by directory state and bounded Panel projection. */
	public static String canonicalAlias(String alias) {
		alias = java.text.Normalizer.normalize(Objects.requireNonNull(alias, "alias"),
			java.text.Normalizer.Form.NFKC).strip();
		if (alias.isBlank() || alias.length() > MAX_ALIAS_LENGTH
			|| alias.codePoints().anyMatch(LogisticsNetworkDirectoryEntry::forbiddenAliasCodePoint))
			throw new IllegalArgumentException("invalid global logistics alias");
		return alias;
	}

	private static boolean forbiddenAliasCodePoint(int codePoint) {
		return Character.isISOControl(codePoint)
			|| FactoryPublicTextPolicy.isDefaultIgnorable(codePoint);
	}
}
