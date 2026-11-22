package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeShelfSource;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryShelfUpdates;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.level.ChunkEvent;

@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BiocomputingRecipeShelfSourceGameTests {
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void physicalUpdatesRefreshRecipeParametersOnTheNextTick(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.runAfterDelay(85, () -> {
            helper.assertValueEqual(f.core.queueCount(), 1, "Settled library has no ordinary shelves");
            helper.getLevel().setBlock(f.pos.north(), Blocks.BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
            helper.runAfterDelay(1, () -> {
                helper.assertValueEqual(f.core.queueCount(), 2, "Physical update must refresh parameters on the next tick, not the old poll");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void aFreshExplicitScanAcknowledgesPrecedingPhysicalUpdates(GameTestHelper helper) {
        var f = new Fixture(helper);
        try (var source = new RecipeShelfSource(helper.getLevel(), f.pos)) {
            source.scan(32, 16);
            helper.assertTrue(!source.needsRefresh(32, 16), "Fresh source is clean");
            helper.getLevel().setBlock(f.pos.north(), Blocks.BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
            var result = source.scan(32, 16);
            helper.assertValueEqual(result.structure().snapshot().ordinaryShelves().size(), 1, "Explicit scan already includes the update");
            helper.assertTrue(!source.needsRefresh(32, 16), "A completed fresh scan must not replay the old notification");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void replacingAnOccupiedBookRefreshesBudgetWithoutRestartingIndex(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.runAfterDelay(85, () -> {
            long generation = f.core.diagnosticGeneration();
            f.shelf.setItem(5, efficiencyBook(helper, 3));
            helper.runAfterDelay(1, () -> {
                helper.assertValueEqual(f.core.searchBudget(), Math.min(CBConfigs.SERVER.library.libraryScanBatch.get(), 18), "Efficiency III budget on next tick");
                var occupiedState = helper.getLevel().getBlockState(f.shelf.getBlockPos());
                f.shelf.setItem(5, efficiencyBook(helper, 4));
                helper.assertValueEqual(helper.getLevel().getBlockState(f.shelf.getBlockPos()), occupiedState, "Replacement keeps occupied block state unchanged");
                helper.runAfterDelay(1, () -> {
                    helper.assertValueEqual(f.core.searchBudget(), Math.min(CBConfigs.SERVER.library.libraryScanBatch.get(), 25), "BLOCK_CHANGE refreshes Efficiency IV budget");
                    helper.assertValueEqual(f.core.diagnosticGeneration(), generation, "Non-recipe metadata does not rebuild the index");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void sourceRefreshIsScopedAndRecoversAcrossShelfReconnectAndConfigChange(GameTestHelper helper) {
        var f = new Fixture(helper);
        var source = new RecipeShelfSource(helper.getLevel(), f.pos);
        source.scan(32, 16);
        helper.getLevel().setBlock(f.pos.west(3), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(!source.needsRefresh(32, 16), "Unrelated world update does not dirty source");
        helper.runAfterDelay(40, () -> {
            try (source) {
                helper.assertTrue(!source.needsRefresh(32, 16), "Idle ticks do not cause a timer scan");
                helper.getLevel().setBlock(f.pos.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                helper.assertTrue(source.needsRefresh(32, 16), "Removal observed");
                helper.assertTrue(source.scan(32, 16).structure().snapshot().mediaShelves().isEmpty(), "Removed shelf no longer admitted");
                helper.getLevel().setBlock(f.pos.east(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
                helper.assertTrue(source.needsRefresh(32, 16), "Reconnect observed at retained boundary");
                helper.assertValueEqual(source.scan(32, 16).structure().snapshot().mediaShelves().size(), 1, "Reconnected physical shelf admitted");
                helper.assertTrue(source.needsRefresh(1, 16), "Changed scan limit invalidates source");
                helper.assertValueEqual(source.scan(1, 16).structure().state(), LibraryStructureScanner.State.TOO_LARGE, "Changed config applied");
                helper.assertTrue(!source.needsRefresh(1, 16), "Invalid geometry waits for physical or config change");
                helper.assertTrue(source.needsRefresh(32, 16), "Restored config invalidates again");
                source.scan(32, 16); source.invalidate();
                helper.assertTrue(source.needsRefresh(32, 16), "Explicit invalidation honored");
                source.scan(32, 16);
            }
            source.close();
            boolean rejected = false;
            try { source.needsRefresh(32, 16); } catch (IllegalStateException expected) { rejected = true; }
            helper.assertTrue(rejected, "Closed source cannot be reused");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void missingShelfRetriesAndUpdatesDuringReadAreNotLost(GameTestHelper helper) {
        var f = new Fixture(helper);
        var level = helper.getLevel();
        level.removeBlockEntity(f.pos.east());
        try (var source = new RecipeShelfSource(level, f.pos)) {
            var missing = source.scan(32, 16);
            helper.assertValueEqual(missing.structure().state(), LibraryStructureScanner.State.VALID, "Blocks still form valid geometry");
            helper.assertTrue(!missing.books().complete() && source.needsRefresh(32, 16), "Missing entity remains incomplete and retries without another event");
            var notifying = new ChiseledBookShelfBlockEntity(f.pos.east(), level.getBlockState(f.pos.east())) {
                private boolean notify = true;
                @Override public ItemStack getItem(int slot) {
                    if (notify) { notify = false; LibraryShelfUpdates.changed(level, getBlockPos()); }
                    return super.getItem(slot);
                }
            };
            level.setBlockEntity(notifying);
            helper.assertTrue(source.scan(32, 16).books().complete(), "Entity repair is read without a new player action");
            helper.assertTrue(source.needsRefresh(32, 16), "Notification during the scan is retained");
            source.scan(32, 16);
            helper.assertTrue(!source.needsRefresh(32, 16), "Next completed observation is clean");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void chunkNotificationsInvalidateWithoutForceLoadingUnknownChunks(GameTestHelper helper) {
        var f = new Fixture(helper);
        var level = helper.getLevel();
        try (var source = new RecipeShelfSource(level, f.pos)) {
            source.scan(32, 16);
            var chunk = level.getChunkAt(f.pos);
            LibraryShelfUpdates.onChunkUnload(new ChunkEvent.Unload(chunk));
            helper.assertTrue(source.needsRefresh(32, 16), "Chunk unload notification invalidates source");
            source.scan(32, 16);
            LibraryShelfUpdates.onChunkLoad(new ChunkEvent.Load(chunk, false));
            helper.assertTrue(source.needsRefresh(32, 16), "Chunk load notification invalidates source");
        }
        var absent = new BlockPos(29_000_000, 64, 29_000_000);
        helper.assertTrue(!level.hasChunkAt(absent), "Distant fixture starts unloaded");
        try (var source = new RecipeShelfSource(level, absent)) {
            helper.assertValueEqual(source.scan(32, 16).structure().state(), LibraryStructureScanner.State.PARTIAL, "Unloaded is not empty or valid");
            helper.assertTrue(!level.hasChunkAt(absent), "Inspection never force loads a chunk");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void loadedHostRevalidatesPhysicalSourceAfterArchiveReplacementAndRecreation(GameTestHelper helper) {
        var f = new Fixture(helper);
        var level = helper.getLevel();
        helper.runAfterDelay(85, () -> {
            var oldArchive = f.core.saveWithFullMetadata(level.registryAccess());
            level.setBlock(f.pos.north(), Blocks.BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
            f.core.tick();
            helper.assertValueEqual(f.core.queueCount(), 2, "Physical shelf acknowledged before archive replacement");
            f.core.loadWithComponents(oldArchive, level.registryAccess());
            f.core.tick();
            helper.assertValueEqual(f.core.queueCount(), 2, "Old saved geometry cannot leave a clean source after loading");
            var currentArchive = f.core.saveWithFullMetadata(level.registryAccess());
            f.core.onChunkUnloaded();
            level.removeBlockEntity(f.pos);
            level.setBlock(f.pos.north(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            var recreated = new LibraryBlockEntity(f.pos, level.getBlockState(f.pos));
            level.setBlockEntity(recreated);
            recreated.loadWithComponents(currentArchive, level.registryAccess()); recreated.initialize(); recreated.tick();
            helper.assertValueEqual(recreated.queueCount(), 1, "Recreated host reads actual shelves rather than archived geometry");
            helper.succeed();
        });
    }

    private static ItemStack efficiencyBook(GameTestHelper helper, int level) {
        var book = new ItemStack(Items.ENCHANTED_BOOK);
        var enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(helper.getLevel().registryAccess().holderOrThrow(Enchantments.EFFICIENCY), level);
        book.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
        return book;
    }

    private static final class Fixture {
        final BlockPos pos;
        final LibraryBlockEntity core;
        final ChiseledBookShelfBlockEntity shelf;
        Fixture(GameTestHelper helper) {
            var level = helper.getLevel(); pos = helper.absolutePos(new BlockPos(3, 2, 3));
            var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState();
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos, state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
            level.setBlock(pos.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
            core = (LibraryBlockEntity)level.getBlockEntity(pos); core.initialize();
            level.setBlock(pos.east(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
            shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(pos.east());
        }
    }
}
