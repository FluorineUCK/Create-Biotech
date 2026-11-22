package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.stockTicker.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech") @PrefixGameTestTemplate(false)
public class StockRetiredOrderGameTests {
    @GameTest(template="empty")
    public static void unboundTickerRejectsOldCatalogWithoutHijackingPhysicalItems(GameTestHelper h) {
        var pos = h.absolutePos(new BlockPos(1, 1, 1));
        var level = h.getLevel();
        level.setBlock(pos, AllBlocks.STOCK_TICKER.getDefaultState(), 3);
        var ticker = (StockTickerBlockEntity) level.getBlockEntity(pos);
        var messages = new ArrayList<String>();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "OrderCheck")) {
            int closed;
            @Override public void closeContainer() { closed++; }
            @Override public void displayClientMessage(Component msg, boolean overlay) {
                if (msg.getContents() instanceof TranslatableContents text) messages.add(text.getKey());
            }
        };
        var normal = new BigItemStack(Items.IRON_INGOT.getDefaultInstance(), 2);
        var token = new BigItemStack(CBItems.PATTERN_DISPLAY.get().getDefaultInstance(), 1);
        var mixed = PackageOrderWithCrafts.simple(List.of(normal, token));
        var nested = new PackageOrderWithCrafts(new PackageOrder(List.of(normal)), List.of(
            new PackageOrderWithCrafts.CraftingEntry(new PackageOrder(List.of(token)), 1)));
        new Packet(pos, mixed, false).apply(player, ticker);
        new Packet(pos, nested, true).apply(player, ticker);
        var retired = "create_biotech.stock_application.code.retired_pattern";
        h.assertValueEqual(messages, List.of(retired, retired), "Both old orders are explicitly rejected");
        h.assertValueEqual(player.closed, 0, "Rejected token must not reach native requester encoding");
        new Packet(pos, PackageOrderWithCrafts.simple(List.of(normal)), true).apply(player, ticker);
        h.assertValueEqual(player.closed, 1, "An ordinary item still takes Create's requester path");
        h.succeed();
    }

    private static final class Packet extends PackageOrderRequestPacket {
        Packet(BlockPos pos, PackageOrderWithCrafts order, boolean requester) { super(pos, order, "shop", requester); }
        void apply(ServerPlayer actor, StockTickerBlockEntity ticker) { super.applySettings(actor, ticker); }
    }
}
