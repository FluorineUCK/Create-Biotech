package com.nobodiiiii.createbiotech.content.factorycluster;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

/** UUID-only Cluster reference to one server-global Create logistics-network identity. */
public record LogisticsBinding(UUID logisticsNetworkId) {
	public LogisticsBinding {
		Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("Id", logisticsNetworkId);
		return tag;
	}

	/** Rejects the former alias-bearing representation instead of migrating it locally. */
	public static Optional<LogisticsBinding> load(CompoundTag tag) {
		return tag.getAllKeys().equals(java.util.Set.of("Id")) && tag.hasUUID("Id")
			? Optional.of(new LogisticsBinding(tag.getUUID("Id"))) : Optional.empty();
	}

	public static List<LogisticsBinding> normalize(List<LogisticsBinding> input) {
		LinkedHashMap<UUID, LogisticsBinding> ordered = new LinkedHashMap<>();
		for (LogisticsBinding binding : input)
			if (binding != null)
				ordered.putIfAbsent(binding.logisticsNetworkId(), binding);
		return List.copyOf(ordered.values());
	}
}
