package com.nobodiiiii.createbiotech.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.List;
import java.util.UUID;


import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import com.nobodiiiii.createbiotech.CreateBiotech;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FactoryPlayerEntryGameTests {
    private FactoryPlayerEntryGameTests() {}

    private static com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity unboundLibrary(
        GameTestHelper helper, BlockPos pos) {
        var level = helper.getLevel();
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        var state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState()
            .setValue(com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        level.setBlock(pos.above(), state.setValue(com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock.HALF,
            DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        var core = (com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity) level.getBlockEntity(pos);
        helper.assertTrue(core.applyPlacementData(com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData.configured(
            com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency.of("entry-library"),
            0, UUID.randomUUID(), 0)), "Library fixture must own a real configured identity claim");
        return core;
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.WEST, pos, false);
    }
    private static ServerPlayer menuPlayer(GameTestHelper helper, BlockPos pos) {
        var player = new RecordingPlayer(helper.getLevel());
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.moveTo(Vec3.atCenterOf(pos.west()));
        return player;
    }

    @GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
    public static void bothCoreHalvesOpenRegisteredBrowserWithoutClusterOrRecipes(GameTestHelper helper) {
        var pos = helper.absolutePos(new BlockPos(2, 3, 2));
        var core = unboundLibrary(helper, pos);
        var player = (RecordingPlayer) menuPlayer(helper, pos);
        var before = core.saveWithoutMetadata(helper.getLevel().registryAccess());
        com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserRequestPacket previousRequest = null;
        for (var clicked : List.of(pos, pos.above())) {
            player.containerMenu = player.inventoryMenu;
            helper.assertTrue(player.gameMode.useItemOn(player, helper.getLevel(), ItemStack.EMPTY,
                InteractionHand.MAIN_HAND, hit(clicked)).consumesAction(), "Physical Core click must open the browser");
            helper.assertTrue(player.containerMenu instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu,
                "Either half must open the canonical registered browser");
            helper.assertTrue(player.clientMenu instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu,
                "Opening bytes must construct the registered client menu");
            var menu = (com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu) player.containerMenu;
            var physicalAddress = core.memberAddress();
            helper.assertValueEqual(menu.coreAddress(), new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation(
                physicalAddress.dimension(), physicalAddress.subLevelId(), physicalAddress.localPos()),
                "Upper half resolves to lower physical address");
            helper.assertValueEqual(menu.libraryId(), core.memberId(), "Opening preserves exact Library identity");
            var client = (com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu) player.clientMenu;
            if (previousRequest != null) {
                helper.assertFalse(menu.enqueue(player, previousRequest), "Reopened menu rejects previous random session despite container-id reuse");
            } else {
                var request = client.browser().request(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter.EMPTY, 0, java.util.Optional.empty());
                var wire = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), levelRegistries(helper));
                try {
                    com.nobodiiiii.createbiotech.network.CBPackets.beginPanelDeliveryObservationForTest(player);
                    com.nobodiiiii.createbiotech.network.CBPackets.roundTripPanelForTest(request, wire).handle(player);
                    menu.broadcastChanges();
                    var payload = com.nobodiiiii.createbiotech.network.CBPackets.takePanelDeliveryForTest(player).orElseThrow();
                    helper.assertTrue(payload instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserResponsePacket,
                        "Registered browser request must deliver its actual bounded response");
                    wire.clear();
                    var response = com.nobodiiiii.createbiotech.network.CBPackets.roundTripPanelForTest(
                        (com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserResponsePacket) payload, wire);
                    helper.assertTrue(client.browser().accept(response), "Registered response must apply to exact client opening and intent");
                    helper.assertTrue(response.page().entries().isEmpty(), "Empty unbound Core still has an operational browser");
                    helper.assertValueEqual(response.page().status(), com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage.Status.OK,
                        "Empty browser is OK, not an unopened placeholder");
                    previousRequest = request;
                } finally { wire.release(); }
            }
        }
        helper.assertValueEqual(core.saveWithoutMetadata(helper.getLevel().registryAccess()), before,
            "Browser opening cannot spend resources, create queries or mutate persisted Core state");
        helper.assertTrue(!core.saveWithoutMetadata(core.getLevel().registryAccess()).getCompound("PatternLibrary").contains("BindingState"), "Browser opening must not bind an unbound Library");
        helper.succeed();
    }

    private static net.minecraft.core.RegistryAccess levelRegistries(GameTestHelper helper) { return helper.getLevel().registryAccess(); }

    private static final class RecordingPlayer extends FakePlayer {
        net.minecraft.world.inventory.AbstractContainerMenu clientMenu;
        net.minecraft.network.chat.Component feedback;
        RecordingPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "player-entry")); }
        @Override public java.util.OptionalInt openMenu(net.minecraft.world.MenuProvider provider,
            java.util.function.Consumer<net.minecraft.network.RegistryFriendlyByteBuf> writer) {
            var menu = provider.createMenu(1, getInventory(), this);
            if (menu == null) return java.util.OptionalInt.empty();
            containerMenu = menu;
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess());
            try {
                if (writer != null) writer.accept(buffer);
                if (menu instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu)
                    clientMenu = com.nobodiiiii.createbiotech.registry.CBMenuTypes.PATTERN_STORAGE_CORE.get().create(1, getInventory(), buffer);
            } finally { buffer.release(); }
            return java.util.OptionalInt.of(1);
        }
        @Override public void displayClientMessage(net.minecraft.network.chat.Component component, boolean overlay) {
            feedback = component;
        }
    }
}
