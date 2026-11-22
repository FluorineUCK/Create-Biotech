package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenu;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class StockControlVisibilityGameTests {
    @GameTest(template="empty")
    public static void nativeKeeperWithoutApplicationHasNoExtraButtons(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, AllBlocks.STOCK_TICKER.getDefaultState(), 3);
        level.setBlock(pos.east(), AllBlocks.BLAZE_BURNER.getDefaultState().setValue(
            BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING), 3);
        var ticker = (StockTickerBlockEntity)level.getBlockEntity(pos);
        var actor = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "keeper-menu"));
        actor.moveTo(Vec3.atCenterOf(pos));
        actor.containerMenu = ticker.new RequestMenuProvider().createMenu(3, actor.getInventory(), actor);
        h.runAfterDelay(8, () -> {
            h.assertTrue(ticker.isKeeperPresent() && actor.containerMenu.stillValid(actor), "Real native keeper is available");
            var nonce = UUID.randomUUID();
            var state = StockApplicationMenu.controls(actor, nonce);
            h.assertTrue(state.container() == 3 && state.nonce().equals(nonce), "Only the requesting native screen receives its snapshot");
            h.assertFalse(state.application() || state.route(), "No adapter: leave the native screen unchanged");
            h.succeed();
        });
    }
}
