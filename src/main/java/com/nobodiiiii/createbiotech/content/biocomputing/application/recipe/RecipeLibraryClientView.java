package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryResidentCapture;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner.State;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.VillagerType;

/** Historical recipe diagnostics, not the generic storage model or a reference to world objects. */
public record RecipeLibraryClientView(boolean renderLibrarian, ResourceLocation villagerType,
	int librarianLevel, @Nullable String customName, State structureState,
	boolean pendingSafeRelease, int logicalMembers, int ordinaryBookshelves,
	int chiseledBookshelves, int searchBudget, int queueCount) {
	public static final RecipeLibraryClientView EMPTY = new RecipeLibraryClientView(false,
		ResourceLocation.withDefaultNamespace("plains"), 1, null, State.UNFORMED, false, 0, 0, 0, 0, 1);
	// Historical wire bounds; these are not configurable storage capacity or execution budgets.
	private static final int MAX_COUNT = 1024;
	private static final int MAX_SEARCH_BUDGET = 10000;
	private static final Set<String> REQUIRED = Set.of("RenderLibrarian", "VillagerType", "LibrarianLevel",
		"StructureState", "PendingSafeRelease", "LogicalMembers", "OrdinaryBookshelves",
		"ChiseledBookshelves", "SearchBudget", "QueueCount");
	private static final String CUSTOM_NAME = "CustomName";

	public RecipeLibraryClientView {
		if (villagerType == null || structureState == null || librarianLevel < 1 || librarianLevel > 5
			|| logicalMembers < 0 || logicalMembers > MAX_COUNT || ordinaryBookshelves < 0
			|| ordinaryBookshelves > MAX_COUNT || chiseledBookshelves < 0 || chiseledBookshelves > MAX_COUNT
			|| searchBudget < 0 || searchBudget > MAX_SEARCH_BUDGET || queueCount < 0 || queueCount > MAX_COUNT
			|| (customName != null && customName.codePointCount(0, customName.length()) > 64))
			throw new IllegalArgumentException("Invalid recipe-library client view");
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

	public static Optional<RecipeLibraryClientView> load(CompoundTag tag) {
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
			State state = State.valueOf(tag.getString("StructureState"));
			BuiltInRegistries.VILLAGER_TYPE.getKey(VillagerType.PLAINS);
			if (type == null || !BuiltInRegistries.VILLAGER_TYPE.containsKey(type))
				return Optional.empty();
			return Optional.of(new RecipeLibraryClientView(tag.getBoolean("RenderLibrarian"), type,
				tag.getInt("LibrarianLevel"), tag.contains(CUSTOM_NAME) ? tag.getString(CUSTOM_NAME) : null,
				state, tag.getBoolean("PendingSafeRelease"), tag.getInt("LogicalMembers"),
				tag.getInt("OrdinaryBookshelves"), tag.getInt("ChiseledBookshelves"),
				tag.getInt("SearchBudget"), tag.getInt("QueueCount")));
		} catch (IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}

	/** Capture only public values; retaining a layout during PARTIAL does not make it usable. */
	public static RecipeLibraryClientView capture(LibraryResidentCapture.Projection librarian, State state,
		boolean pendingSafeRelease, @Nullable LibraryStructureScanner.Snapshot layout, int searchBudget, int queueCount) {
		return new RecipeLibraryClientView(librarian.valid(), librarian.type(), librarian.level(), librarian.customName(),
			state, pendingSafeRelease, layout == null ? 0 : Math.min(MAX_COUNT, layout.members().size()),
			layout == null ? 0 : Math.min(MAX_COUNT, layout.ordinaryShelves().size()),
			layout == null ? 0 : Math.min(MAX_COUNT, layout.mediaShelves().size()),
			Math.min(MAX_SEARCH_BUDGET, searchBudget), Math.min(MAX_COUNT, queueCount));
	}

	/** Keep the renderer independent of recipe diagnostics and their wire protocol. */
	public LibraryResidentCapture.Projection residentView() {
		return new LibraryResidentCapture.Projection(renderLibrarian, villagerType, librarianLevel, customName);
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
