package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeLibraryArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipePageIndex;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Historical page/query wire composition only; not a runtime owner or general Library storage. */
public final class ProductionLibraryIndexArchive {
    private static final String PAGE_ORDER = "PageOrder", CACHE = "Cache", FINGERPRINT_CURSOR = "FingerprintCursor",
        GENERATION = "Generation", COMPLETE = "Complete", QUERIES = "Queries", REPLIES = "Replies",
        REPLY_REQUESTER_CURSOR = "ReplyRequesterCursor";

    public static final RecipeLibraryArchive.IndexCodec<ProductionLibraryQueries> CODEC = new RecipeLibraryArchive.IndexCodec<>() {
        @Override public boolean hasPriorShape(CompoundTag tag) { return validRoot(tag, false); }
        @Override public RecipeLibraryArchive.LoadedIndex<ProductionLibraryQueries> load(CompoundTag tag, HolderLookup.Provider registries) {
            return ProductionLibraryIndexArchive.load(tag, registries);
        }
    };

    private ProductionLibraryIndexArchive() {}

    /** Save the exact index referenced by these queries: callers cannot supply a mismatched pair. */
    public static CompoundTag save(ProductionLibraryQueries queries, HolderLookup.Provider registries) {
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(registries, "registries");
        CompoundTag root = queries.pageData().save(registries);
        CompoundTag queueArchive = queries.save(registries);
        queueArchive.getAllKeys().forEach(key -> root.put(key, queueArchive.get(key).copy()));
        return root;
    }

    public static RecipeLibraryArchive.LoadedIndex<ProductionLibraryQueries> load(CompoundTag root, HolderLookup.Provider registries) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(registries, "registries");
        try {
            if (!validRoot(root, true)) return discarded();
            CompoundTag dataRoot = root.copy();
            dataRoot.remove(QUERIES); dataRoot.remove(REPLIES); dataRoot.remove(REPLY_REQUESTER_CURSOR);
            var restored = RecipePageIndex.load(dataRoot, registries);
            if (restored.recovery() == RecipePageIndex.Recovery.DISCARDED) return discarded();
            var queueArchive = new CompoundTag();
            for (String key : List.of(QUERIES, REPLIES, REPLY_REQUESTER_CURSOR))
                if (root.contains(key)) queueArchive.put(key, root.get(key).copy());
            var queries = ProductionLibraryQueries.load(queueArchive, registries, restored.index(),
                root.getLong(GENERATION), restored.recovery() == RecipePageIndex.Recovery.REINDEXED);
            return new RecipeLibraryArchive.LoadedIndex<>(queries.queries(), restored.index(),
                restored.hadCorruption() || queries.hadCorruption());
        } catch (RuntimeException exception) {
            return discarded();
        }
    }

    private static RecipeLibraryArchive.LoadedIndex<ProductionLibraryQueries> discarded() {
        var data = new RecipePageIndex();
        return new RecipeLibraryArchive.LoadedIndex<>(new ProductionLibraryQueries(data), data, true);
    }

    private static boolean validRoot(CompoundTag root, boolean allowRequesterCursor) {
        if (!allowRequesterCursor && root.contains(REPLY_REQUESTER_CURSOR)) return false;
        Set<String> expected = new HashSet<>(Set.of(PAGE_ORDER, CACHE, FINGERPRINT_CURSOR,
            GENERATION, COMPLETE, QUERIES, REPLIES));
        if (root.contains(REPLY_REQUESTER_CURSOR)) expected.add(REPLY_REQUESTER_CURSOR);
        return root.getAllKeys().equals(expected)
            && hasType(root, PAGE_ORDER, Tag.TAG_LIST) && hasType(root, CACHE, Tag.TAG_LIST)
            && hasType(root, FINGERPRINT_CURSOR, Tag.TAG_INT) && hasType(root, GENERATION, Tag.TAG_LONG)
            && hasType(root, COMPLETE, Tag.TAG_BYTE) && hasType(root, QUERIES, Tag.TAG_LIST)
            && hasType(root, REPLIES, Tag.TAG_LIST)
            && (!root.contains(REPLY_REQUESTER_CURSOR) || root.hasUUID(REPLY_REQUESTER_CURSOR))
            && (root.getByte(COMPLETE) == 0 || root.getByte(COMPLETE) == 1)
            && isCompoundList((ListTag) root.get(PAGE_ORDER)) && isCompoundList((ListTag) root.get(CACHE))
            && isCompoundList((ListTag) root.get(QUERIES)) && isCompoundList((ListTag) root.get(REPLIES));
    }

    private static boolean isCompoundList(ListTag list) {
        return list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND;
    }

    private static boolean hasType(CompoundTag tag, String key, int type) {
        Tag value = tag.get(key);
        return value != null && value.getId() == type;
    }
}
