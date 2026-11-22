package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.*;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Legacy lectern path under migration: no production binding is seeded to hide the local-index gate. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingLecternIndexGameTests {
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void unboundLecternPublishesPhysicalBookAndRemoval(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(3, 1, 3));
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState();
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(pos.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var core = (LibraryBlockEntity) level.getBlockEntity(pos);
        helper.assertTrue(core.applyPlacementData(LibraryCoreItemData.configured(
            CommunicationFrequency.of("local-index-native"), 0, UUID.randomUUID(), 0)), "Configured physical core");
        level.setBlock(pos.east(), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        var shelf = (ChiseledBookShelfBlockEntity) level.getBlockEntity(pos.east());
        String raw = "{\"version\":2,\"type\":\"standard\",\"inputs\":[],\"outputs\":[{\"item\":\"minecraft:paper\",\"count\":1}],\"address\":\"local-native\"}";
        var book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough(raw))));
        shelf.setItem(2, book);
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(!core.saveWithoutMetadata(core.getLevel().registryAccess()).getCompound("PatternLibrary").contains("BindingState"), "Local indexing must not create a production binding");
            helper.assertValueEqual(core.recipeSnapshot().records().size(), 1, "Unbound lectern must publish its real book");
            helper.assertFalse(core.recipeSnapshot().reindexing(), "Local generation completed");
            var snapshot = core.recipeSnapshot();
            var beforeReads = core.saveWithFullMetadata(level.registryAccess());
            var address = core.memberAddress();
            var fence = new BrowserFence(UUID.randomUUID(), new RecipeSourceLocation(address.dimension(), address.subLevelId(), address.localPos()),
                core.memberId(), snapshot.generation());
            var browser = core.browserService(fence);
            for (int i = 0; i < 20; i++) {
                var page = browser.page(fence, PatternBrowserFilter.EMPTY, 0, java.util.Optional.of(snapshot.records().getFirst().patternId()));
                helper.assertValueEqual(page.status(), PatternBrowserPage.Status.OK, "Real core supplies the new pure browser");
                helper.assertTrue(page.detail().orElseThrow().pattern() == snapshot.records().getFirst(), "No second recipe index or full-catalog copy");
            }
            helper.assertValueEqual(core.saveWithFullMetadata(level.registryAccess()), beforeReads, "Repeated browser reads cannot advance index, queries or production");
            helper.assertValueEqual(shelf.getItem(2).get(DataComponents.WRITABLE_BOOK_CONTENT).pages().getFirst().raw(), raw,
                "Read-only indexing preserves the physical handwritten page");
            ItemStack taken = shelf.removeItem(2, 1);
            helper.runAfterDelay(40, () -> {
                helper.assertValueEqual(core.recipeSnapshot().records().size(), 0, "Taking the medium removes its catalog entry");
                helper.assertFalse(core.recipeSnapshot().reindexing(), "Removal generation completed");
                var stale = browser.page(fence, PatternBrowserFilter.EMPTY, 0);
                helper.assertValueEqual(stale.status(), PatternBrowserPage.Status.STALE, "Existing service observes the new generation rather than a captured old cache");
                helper.assertValueEqual(browser.page(stale.fence(), PatternBrowserFilter.EMPTY, 0).totalMatches(), 0, "Explicit refresh cannot resurrect the removed medium");
                helper.assertValueEqual(taken.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().getFirst().raw(), raw,
                    "Taken book retains its data");
                helper.assertTrue(!core.saveWithoutMetadata(core.getLevel().registryAccess()).getCompound("PatternLibrary").contains("BindingState"), "No implicit production membership");
                helper.succeed();
            });
        });
    }
}
