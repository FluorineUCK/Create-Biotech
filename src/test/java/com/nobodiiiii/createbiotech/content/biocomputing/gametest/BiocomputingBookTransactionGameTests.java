package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.registry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Physical transaction primitive only. This deliberately does not claim the public write service is enabled. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingBookTransactionGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void physicalCommitAndCarriedReceiptRecoverAfterRealShelfReload(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(3, 1, 3));
        level.setBlock(pos, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
        var shelf = (ChiseledBookShelfBlockEntity)level.getBlockEntity(pos);
        var media = new LibraryBookMedia(CBDataComponents.BIOCOMPUTING_LIBRARY_CONTENTS.get(),
            CBConfigs.SERVER.biocomputing.current(true).archive(), CBConfigs.SERVER.biocomputing.libraryBookBytes(true));
        var volumes = new LibraryBookVolumes(CBDataComponents.BIOCOMPUTING_LIBRARY_VOLUME.get());
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "book-transaction"));
        var pages = new WritableBookContent(List.of(Filterable.passThrough("original human notes"), Filterable.passThrough("original recipe page")));
        var book = new ItemStack(Items.WRITABLE_BOOK); book.set(DataComponents.WRITABLE_BOOK_CONTENT, pages);
        book = volumes.rename(media.put(book, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43))), "日志");
        shelf.setItem(2, book);
        try (var view = new LibraryBookWorldAccess(level, pos, actor, () -> true, () -> true, () -> List.of(pos))) {
            var access = new LibraryBookAccess(view, media, volumes); var handle = access.findVolume("日志");
            var token = access.stat(handle, "reading").field("version");
            UUID source = UUID.randomUUID(), event = UUID.randomUUID();
            var message = new MessageEnvelope(source, 0, event, 0, new DataValue.RecordValue(Map.of(
                "op", new DataValue.TextValue("put"), "volume", new DataValue.TextValue("日志"), "entry", new DataValue.TextValue("reading"),
                "expected", token, "kind", new DataValue.TextValue("DATA"), "value", new DataValue.IntegerValue(44))),
                new MessageProtocol.Request(new RequestContext(new RequestCorrelation(source, event, 0), "client")));
            var prepared = access.prepareWrite(handle, message);
            helper.assertTrue(ItemStack.matches(book, shelf.getItem(2)), "Preparing must not write the shelf before the service preflights its reply");
            prepared.commit().run();
            helper.assertValueEqual(media.read(shelf.getItem(2)).get("reading").value(), new DataValue.IntegerValue(44), "Guarded physical replacement");
            helper.assertTrue(shelf.getItem(2).get(DataComponents.WRITABLE_BOOK_CONTENT).equals(pages), "Original pages preserved at commit");
            var saved = shelf.saveWithFullMetadata(level.registryAccess()); level.removeBlockEntity(pos);
            var restored = (ChiseledBookShelfBlockEntity)BlockEntity.loadStatic(pos, level.getBlockState(pos), saved, level.registryAccess());
            level.setBlockEntity(restored);
            var retry = access.prepareWrite(access.findVolume("日志"), message);
            helper.assertTrue(retry.replay(), "Real registered component must carry a committed receipt through shelf NBT reload");
            helper.assertValueEqual(retry.reply(), prepared.reply(), "Recover the exact original reply after the commit-before-reply window");
            var beforeReplay = restored.getItem(2).copy(); retry.commit().run();
            helper.assertTrue(ItemStack.matches(beforeReplay, restored.getItem(2)), "Retry must not consume another revision");
            var taken = restored.removeItem(2, 1); var carried = ItemStack.parse(level.registryAccess(), taken.save(level.registryAccess())).orElseThrow();
            restored.setItem(5, carried);
            var moved = access.findVolume("日志");
            helper.assertTrue(access.prepareWrite(moved, message).replay(), "Moving the physical book carries its receipt, not an external core reference");
            var version = access.stat(moved, "reading"); access.releaseReceipt(moved, message);
            helper.assertValueEqual(media.stat(restored.getItem(5), "reading"), version, "Exact receipt cleanup must not change data or revision");
            helper.assertTrue(restored.getItem(5).get(DataComponents.WRITABLE_BOOK_CONTENT).equals(pages), "Cleanup also preserves original pages");
            helper.succeed();
        }
    }
}
