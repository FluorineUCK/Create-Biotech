package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreHost;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryShelfReader;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureWorldView;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;

/** Manual, authorized recipe-page operation; deliberately absent from the generic storage protocol. */
public final class RecipeBookEditing {
    private RecipeBookEditing() {}

    /** Live session permission and the one authoritative recipe index supplied by its owner. */
    public interface Access {
        boolean mayEdit(ServerPlayer player);
        RecipeIndexSnapshot snapshot();
        List<PatternPageKey> pageOrder();
        boolean structureKnown();
        void pagesChanged(List<PatternPageKey> pages);
    }

    public static String create(SmartBlockEntity owner, ServerPlayer player, Access access,
        long expectedGeneration, String json) {
        if (!authorized(owner, player, access)) return "denied";
        var snapshot = access.snapshot();
        if (snapshot.reindexing() || snapshot.generation() != expectedGeneration) return "stale_generation";
        var level = (ServerLevel)owner.getLevel();
        var physical = scan(owner, access);
        if (physical == null) return "conflict";
        // Keep the existing deterministic manual page-selection order. This is not XBus arbitration.
        var positions = physical.mediaShelves().stream().sorted(Comparator.<BlockPos>comparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ)).toList();
        var shelves = new LinkedHashMap<BlockPos, ChiseledBookShelfBlockEntity>();
        var pages = new ArrayList<PatternPageKey>();
        for (var pos : positions) {
            if (!(SubLevelCompat.getLoadedBlockEntity(level, pos) instanceof ChiseledBookShelfBlockEntity shelf)) return "conflict";
            shelves.put(pos, shelf);
            pages.addAll(RecipeBookPages.pageKeys(address(level, pos), LibraryShelfReader.copyContents(shelf)));
        }
        for (var entry : shelves.entrySet()) {
            var shelf = entry.getValue();
            var slots = LibraryShelfReader.copyContents(shelf);
            for (int slot = 0; slot < slots.size(); slot++) {
                var book = slots.get(slot);
                if (!book.is(Items.WRITABLE_BOOK)) continue;
                var content = book.get(DataComponents.WRITABLE_BOOK_CONTENT);
                if (content == null) continue;
                int page = -1;
                for (int candidate = 0; candidate < content.pages().size(); candidate++)
                    if (content.pages().get(candidate).raw().isBlank()) { page = candidate; break; }
                if (page < 0 && content.pages().size() < WritableBookContent.MAX_PAGES) page = content.pages().size();
                if (page < 0) continue;
                var target = new PatternPageKey(address(level, entry.getKey()), slot, page);
                var result = RecipeBookPages.createPageJson(book, target, json, player.registryAccess());
                if (!result.resultKey().equals("success")) return result.resultKey();
                shelf.setItem(slot, result.book());
                shelf.setChanged();
                access.pagesChanged(List.copyOf(pages));
                return "success";
            }
        }
        return "no_capacity";
    }

    public static String replace(SmartBlockEntity owner, ServerPlayer player, Access access,
        UUID patternId, long expectedGeneration, String json) {
        if (!authorized(owner, player, access)) return "denied";
        var snapshot = access.snapshot();
        if (snapshot.reindexing() || snapshot.generation() != expectedGeneration) return "stale_generation";
        var original = snapshot.records().stream().filter(pattern -> pattern.patternId().equals(patternId)).findFirst().orElse(null);
        var physical = scan(owner, access);
        if (original == null || physical == null || !access.pageOrder().contains(original.source())) return "conflict";
        var source = original.source();
        var level = (ServerLevel)owner.getLevel();
        if (!source.shelf().equals(address(level, source.shelf().localPos()))
            || !physical.mediaShelves().contains(source.shelf().localPos())) return "conflict";
        if (!(SubLevelCompat.getLoadedBlockEntity(level, source.shelf().localPos()) instanceof ChiseledBookShelfBlockEntity shelf)
            || source.slot() >= shelf.getContainerSize()) return "conflict";
        var current = LibraryShelfReader.copyContents(shelf);
        var raw = RecipeBookPages.readPage(current, source);
        var parsed = new PatternJsonParser(player.registryAccess()).parse(source, raw);
        if (!(parsed instanceof ParseResult.Valid valid) || !valid.pattern().patternId().equals(patternId)) return "conflict";
        var result = RecipeBookPages.replacePageJson(current.get(source.slot()), source, raw, json, player.registryAccess());
        if (!result.resultKey().equals("success")) return result.resultKey();
        shelf.setItem(source.slot(), result.book());
        shelf.setChanged();
        access.pagesChanged(access.pageOrder());
        return "success";
    }

    private static boolean authorized(SmartBlockEntity owner, ServerPlayer player, Access access) {
        return owner.getLevel() instanceof ServerLevel level && player.level() == level
            && !owner.isRemoved() && level.isLoaded(owner.getBlockPos())
            && level.getBlockEntity(owner.getBlockPos()) == owner
            && owner instanceof LibraryCoreHost host && host.libraryIdentityReady()
            && access.mayEdit(player);
    }

    private static LibraryStructureScanner.Snapshot scan(SmartBlockEntity owner, Access access) {
        if (!access.structureKnown()) return null;
        var scan = LibraryStructureScanner.scan(new LibraryStructureWorldView(owner.getLevel()), owner.getBlockPos(),
            CBConfigs.SERVER.library.libraryMaxMembers.get(), CBConfigs.SERVER.library.libraryMaxSpan.get());
        return scan.state() == LibraryStructureScanner.State.VALID ? scan.snapshot() : null;
    }

    private static RecipeSourceLocation address(ServerLevel level, BlockPos pos) {
        return new RecipeSourceLocation(level.dimension(), SubLevelCompat.getSpaceId(level, pos), pos);
    }
}
