package com.nobodiiiii.createbiotech.content.biocomputing.gametest;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.registry.CBDataComponents;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingBookComponentGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void physicalBooksKeepPagesAndComponentsAndEmitUpdateEvents(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(3, 1, 3));
        level.setBlock(pos, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        var shelf = (ChiseledBookShelfBlockEntity) level.getBlockEntity(pos);
        var watch = LibraryShelfUpdates.watch(level); watch.observe(Set.of(pos));
        try {
            var limits = new ArchiveLimits(10000, 100000, 100000);
            var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(), limits);
            var volumes = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get());
            var book = new ItemStack(Items.WRITABLE_BOOK);
            var pages = new WritableBookContent(List.of(Filterable.passThrough("human notes"),
                Filterable.passThrough("{\"version\":2,\"type\":\"standard\",\"inputs\":[],\"outputs\":[{\"item\":\"minecraft:paper\",\"count\":1}],\"address\":\"preserve\"}")));
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, pages);
            shelf.setItem(2, book);
            helper.assertTrue(watch.consume(), "Real book insertion must invalidate a subscribed Library");
            var entry = new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.RecordValue(Map.of(
                "count", new DataValue.IntegerValue(42), "name", new DataValue.TextValue("sample"))));
            var written = media.put(shelf.getItem(2), "reading", entry);
            written = volumes.rename(written, "日志");
            shelf.setItem(2, written); shelf.setChanged();
            var versioned = media.stat(written, "reading");
            helper.assertTrue(watch.consume(), "Component-only replacement must notify despite identical occupied-slot states");
            helper.assertTrue(media.read(book).isEmpty(), "Original source stack must not be mutated");
            helper.assertTrue(volumes.name(book).isEmpty(), "Legacy unnamed source must remain unchanged");
            var saved = shelf.saveWithFullMetadata(level.registryAccess());
            level.removeBlockEntity(pos);
            var loaded = (ChiseledBookShelfBlockEntity) BlockEntity.loadStatic(pos, level.getBlockState(pos), saved, level.registryAccess());
            level.setBlockEntity(loaded);
            helper.assertTrue(media.read(loaded.getItem(2)).get("reading").equals(entry), "Registered component must survive real shelf NBT reload");
            helper.assertValueEqual(media.stat(loaded.getItem(2), "reading"), versioned, "Book version survives real shelf reload");
            helper.assertTrue(volumes.name(loaded.getItem(2)).orElseThrow().equals("日志"), "Registered volume name must survive shelf save/load");
            helper.assertTrue(loaded.getItem(2).get(DataComponents.WRITABLE_BOOK_CONTENT).equals(pages), "Original text and recipe pages must be identical");
            var taken = loaded.removeItem(2, 1); loaded.setChanged();
            helper.assertTrue(watch.consume() && loaded.isEmpty(), "Taking the book must invalidate and remove physical data");
            var itemTag = taken.save(level.registryAccess());
            var carried = ItemStack.parse(level.registryAccess(), itemTag).orElseThrow();
            helper.assertTrue(media.read(carried).get("reading").equals(entry), "Taking and serializing the medium must carry its entries");
            helper.assertValueEqual(media.stat(carried, "reading"), versioned, "Version belongs to carried medium, not a core or slot");
            helper.assertTrue(volumes.name(carried).orElseThrow().equals("日志"), "Volume belongs to carried book, not shelf slot");
            loaded.setItem(5, carried); loaded.setChanged();
            helper.assertTrue(watch.consume(), "Reinserting in another slot must invalidate");
            helper.assertTrue(media.read(loaded.getItem(5)).get("reading").equals(entry), "New slot must own carried entries");
            helper.assertTrue(volumes.name(loaded.getItem(5)).orElseThrow().equals("日志"), "Reinsertion must retain volume name");
            helper.assertTrue(!watch.consume(), "Quiet shelf must not generate polling invalidations");
            loaded.setItem(5, media.put(loaded.getItem(5), "reading", entry));
            helper.assertTrue(!media.stat(loaded.getItem(5), "reading").field("version").equals(versioned.field("version")), "Local replacement advances the physical medium revision");
            helper.assertTrue(loaded.getItem(5).get(DataComponents.WRITABLE_BOOK_CONTENT).equals(pages), "Version edits preserve original pages");
            helper.succeed();
        } finally { LibraryShelfUpdates.forget(level, watch); }
    }
}
