package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryShelfReader;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.List;
import java.util.Objects;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Physical substrate only: this does not create the pending generic Library storage device. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingLibraryShelfGameTests {
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void actualBookSlotsRemainDetachedAcrossRemovalAndShelfReload(GameTestHelper helper) {
        var level = helper.getLevel();
        // All fixtures fit within the existing 20 x 12 x 12 template; no player save is used.
        var core = helper.absolutePos(new BlockPos(2, 1, 2));
        var shelfPos = core.east();
        var coreState = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState();
        level.setBlock(core, coreState, Block.UPDATE_ALL);
        level.setBlock(core.above(), coreState.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        level.setBlock(shelfPos, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        var shelf = (ChiseledBookShelfBlockEntity)Objects.requireNonNull(level.getBlockEntity(shelfPos));
        var book = new ItemStack(Items.WRITABLE_BOOK);
        var pages = new WritableBookContent(List.of(Filterable.passThrough("Original handwritten page")));
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, pages);
        shelf.setItem(2, book);

        var captured = LibraryShelfReader.read(level, core, shelfPos);
        helper.assertValueEqual(captured.status(), LibraryShelfReader.Status.OK, "Loaded actual shelf read");
        var snapshot = captured.snapshot().orElseThrow();
        helper.assertValueEqual(snapshot.items().size(), 6, "All six physical slots remain present");
        snapshot.items().get(2).set(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
        helper.assertValueEqual(shelf.getItem(2).get(DataComponents.WRITABLE_BOOK_CONTENT), pages, "Snapshot editing cannot change the source book");

        var taken = shelf.removeItem(2, 1);
        helper.assertTrue(LibraryShelfReader.read(level, core, shelfPos).snapshot().orElseThrow().items().get(2).isEmpty(), "A fresh read sees actual removal");
        helper.assertValueEqual(snapshot.items().get(2).get(DataComponents.WRITABLE_BOOK_CONTENT), pages, "Historical value stays detached");
        shelf.setItem(5, taken);
        var saved = shelf.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(shelfPos);
        helper.assertValueEqual(LibraryShelfReader.read(level, core, shelfPos).status(), LibraryShelfReader.Status.MISSING, "Missing shelf is not an empty successful read");
        var restored = Objects.requireNonNull(BlockEntity.loadStatic(shelfPos, level.getBlockState(shelfPos), saved, level.registryAccess()));
        level.setBlockEntity(restored);
        var after = LibraryShelfReader.read(level, core, shelfPos).snapshot().orElseThrow();
        helper.assertTrue(after.items().get(2).isEmpty(), "The vacated slot stays empty after NBT reload");
        helper.assertValueEqual(after.items().get(5).get(DataComponents.WRITABLE_BOOK_CONTENT), pages, "Book contents follow the actual moved medium and saved shelf");
        helper.succeed();
    }
}
