package com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Historical cursor format; persistence is separate from the world-independent route policy. */
public final class RouteCursorCodecs {
	private RouteCursorCodecs() {}

	public static CompoundTag save(DefaultRoutePolicy.CursorState state) {
		CompoundTag root = new CompoundTag();
		ListTag cursors = new ListTag();
		Objects.requireNonNull(state, "state").lastSelectedByPriority().entrySet().stream().sorted(Map.Entry.comparingByKey())
			.forEach(entry -> {
				CompoundTag cursor = new CompoundTag();
				cursor.putInt("Priority", entry.getKey());
				cursor.putUUID("LibraryId", entry.getValue().libraryId());
				cursor.putUUID("PatternId", entry.getValue().patternId());
				cursor.putString("ProviderKey", entry.getValue().providerKey());
				cursors.add(cursor);
			});
		root.put("Cursors", cursors);
		return root;
	}

	public static Optional<DefaultRoutePolicy.CursorState> load(CompoundTag root) {
		Objects.requireNonNull(root, "root");
		if (!root.getAllKeys().equals(java.util.Set.of("Cursors"))
			|| !(root.get("Cursors") instanceof ListTag cursors))
			return Optional.empty();
		if (cursors.size() > DefaultRoutePolicy.CursorState.MAX_PRIORITIES)
			return Optional.empty();
		Map<Integer, DefaultRoutePolicy.RouteIdentity> loaded = new HashMap<>();
		for (Tag value : cursors) {
			if (!(value instanceof CompoundTag cursor)
				|| !cursor.getAllKeys().equals(java.util.Set.of("Priority", "LibraryId", "PatternId", "ProviderKey"))
				|| !cursor.contains("Priority", Tag.TAG_INT)
				|| !cursor.hasUUID("LibraryId") || !cursor.hasUUID("PatternId")
				|| !cursor.contains("ProviderKey", Tag.TAG_STRING))
				return Optional.empty();
			String providerKey = cursor.getString("ProviderKey");
			if (providerKey.isBlank() || providerKey.trim().isEmpty()
				|| providerKey.length() > DefaultRoutePolicy.RouteIdentity.MAX_PROVIDER_KEY_LENGTH
				|| loaded.put(cursor.getInt("Priority"), new DefaultRoutePolicy.RouteIdentity(
					cursor.getUUID("LibraryId"), cursor.getUUID("PatternId"), providerKey)) != null)
				return Optional.empty();
		}
		return Optional.of(new DefaultRoutePolicy.CursorState(loaded));
	}
}
