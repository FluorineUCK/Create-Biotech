package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** The recipe index is live; historical requests are disk-only data, never a job or a search capability. */
public final class RecipeIndexStorage {
    public static final String RETIRED = "RetiredProductionSearch";
    private static final Set<String> QUEUE_KEYS = Set.of("Queries", "Replies", "ReplyRequesterCursor");
    private static final Set<String> PRIOR_KEYS = Set.of("PageOrder", "Cache", "FingerprintCursor", "Generation", "Complete", "Queries", "Replies");
    public static final RecipeIndexStorage EMPTY = new RecipeIndexStorage(new CompoundTag());
    private final CompoundTag frozen;

    private RecipeIndexStorage(CompoundTag frozen) { this.frozen = frozen; }

    public CompoundTag save(RecipePageIndex live, HolderLookup.Provider registries) {
        CompoundTag tag = live.save(registries);
        if (!frozen.isEmpty()) tag.put(RETIRED, frozen.copy());
        return tag;
    }

    public static final RecipeLibraryArchive.IndexCodec<RecipeIndexStorage> CODEC = new RecipeLibraryArchive.IndexCodec<>() {
        @Override public boolean accepts(CompoundTag tag) {
            return !tag.contains(RETIRED) || (tag.contains(RETIRED, Tag.TAG_COMPOUND)
                && QUEUE_KEYS.stream().noneMatch(tag::contains));
        }

        @Override public boolean hasPriorShape(CompoundTag tag) {
            return tag.getAllKeys().equals(PRIOR_KEYS) && compoundList(tag.get("PageOrder")) && compoundList(tag.get("Cache"))
                && tag.contains("FingerprintCursor", Tag.TAG_INT) && tag.contains("Generation", Tag.TAG_LONG)
                && tag.contains("Complete", Tag.TAG_BYTE) && (tag.getByte("Complete") == 0 || tag.getByte("Complete") == 1)
                && compoundList(tag.get("Queries")) && compoundList(tag.get("Replies"));
        }

        @Override public RecipeLibraryArchive.LoadedIndex<RecipeIndexStorage> load(CompoundTag tag, HolderLookup.Provider registries) {
            CompoundTag history = new CompoundTag();
            if (tag.contains(RETIRED)) history = tag.getCompound(RETIRED).copy();
            else {
                for (String key : QUEUE_KEYS) if (tag.contains(key)) history.put(key, tag.get(key).copy());
                if (!history.isEmpty() && tag.contains("Generation")) history.put("Generation", tag.get("Generation").copy());
            }
            // Only page fields go to the data codec. Never decode, iterate or restart retired jobs on a page change.
            var pages = new CompoundTag();
            for (String key : tag.getAllKeys())
                if (!key.equals(RETIRED) && !QUEUE_KEYS.contains(key)) pages.put(key, tag.get(key));
            var data = RecipePageIndex.load(pages, registries);
            return new RecipeLibraryArchive.LoadedIndex<>(history.isEmpty() ? EMPTY : new RecipeIndexStorage(history),
                data.index(), data.hadCorruption());
        }
    };

    private static boolean compoundList(Tag tag) {
        return tag instanceof ListTag list && (list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND);
    }
}
