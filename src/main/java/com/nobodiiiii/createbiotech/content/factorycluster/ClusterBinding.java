package com.nobodiiiii.createbiotech.content.factorycluster;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public record ClusterBinding(UUID clusterId, long revision,
	@Nullable ClusterAuthority authority, List<LogisticsBinding> logisticsBindings) {
	public static final int DATA_VERSION = 1;
	public static final int MAX_BINDINGS = 32;

	private static final String VERSION_KEY = "Version";
	private static final String CLUSTER_ID_KEY = "ClusterId";
	private static final String REVISION_KEY = "Revision";
	private static final String AUTHORITY_KEY = "Authority";
	private static final String LOGISTICS_BINDINGS_KEY = "LogisticsBindings";

	public ClusterBinding {
		Objects.requireNonNull(clusterId, "clusterId");
		if (revision < 0)
			throw new IllegalArgumentException("revision must not be negative");
		logisticsBindings = LogisticsBinding.normalize(
			Objects.requireNonNull(logisticsBindings, "logisticsBindings"));
		if (logisticsBindings.size() > MAX_BINDINGS)
			throw new IllegalArgumentException("too many logistics bindings");
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt(VERSION_KEY, DATA_VERSION);
		tag.putUUID(CLUSTER_ID_KEY, clusterId);
		tag.putLong(REVISION_KEY, revision);
		if (authority != null)
			tag.put(AUTHORITY_KEY, authority.save());
		ListTag encodedBindings = new ListTag();
		logisticsBindings.forEach(binding -> encodedBindings.add(binding.save()));
		tag.put(LOGISTICS_BINDINGS_KEY, encodedBindings);
		return tag;
	}

	public static Optional<ClusterBinding> tryLoad(CompoundTag tag) {
		if (!tag.contains(VERSION_KEY, Tag.TAG_INT)
			|| tag.getInt(VERSION_KEY) != DATA_VERSION
			|| !tag.hasUUID(CLUSTER_ID_KEY)
			|| !tag.contains(REVISION_KEY, Tag.TAG_LONG)
			|| tag.getLong(REVISION_KEY) < 0)
			return Optional.empty();

		ClusterAuthority authority = null;
		if (tag.contains(AUTHORITY_KEY)) {
			if (!tag.contains(AUTHORITY_KEY, Tag.TAG_COMPOUND))
				return Optional.empty();
			Optional<ClusterAuthority> decodedAuthority =
				ClusterAuthority.tryLoad(tag.getCompound(AUTHORITY_KEY));
			if (decodedAuthority.isEmpty())
				return Optional.empty();
			authority = decodedAuthority.get();
		}

		Tag rawBindings = tag.get(LOGISTICS_BINDINGS_KEY);
		if (!(rawBindings instanceof ListTag encodedBindings)
			|| (!encodedBindings.isEmpty()
				&& encodedBindings.getElementType() != Tag.TAG_COMPOUND)
			|| encodedBindings.size() > MAX_BINDINGS)
			return Optional.empty();

		Set<UUID> seen = new HashSet<>();
		java.util.ArrayList<LogisticsBinding> bindings = new java.util.ArrayList<>();
		for (Tag encoded : encodedBindings) {
			if (!(encoded instanceof CompoundTag bindingTag))
				return Optional.empty();
			Optional<LogisticsBinding> binding = LogisticsBinding.load(bindingTag);
			if (binding.isEmpty() || !seen.add(binding.get().logisticsId()))
				return Optional.empty();
			bindings.add(binding.get());
		}

		try {
			return Optional.of(new ClusterBinding(tag.getUUID(CLUSTER_ID_KEY),
				tag.getLong(REVISION_KEY), authority, bindings));
		} catch (IllegalArgumentException invalidState) {
			return Optional.empty();
		}
	}
}
