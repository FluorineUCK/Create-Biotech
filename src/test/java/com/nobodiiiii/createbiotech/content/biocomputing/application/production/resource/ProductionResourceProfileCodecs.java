package com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Strict historical production-profile NBT, separate from values and resident/world reads. */
public final class ProductionResourceProfileCodecs {
	private ProductionResourceProfileCodecs() {}
	private static final int VERSION = 1;
	private static final Set<String> KEYS = Set.of("Version", "Profession", "ProcessSlots",
		"Capabilities", "ListenerRangeBonus");

	public static CompoundTag save(ProductionResourceProfile profile) {
		Objects.requireNonNull(profile, "profile");
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", VERSION);
		tag.putString("Profession", profile.profession().name());
		tag.putInt("ProcessSlots", profile.processSlots());
		ListTag capabilityTags = new ListTag();
		profile.capabilities().stream().sorted().map(ProductionCapability::name)
			.map(StringTag::valueOf).forEach(capabilityTags::add);
		tag.put("Capabilities", capabilityTags);
		tag.putInt("ListenerRangeBonus", profile.listenerRangeBonus());
		return tag;
	}

	public static Optional<ProductionResourceProfile> load(CompoundTag tag) {
		if (tag == null || !tag.getAllKeys().equals(KEYS)
			|| !hasType(tag, "Version", Tag.TAG_INT) || tag.getInt("Version") != VERSION
			|| !hasType(tag, "Profession", Tag.TAG_STRING)
			|| !hasType(tag, "ProcessSlots", Tag.TAG_INT)
			|| !hasType(tag, "Capabilities", Tag.TAG_LIST)
			|| !hasType(tag, "ListenerRangeBonus", Tag.TAG_INT))
			return Optional.empty();
		try {
			ProductionProfession profession = ProductionProfession.valueOf(tag.getString("Profession"));
			ListTag list = (ListTag) tag.get("Capabilities");
			if (list == null || list.size() > ProductionCapability.values().length
				|| (!list.isEmpty() && list.getElementType() != Tag.TAG_STRING))
				return Optional.empty();
			EnumSet<ProductionCapability> capabilities = EnumSet.noneOf(ProductionCapability.class);
			for (Tag value : list) {
				if (!(value instanceof StringTag string)
					|| !capabilities.add(ProductionCapability.valueOf(string.getAsString())))
					return Optional.empty();
			}
			return Optional.of(new ProductionResourceProfile(profession, tag.getInt("ProcessSlots"),
				capabilities, tag.getInt("ListenerRangeBonus")));
		} catch (IllegalArgumentException | NullPointerException exception) {
			return Optional.empty();
		}
	}

	private static boolean hasType(CompoundTag tag, String key, int type) {
		Tag value = tag.get(key);
		return value != null && value.getId() == type;
	}
}
