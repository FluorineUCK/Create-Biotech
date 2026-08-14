package com.nobodiiiii.createbiotech.content.factorycluster;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

public record LogisticsBinding(UUID logisticsId, String alias) {
	public static final int MAX_ALIAS_LENGTH = 32;

	public LogisticsBinding {
		Objects.requireNonNull(logisticsId, "logisticsId");
		alias = truncateToCodePoints(alias == null ? "" : alias.strip(), MAX_ALIAS_LENGTH);
		if (alias.isEmpty())
			alias = logisticsId.toString().substring(0, 8);
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("Id", logisticsId);
		tag.putString("Alias", alias);
		return tag;
	}

	public static Optional<LogisticsBinding> load(CompoundTag tag) {
		return tag.hasUUID("Id") && tag.contains("Alias", Tag.TAG_STRING)
			? Optional.of(new LogisticsBinding(tag.getUUID("Id"), tag.getString("Alias")))
			: Optional.empty();
	}

	public static List<LogisticsBinding> normalize(List<LogisticsBinding> input) {
		LinkedHashMap<UUID, LogisticsBinding> ordered = new LinkedHashMap<>();
		for (LogisticsBinding binding : input)
			if (binding != null)
				ordered.putIfAbsent(binding.logisticsId(), binding);
		return List.copyOf(ordered.values());
	}

	private static String truncateToCodePoints(String value, int maximumCodePoints) {
		if (value.codePointCount(0, value.length()) <= maximumCodePoints)
			return value;
		return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
	}
}
