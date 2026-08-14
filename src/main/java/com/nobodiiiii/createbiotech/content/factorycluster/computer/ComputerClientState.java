package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Strict, bounded projection; it deliberately contains no resident or topology NBT. */
public record ComputerClientState(boolean renderResident, @Nullable NodeKind kind,
	int slots, int depth, ComputerDisplayState displayState) {
	private static final int VERSION = 1;
	private static final Set<String> WITHOUT_KIND = Set.of("Version", "RenderResident", "Slots",
		"Depth", "DisplayState");
	private static final Set<String> WITH_KIND = Set.of("Version", "RenderResident", "Kind", "Slots",
		"Depth", "DisplayState");

	public ComputerClientState {
		if (displayState == null || !validRenderedProfile(renderResident, kind, slots, depth))
			throw new IllegalArgumentException("invalid computer client state");
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", VERSION);
		tag.putBoolean("RenderResident", renderResident);
		if (kind != null) tag.putString("Kind", kind.name());
		tag.putInt("Slots", slots);
		tag.putInt("Depth", depth);
		tag.putString("DisplayState", displayState.name());
		return tag;
	}

	public static Optional<ComputerClientState> load(CompoundTag tag) {
		if (tag == null || (!tag.getAllKeys().equals(WITH_KIND)
			&& !tag.getAllKeys().equals(WITHOUT_KIND))
			|| !has(tag, "Version", Tag.TAG_INT) || tag.getInt("Version") != VERSION
			|| !has(tag, "RenderResident", Tag.TAG_BYTE) || !canonicalBoolean(tag, "RenderResident")
			|| !has(tag, "Slots", Tag.TAG_INT) || !has(tag, "Depth", Tag.TAG_INT)
			|| !has(tag, "DisplayState", Tag.TAG_STRING)
			|| (tag.contains("Kind") && !has(tag, "Kind", Tag.TAG_STRING)))
			return Optional.empty();
		try {
			NodeKind kind = tag.contains("Kind") ? NodeKind.valueOf(tag.getString("Kind")) : null;
			return Optional.of(new ComputerClientState(tag.getBoolean("RenderResident"), kind,
				tag.getInt("Slots"), tag.getInt("Depth"),
				ComputerDisplayState.valueOf(tag.getString("DisplayState"))));
		} catch (IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}

	private static boolean validRenderedProfile(boolean renderResident, @Nullable NodeKind kind,
		int slots, int depth) {
		if (!renderResident) return kind == null && slots == 0 && depth == 0;
		if (kind == null) return false;
		return switch (kind) {
			case VILLAGER -> pair(slots, depth, 1, 1) || pair(slots, depth, 2, 1)
				|| pair(slots, depth, 3, 2) || pair(slots, depth, 4, 2);
			case LIBRARIAN -> pair(slots, depth, 4, 2) || pair(slots, depth, 6, 2)
				|| pair(slots, depth, 8, 3) || pair(slots, depth, 12, 3)
				|| pair(slots, depth, 16, 4);
			case NITWIT -> pair(slots, depth, 1, 0);
			case WANDERING_TRADER -> pair(slots, depth, 2, 1);
			case ZOMBIE_VILLAGER -> pair(slots, depth, 0, 0);
		};
	}

	private static boolean pair(int slots, int depth, int expectedSlots, int expectedDepth) {
		return slots == expectedSlots && depth == expectedDepth;
	}

	private static boolean has(CompoundTag tag, String key, int type) {
		Tag value = tag.get(key);
		return value != null && value.getId() == type;
	}

	private static boolean canonicalBoolean(CompoundTag tag, String key) {
		byte value = tag.getByte(key);
		return value == 0 || value == 1;
	}
}
