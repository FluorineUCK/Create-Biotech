package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BiocomputingRecipeMaintenanceGameTests {
    private static final String JSON = """
        {"version":2,"type":"standard","inputs":[],
         "outputs":[{"item":"minecraft:paper","count":1}],"address":"maintenance"}
        """;

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void independentMaintenanceReadsRealBooksWithoutProductionCallbacks(GameTestHelper helper) {
        var f = new Fixture(helper);
        var level = helper.getLevel();
        var owner = new RecipeLibraryMaintenance();
        try (var source = new RecipeShelfSource(level, f.pos)) {
            var physical = source.scan(32, 16);
            helper.assertTrue(physical.books().complete(), "Real physical source is readable");
            owner.apply(physical.structure(), physical.books().topologies(), physical.books().efficiencyBonus(), true,
                pos -> new RecipeSourceLocation(level.dimension(), null, pos));
            var reads = new AtomicInteger();
            RecipePageIndex.PageReader pages = key -> {
                reads.incrementAndGet();
                var shelf = LibraryShelfReader.read(level, f.pos, key.shelf().localPos()).snapshot().orElseThrow();
                return RecipeBookPages.readPage(shelf.items(), key);
            };
            var parser = new PatternJsonParser(level.registryAccess());
            owner.advance(true, level::isLoaded, pos -> LibraryShelfReader.read(level, f.pos, pos).snapshot().isPresent(), pages, parser, 1);
            helper.assertValueEqual(reads.get(), 1, "Budget caps actual physical page reads");
            helper.assertTrue(!owner.index().indexPassComplete(), "Incomplete prefix is not a complete catalog");
            var restored = new RecipeLibraryMaintenance();
            var decoded = RecipeMaintenanceArchive.load(RecipeMaintenanceArchive.save(owner, level.registryAccess()),
                level.registryAccess(), 32, null).orElseThrow();
            decoded.commitTo(restored);
            helper.assertTrue(restored.index() == decoded.index(), "Restored owner adopts the only decoded index");
            helper.assertTrue(!restored.advance(true, level::isLoaded, pos -> true, pages, parser, 99), "Saved topology alone is not physical authority");
            restored.apply(physical.structure(), physical.books().topologies(), 0, true,
                pos -> new RecipeSourceLocation(level.dimension(), null, pos));
            restored.advance(true, level::isLoaded, pos -> LibraryShelfReader.read(level, f.pos, pos).snapshot().isPresent(), pages, parser, 99);
            helper.assertValueEqual(reads.get(), 100, "Restore retains cursor and finishes the remaining bounded work");
            helper.assertValueEqual(restored.index().snapshot().records().size(), 1, "New application alone publishes the physical recipe");
            helper.assertTrue(ItemStack.matches(f.book, f.shelf.getItem(2)), "Indexing never mutates recipe pages or generic Library components");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void registeredHostRevalidatesAndReindexesMediaAfterSaveAndRecreation(GameTestHelper helper) {
        var f = new Fixture(helper);
        var level = helper.getLevel();
        helper.runAfterDelay(80, () -> {
            var original = (LibraryBlockEntity)level.getBlockEntity(f.pos);
            helper.assertValueEqual(original.recipeSnapshot().records().size(), 1, "Registered host indexes without a production connection");
            helper.assertTrue(!original.recipeSnapshot().reindexing(), "Initial catalog complete");
            var archive = original.saveWithFullMetadata(level.registryAccess());
            original.loadWithComponents(archive, level.registryAccess()); original.tick();
            helper.assertValueEqual(original.recipeSnapshot().records().size(), 1, "Historical archive retains the one catalog");
            var removed = f.shelf.removeItem(2, 1);
            helper.assertTrue(ItemStack.matches(f.book, removed), "Physical media carries its exact contents out");
            helper.runAfterDelay(2, () -> {
                helper.assertValueEqual(original.recipeSnapshot().records().size(), 0, "Book removal clears authoritative recipe facts");
                var absentArchive = original.saveWithFullMetadata(level.registryAccess());
                original.onChunkUnloaded(); level.removeBlockEntity(f.pos);
                var recreated = new LibraryBlockEntity(f.pos, level.getBlockState(f.pos));
                level.setBlockEntity(recreated);
                recreated.loadWithComponents(absentArchive, level.registryAccess()); recreated.initialize(); recreated.tick();
                helper.assertValueEqual(recreated.recipeSnapshot().records().size(), 0, "Recreation cannot resurrect removed media");
                f.shelf.setItem(2, removed);
                helper.runAfterDelay(40, () -> {
                    helper.assertValueEqual(recreated.recipeSnapshot().records().size(), 1, "Real insertion reindexes in the recreated owner");
                    helper.assertTrue(!recreated.recipeSnapshot().reindexing(), "Replacement catalog completes");
                    helper.assertTrue(ItemStack.matches(f.book, f.shelf.getItem(2)), "Save, removal and recreation preserve the entire physical book");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void actualArchiveRejectsMalformedFieldsAndRevalidatesRecoverableGeometry(GameTestHelper helper) {
        var f = new Fixture(helper); var level = helper.getLevel();
        helper.runAfterDelay(80, () -> {
            var core = (LibraryBlockEntity)level.getBlockEntity(f.pos);
            helper.assertValueEqual(core.recipeSnapshot().records().size(), 1, "Initial actual catalog available");
            var original = core.saveWithFullMetadata(level.registryAccess());
            var invalid = original.copy();
            invalid.getCompound("PatternLibrary").getCompound("ServerState").putInt("StructureState", 1);
            core.loadWithComponents(invalid, level.registryAccess());
            helper.assertValueEqual(core.saveWithFullMetadata(level.registryAccess()).getCompound("PatternLibrary"),
                original.getCompound("PatternLibrary"), "Malformed field cannot partly replace actual persisted state");
            var diagnostic = core.structureSnapshot();
            var damaged = original.copy();
            damaged.getCompound("PatternLibrary").getCompound("ServerState").put("StructureSnapshot", new net.minecraft.nbt.CompoundTag());
            core.loadWithComponents(damaged, level.registryAccess());
            helper.assertTrue(core.diagnosticTopologyRefreshPending(), "Corrupt geometry is gated rather than treated as an empty library");
            helper.assertTrue(core.structureSnapshot() == diagnostic, "Only prior diagnostic geometry is retained");
            helper.assertValueEqual(core.diagnosticGeneration(), original.getCompound("PatternLibrary").getCompound("ServerState")
                .getCompound("PatternIndex").getLong("Generation"), "Valid page continuation is not discarded with corrupt geometry");
            core.tick();
            helper.assertTrue(!core.diagnosticTopologyRefreshPending(), "Actual physical scan revalidates recovery");
            helper.assertValueEqual(core.recipeSnapshot().records().size(), 1, "Recipe remains usable after physical recovery");
            helper.assertTrue(ItemStack.matches(f.book, f.shelf.getItem(2)), "Recovery leaves all book components untouched");
            helper.succeed();
        });
    }

    private static final class Fixture {
        final BlockPos pos;
        final ChiseledBookShelfBlockEntity shelf;
        final ItemStack book;
        Fixture(GameTestHelper helper) {
            var level = helper.getLevel(); pos = helper.absolutePos(new BlockPos(3, 2, 3));
            var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState();
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos, state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
            level.setBlock(pos.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
            level.setBlock(pos.east(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
            shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.east());
            var raw = new ItemStack(Items.WRITABLE_BOOK);
            raw.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough(JSON))));
            var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), CBConfigs.SERVER.biocomputing.current(true).archive());
            book = media.put(raw, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(49)));
            shelf.setItem(2, book.copy());
        }
    }
}
