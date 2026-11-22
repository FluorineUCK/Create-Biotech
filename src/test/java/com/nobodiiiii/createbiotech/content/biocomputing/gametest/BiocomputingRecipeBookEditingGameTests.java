package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeIndexSnapshot;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternCreateRequestPacket;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternEditRequestPacket;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BiocomputingRecipeBookEditingGameTests {
    private static final String JSON = """
        {"version":2,"type":"standard","inputs":[],
         "outputs":[{"item":"minecraft:paper","count":1}],"address":"new-editor"}
        """;

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void recipeApplicationCreatesInPhysicalBookWithoutOwningAGenericDataIndex(GameTestHelper helper) {
        var f = new Fixture(helper);
        var before = f.shelf.getItem(2).copy();
        helper.assertValueEqual(RecipeBookEditing.create(f.core, f.player, f, 0, JSON), "success",
            "Application service must create without the old host's production or browser state");
        var after = f.shelf.getItem(2);
        var pages = after.get(DataComponents.WRITABLE_BOOK_CONTENT);
        helper.assertValueEqual(pages.pages().getFirst().raw(), "human notes", "Existing page is preserved");
        helper.assertValueEqual(pages.pages().get(1).raw(), JSON, "Append reaches the real shelf");
        helper.assertValueEqual(after.get(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get()),
            before.get(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get()), "Library entries and revisions remain exact");
        helper.assertValueEqual(f.publications, 1, "Only one successful edit invalidates the authoritative index");
        helper.assertValueEqual(f.pages.size(), WritableBookContent.MAX_PAGES, "The existing page-index range is retained");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void replacementUsesIndexedIdentityButWritesOnlyTheCurrentPhysicalSource(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.assertValueEqual(RecipeBookEditing.create(f.core, f.player, f, 0, JSON), "success", "Seed through the public service");
        var key = f.pages.get(1);
        var parsed = (ParseResult.Valid)new PatternJsonParser(helper.getLevel().registryAccess()).parse(key, JSON);
        f.snapshot = new RecipeIndexSnapshot(1, false, List.of(parsed.pattern()), 0, 0, List.of());
        var before = f.shelf.getItem(2).copy();
        var edited = JSON.replace("minecraft:paper", "minecraft:diamond");
        helper.assertValueEqual(RecipeBookEditing.replace(f.core, f.player, f, parsed.pattern().patternId(), 1, edited),
            "success", "Independent recipe application resolves the indexed source itself");
        var after = f.shelf.getItem(2);
        helper.assertValueEqual(RecipeBookPages.readPage(LibraryShelfReader.copyContents(f.shelf), key), edited, "Exact original page replaced");
        helper.assertValueEqual(after.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().getFirst().raw(), "human notes", "Other page untouched");
        helper.assertValueEqual(after.get(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get()),
            before.get(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get()), "Data entries and version tokens untouched");
        helper.assertValueEqual(f.publications, 2, "One invalidation for each edit");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void rejectedLibraryIdentityCannotCreateThroughTheActualManualMenu(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(!f.core.recipeSnapshot().reindexing(), "Original index has completed");
            var level = helper.getLevel(); var pos = f.core.getBlockPos().north(3);
            var saved = f.core.saveWithFullMetadata(level.registryAccess());
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos, f.core.getBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos.above(), level.getBlockState(f.core.getBlockPos().above()), Block.UPDATE_ALL);
            var copy = (LibraryBlockEntity)level.getBlockEntity(pos);
            copy.loadWithComponents(saved, level.registryAccess()); copy.initialize();
            level.setBlock(pos.west(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
            var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.west());
            var book = new ItemStack(Items.WRITABLE_BOOK);
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough(""))));
            shelf.setItem(0, book.copy());
            f.player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
            helper.assertTrue(f.core.libraryIdentityReady() && !copy.libraryIdentityReady(), "Only the original identity is admitted");
            helper.assertTrue(copy.canPlayerEdit(f.player), "Nearby build permission alone is not identity ownership");
            var menu = new PatternStorageCoreMenu(9, f.player.getInventory(), copy);
            f.player.containerMenu = menu;
            helper.assertValueEqual(copy.createPattern(f.player, menu.openingFence(), JSON), "denied",
                "Rejected identity must not mutate physical recipe books through a manual session");
            helper.assertTrue(ItemStack.matches(book, shelf.getItem(0)), "Rejected owner leaves the physical book exact");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void rejectedEditsNeverTouchBooksOrPublishAnIndexChange(GameTestHelper helper) {
        var f = new Fixture(helper); var before = f.shelf.getItem(2).copy();
        helper.assertValueEqual(RecipeBookEditing.create(f.core, f.player, f, 0, "{"), "invalid", "Malformed recipe rejected");
        helper.assertValueEqual(RecipeBookEditing.create(f.core, f.player, f, 9, JSON), "stale_generation", "Wrong generation rejected");
        f.allowed = false;
        helper.assertValueEqual(RecipeBookEditing.create(f.core, f.player, f, 0, JSON), "denied", "Revoked session rejected");
        f.allowed = true; f.snapshot = f.snapshot.withReindexing(true);
        helper.assertValueEqual(RecipeBookEditing.create(f.core, f.player, f, 0, JSON), "stale_generation", "Incomplete index rejected");
        helper.assertTrue(ItemStack.matches(before, f.shelf.getItem(2)), "All failures preserve the exact source stack");
        f.snapshot = f.snapshot.withReindexing(false);
        var full = before.copy(); full.set(DataComponents.WRITABLE_BOOK_CONTENT,
            new WritableBookContent(Collections.nCopies(WritableBookContent.MAX_PAGES, Filterable.passThrough("notes"))));
        f.shelf.setItem(2, full.copy());
        helper.assertValueEqual(RecipeBookEditing.create(f.core, f.player, f, 0, JSON), "no_capacity", "No blank or append position is distinct");
        helper.assertTrue(ItemStack.matches(full, f.shelf.getItem(2)), "Full book is not initialized or truncated");
        helper.assertValueEqual(f.publications, 0, "Rejected requests never publish");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void replacementRejectsChangedOrDetachedMediaAndRemovedOwner(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.assertValueEqual(RecipeBookEditing.create(f.core, f.player, f, 0, JSON), "success", "Seed through service");
        var key = f.pages.get(1);
        var parsed = (ParseResult.Valid)new PatternJsonParser(helper.getLevel().registryAccess()).parse(key, JSON);
        f.snapshot = new RecipeIndexSnapshot(1, false, List.of(parsed.pattern()), 0, 0, List.of());
        var before = f.shelf.getItem(2).copy();
        var changed = RecipeBookPages.replacePageJson(before, key, JSON, JSON.replace("minecraft:paper", "minecraft:diamond"), helper.getLevel().registryAccess()).book();
        f.shelf.setItem(2, changed.copy());
        helper.assertValueEqual(RecipeBookEditing.replace(f.core, f.player, f, parsed.pattern().patternId(), 1, JSON), "conflict", "Unindexed source replacement is detected");
        helper.assertTrue(ItemStack.matches(changed, f.shelf.getItem(2)), "External replacement is not overwritten");
        f.shelf.setItem(2, before.copy());
        helper.getLevel().setBlock(f.core.getBlockPos().east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertValueEqual(RecipeBookEditing.replace(f.core, f.player, f, parsed.pattern().patternId(), 1, JSON), "conflict", "Cached index does not authorize a detached shelf");
        helper.assertTrue(ItemStack.matches(before, f.shelf.getItem(2)), "Detached source remains exact");
        f.core.setRemoved();
        helper.assertValueEqual(RecipeBookEditing.replace(f.core, f.player, f, parsed.pattern().patternId(), 1, JSON), "denied", "Old owner cannot mutate after removal");
        helper.assertValueEqual(f.publications, 1, "Only the original seed was published");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void actualMenuCreatesReplacesReindexesAndRejectsOldSessions(GameTestHelper helper) {
        var f = new Fixture(helper); var before = f.shelf.getItem(2).copy();
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(!f.core.recipeSnapshot().reindexing(), "Physical index completed without a production binding");
            var menu = new PatternStorageCoreMenu(10, f.player.getInventory(), f.core); f.player.containerMenu = menu;
            var create = new PatternCreateRequestPacket(10, menu.openingFence(), 1, JSON);
            create.handle(f.player); create.handle(f.player);
            helper.assertValueEqual(f.shelf.getItem(2).get(DataComponents.WRITABLE_BOOK_CONTENT).pages().size(), 2, "Actual request replay does not append twice");
            helper.runAfterDelay(35, () -> {
                var snapshot = f.core.recipeSnapshot();
                helper.assertTrue(!snapshot.reindexing(), "Actual host has republished its one index");
                var original = snapshot.records().stream().map(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserEntry::new).filter(e -> e.addresses().contains("new-editor")).findFirst().orElseThrow().pattern();
                var editMenu = new PatternStorageCoreMenu(11, f.player.getInventory(), f.core); f.player.containerMenu = editMenu;
                var edited = JSON.replace("minecraft:paper", "minecraft:diamond");
                var edit = new PatternEditRequestPacket(11, editMenu.openingFence(), 1, original.patternId(), edited);
                edit.handle(f.player); edit.handle(f.player);
                helper.assertValueEqual(RecipeBookPages.readPage(LibraryShelfReader.copyContents(f.shelf), original.source()), edited, "Actual edit request reaches the original book");
                helper.assertValueEqual(f.core.createPattern(f.player, menu.openingFence(), JSON), "denied", "Old menu cannot borrow a new session");
                f.player.setGameMode(GameType.SPECTATOR);
                helper.assertValueEqual(f.core.editPattern(f.player, editMenu.openingFence(), original.patternId(), editMenu.openingFence().generation(), JSON), "denied", "Live build permission is still enforced");
                helper.assertValueEqual(f.shelf.getItem(2).get(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get()),
                    before.get(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get()), "Actual menu retains Library data and versions");
                helper.runAfterDelay(35, () -> {
                    helper.assertTrue(!f.core.recipeSnapshot().reindexing(), "Replacement index completes");
                    helper.assertTrue(f.core.recipeSnapshot().records().stream().map(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserEntry::new).anyMatch(e -> e.addresses().contains("new-editor")
                        && !e.patternId().equals(original.patternId())), "Changed recipe is visible through the existing browser index");
                    helper.succeed();
                });
            });
        });
    }

    private static final class Fixture implements RecipeBookEditing.Access {
        final LibraryBlockEntity core;
        final ChiseledBookShelfBlockEntity shelf;
        final FakePlayer player;
        RecipeIndexSnapshot snapshot = new RecipeIndexSnapshot(0, false, List.of(), 0, 0, List.of());
        List<PatternPageKey> pages = List.of();
        int publications;
        boolean allowed = true;
        Fixture(GameTestHelper helper) {
            var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(3, 2, 3));
            var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState();
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos, state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
            level.setBlock(pos.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
            core = (LibraryBlockEntity)level.getBlockEntity(pos); core.initialize();
            level.setBlock(pos.east(), Blocks.BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos.east(2), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
            shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.east(2));
            var book = new ItemStack(Items.WRITABLE_BOOK);
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough("human notes"))));
            var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
            shelf.setItem(2, media.put(book, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43))));
            player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "recipe-editor"));
            player.setGameMode(GameType.SURVIVAL); player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        }
        public boolean mayEdit(ServerPlayer actor) { return allowed && actor == player; }
        public RecipeIndexSnapshot snapshot() { return snapshot; }
        public List<PatternPageKey> pageOrder() { return pages; }
        public boolean structureKnown() { return true; }
        public void pagesChanged(List<PatternPageKey> changed) {
            pages = List.copyOf(changed); publications++;
            snapshot = new RecipeIndexSnapshot(snapshot.generation() + 1, true, snapshot.records(), 0, 0, List.of());
        }
    }
}
