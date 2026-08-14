package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternLibraryScanner.StructureState;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.VillagerType;

/** Strict, bounded client projection for a pattern library core. */
public record PatternCoreClientState(boolean renderLibrarian, ResourceLocation villagerType,
	int librarianLevel, @Nullable String customName, StructureState structureState,
	boolean pendingSafeRelease, int logicalMembers, int ordinaryBookshelves,
	int chiseledBookshelves, int searchBudget, int queueCount) {
	private static final Set<String> REQUIRED = Set.of("RenderLibrarian", "VillagerType", "LibrarianLevel",
		"StructureState", "PendingSafeRelease", "LogicalMembers", "OrdinaryBookshelves",
		"ChiseledBookshelves", "SearchBudget", "QueueCount");
	private static final String CUSTOM_NAME = "CustomName";

	public PatternCoreClientState {
		if (villagerType == null || structureState == null || librarianLevel < 1 || librarianLevel > 5
			|| logicalMembers < 0 || logicalMembers > 1024 || ordinaryBookshelves < 0
			|| ordinaryBookshelves > 1024 || chiseledBookshelves < 0 || chiseledBookshelves > 1024
			|| searchBudget < 0 || searchBudget > 10000 || queueCount < 0 || queueCount > 1024
			|| (customName != null && customName.codePointCount(0, customName.length()) > 64))
			throw new IllegalArgumentException("Invalid pattern-core client state");
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("RenderLibrarian", renderLibrarian);
		tag.putString("VillagerType", villagerType.toString());
		tag.putInt("LibrarianLevel", librarianLevel);
		if (customName != null)
			tag.putString(CUSTOM_NAME, customName);
		tag.putString("StructureState", structureState.name());
		tag.putBoolean("PendingSafeRelease", pendingSafeRelease);
		tag.putInt("LogicalMembers", logicalMembers);
		tag.putInt("OrdinaryBookshelves", ordinaryBookshelves);
		tag.putInt("ChiseledBookshelves", chiseledBookshelves);
		tag.putInt("SearchBudget", searchBudget);
		tag.putInt("QueueCount", queueCount);
		return tag;
	}

	public static Optional<PatternCoreClientState> load(CompoundTag tag) {
		if (!hasExactKeys(tag) || !has(tag, "RenderLibrarian", Tag.TAG_BYTE)
			|| !has(tag, "VillagerType", Tag.TAG_STRING) || !has(tag, "LibrarianLevel", Tag.TAG_INT)
			|| !has(tag, "StructureState", Tag.TAG_STRING) || !has(tag, "PendingSafeRelease", Tag.TAG_BYTE)
			|| !has(tag, "LogicalMembers", Tag.TAG_INT) || !has(tag, "OrdinaryBookshelves", Tag.TAG_INT)
			|| !has(tag, "ChiseledBookshelves", Tag.TAG_INT) || !has(tag, "SearchBudget", Tag.TAG_INT)
			|| !has(tag, "QueueCount", Tag.TAG_INT)
			|| (tag.contains(CUSTOM_NAME) && !has(tag, CUSTOM_NAME, Tag.TAG_STRING))
			|| !canonicalBoolean(tag, "RenderLibrarian")
			|| !canonicalBoolean(tag, "PendingSafeRelease"))
			return Optional.empty();
		try {
			ResourceLocation type = ResourceLocation.tryParse(tag.getString("VillagerType"));
			StructureState state = StructureState.valueOf(tag.getString("StructureState"));
			BuiltInRegistries.VILLAGER_TYPE.getKey(VillagerType.PLAINS);
			if (type == null || !BuiltInRegistries.VILLAGER_TYPE.containsKey(type))
				return Optional.empty();
			return Optional.of(new PatternCoreClientState(tag.getBoolean("RenderLibrarian"), type,
				tag.getInt("LibrarianLevel"), tag.contains(CUSTOM_NAME) ? tag.getString(CUSTOM_NAME) : null,
				state, tag.getBoolean("PendingSafeRelease"), tag.getInt("LogicalMembers"),
				tag.getInt("OrdinaryBookshelves"), tag.getInt("ChiseledBookshelves"),
				tag.getInt("SearchBudget"), tag.getInt("QueueCount")));
		} catch (IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}

	private static boolean hasExactKeys(CompoundTag tag) {
		Set<String> keys = tag.getAllKeys();
		return (keys.size() == REQUIRED.size() || keys.size() == REQUIRED.size() + 1)
			&& keys.containsAll(REQUIRED) && (keys.size() == REQUIRED.size() || keys.contains(CUSTOM_NAME));
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
