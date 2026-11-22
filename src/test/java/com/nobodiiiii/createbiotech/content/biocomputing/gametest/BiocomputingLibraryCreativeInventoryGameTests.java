package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryServiceBehaviour;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.network.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingLibraryCreativeInventoryGameTests {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void catalogueDoubleClickCollectsOnlyVisibleHotbar(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        f.player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        var stone = new ItemStack(net.minecraft.world.item.Items.STONE, 2);
        f.player.inventoryMenu.getSlot(37).set(stone.copy());
        f.player.inventoryMenu.getSlot(9).set(stone.copy());
        action(f, com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.Action.HOTBAR_PICK_ALL, 36, stone);
        helper.assertTrue(f.player.inventoryMenu.getCarried().getCount() == 4, "Catalogue double-click collects visible hotbar items");
        helper.assertTrue(ItemStack.matches(f.player.inventoryMenu.getSlot(9).getItem(), stone), "Hidden inventory is not swept into creative catalogue cursor");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void cursorDropAndContainerCloseKeepTheFullOriginal(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        var original = carrying(f);
        f.player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        f.player.inventoryMenu.getSlot(36).set(original.copy());
        click(f, 36, ItemStack.EMPTY);
        click(f, -999, projection(f, original));
        helper.assertTrue(f.player.inventoryMenu.getCarried().isEmpty(), "Outside click releases server cursor");
        var drops = f.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, f.player.getBoundingBox().inflate(8));
        helper.assertTrue(drops.stream().anyMatch(e -> ItemStack.matches(e.getItem(), original)), "Actual dropped entity retains full service archive");
        drops.forEach(net.minecraft.world.entity.Entity::discard);
        f.player.inventoryMenu.getSlot(36).set(original.copy());
        click(f, 36, ItemStack.EMPTY);
        listener(f).handleContainerClose(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(0));
        helper.assertTrue(f.player.inventoryMenu.getCarried().isEmpty(), "Closing returns cursor to inventory");
        helper.assertTrue(f.player.getInventory().items.stream().anyMatch(s -> ItemStack.matches(s, original)), "Close returns full original, never a projection");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void unauthorizedAndMalformedIntentsDoNotChangeInventory(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        var original = carrying(f);
        f.player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        f.player.inventoryMenu.getSlot(36).set(original.copy());
        action(f, com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.Action.CLEAR_ALL, -1, ItemStack.EMPTY);
        helper.assertTrue(ItemStack.matches(f.player.inventoryMenu.getSlot(36).getItem(), original), "Survival cannot use creative clear");
        f.player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        var malformed = new com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest(
            com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.Action.CLICK,
            36, 0, net.minecraft.world.inventory.ClickType.PICKUP, new byte[] {0, 1});
        malformed.handle(f.player);
        helper.assertTrue(ItemStack.matches(f.player.inventoryMenu.getSlot(36).getItem(), original), "Trailing payload bytes cannot change source");
        click(f, 36, ItemStack.EMPTY);
        click(f, 37, ItemStack.EMPTY);
        helper.assertTrue(ItemStack.matches(f.player.inventoryMenu.getCarried(), original), "Stale empty cursor does not discard server-owned service");
        helper.assertTrue(f.player.inventoryMenu.getSlot(37).getItem().isEmpty(), "Refused stale click cannot install a substitute");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void invalidCreativeSlotCannotEnterVanillaClickExecution(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        var original = carrying(f);
        f.player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        f.player.inventoryMenu.setCarried(original.copy());
        var request = com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.of(
            com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.Action.CLICK,
            -2, 0, net.minecraft.world.inventory.ClickType.SWAP, projection(f, original), f.level.registryAccess());
        helper.assertTrue(!com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventory.apply(f.player, request), "Negative non-sentinel slot must be refused");
        helper.assertTrue(ItemStack.matches(f.player.inventoryMenu.getCarried(), original), "Refusal retains cursor ownership");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void nestedContainerCannotEraseOrRecreateThePrivateCore(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        var core = carrying(f);
        var box = new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        box.set(net.minecraft.core.component.DataComponents.CONTAINER,
            net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of(core)));
        f.player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        f.player.inventoryMenu.getSlot(36).set(box.copy());
        var listener = listener(f);
        listener.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(36, ItemStack.EMPTY));
        listener.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(37, projection(f, box)));
        helper.assertTrue(ItemStack.matches(f.player.inventoryMenu.getSlot(36).getItem(), box), "Nested core keeps its archive on unsafe legacy clear");
        helper.assertTrue(f.player.inventoryMenu.getSlot(37).getItem().isEmpty(), "Nested display archive cannot be recreated");
        click(f, 36, ItemStack.EMPTY);
        click(f, 37, projection(f, box));
        helper.assertTrue(ItemStack.matches(f.player.inventoryMenu.getSlot(37).getItem(), box), "Server movement retains the complete nested archive");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void explicitCreativeCatalogueAndTrashCanReplaceOwnedSlots(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        var original = carrying(f);
        f.player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        f.player.inventoryMenu.getSlot(36).set(original.copy());
        var stone = new ItemStack(net.minecraft.world.item.Items.STONE, 64);
        action(f, com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.Action.REPLACE_SLOT, 36, stone);
        helper.assertTrue(ItemStack.matches(f.player.inventoryMenu.getSlot(36).getItem(), stone), "Explicit catalogue replacement retains normal creative editing");
        f.player.inventoryMenu.getSlot(37).set(original.copy());
        action(f, com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.Action.CLEAR_ALL, -1, ItemStack.EMPTY);
        helper.assertTrue(f.player.inventoryMenu.getSlot(36).getItem().isEmpty() && f.player.inventoryMenu.getSlot(37).getItem().isEmpty(), "Explicit shift-trash clears public and private items");
        action(f, com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.Action.REPLACE_SLOT, 36, projection(f, original));
        helper.assertTrue(f.player.inventoryMenu.getSlot(36).getItem().isEmpty(), "Catalogue cannot recreate a private service from its projection");
        helper.succeed();
    }

    static void action(BiocomputingLibraryBlockGameTests.Fixture f,
            com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.Action action,
            int slot, ItemStack shown) {
        com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.of(
            action, slot, 0, net.minecraft.world.inventory.ClickType.PICKUP, shown, f.level.registryAccess()).handle(f.player);
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void explicitCreativeCursorReplacementIsNotAnUntrustedSlotOverwrite(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        var original = carrying(f);
        f.player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        f.player.inventoryMenu.setCarried(original.copy());
        var request = com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.of(
            com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.Action.SET_CURSOR,
            -1, 0, net.minecraft.world.inventory.ClickType.PICKUP, ItemStack.EMPTY, f.level.registryAccess());
        request.handle(f.player);
        helper.assertTrue(f.player.inventoryMenu.getCarried().isEmpty(), "Explicit trash/cursor clear disposes the actual cursor");
        var marker = projection(f, original);
        com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.of(
            com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.Action.SET_CURSOR,
            -1, 0, net.minecraft.world.inventory.ClickType.PICKUP, marker, f.level.registryAccess()).handle(f.player);
        helper.assertTrue(f.player.inventoryMenu.getCarried().isEmpty(), "A saved client template cannot recreate the disposed archive");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void serverCreativeClicksCarryFullArchiveThroughMouseCursorAndNewSlot(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        var original = carrying(f);
        f.player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        f.player.inventoryMenu.getSlot(36).set(original.copy());
        click(f, 36, ItemStack.EMPTY);
        helper.assertTrue(ItemStack.matches(f.player.inventoryMenu.getCarried(), original), "Server cursor owns the full archive after pickup");
        helper.assertTrue(f.player.inventoryMenu.getSlot(36).getItem().isEmpty(), "Source is cleared by authoritative pickup");
        click(f, 37, projection(f, original));
        helper.assertTrue(ItemStack.matches(f.player.inventoryMenu.getSlot(37).getItem(), original), "Destination receives the original, not the display marker");
        helper.assertTrue(f.player.inventoryMenu.getCarried().isEmpty(), "Cursor releases exactly one carried core");
        f.assertBookUnchanged(); helper.succeed();
    }

    static void click(BiocomputingLibraryBlockGameTests.Fixture f, int slot, ItemStack beforeCursor) {
        var request = com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.of(
            com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.Action.CLICK,
            slot, 0, net.minecraft.world.inventory.ClickType.PICKUP, beforeCursor, f.level.registryAccess());
        var wire = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), f.level.registryAccess());
        try {
            com.nobodiiiii.createbiotech.network.CBPackets.roundTripPanelForTest(request, wire).handle(f.player);
        } finally { wire.release(); }
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void legacyCreativeReplacementMustNotDestroyCarriedService(GameTestHelper helper) {
        var f = new BiocomputingLibraryBlockGameTests.Fixture(helper);
        var original = carrying(f);
        var client = projection(f, original);
        f.player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        f.player.inventoryMenu.getSlot(36).set(original.copy());
        var listener = listener(f);
        listener.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(36, ItemStack.EMPTY));
        listener.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(37, client));
        helper.assertTrue(ItemStack.matches(f.player.inventoryMenu.getSlot(36).getItem(), original),
            "Unsafe legacy replacement must retain the complete server-owned original");
        helper.assertTrue(f.player.inventoryMenu.getSlot(37).getItem().isEmpty(), "Client marker cannot become another real core");
        helper.succeed();
    }

    static ItemStack carrying(BiocomputingLibraryBlockGameTests.Fixture f) {
        var service = f.core.getBehaviour(LibraryServiceBehaviour.TYPE);
        service.configure(0, BiocomputingLibraryServiceCarryGameTests.configuration(true));
        f.helper.assertTrue(service.receive(BiocomputingLibraryServiceCarryGameTests.request()).accepted(), "Admit request before removal");
        f.wrench(true);
        var drop = f.coreDrops().getFirst(); var result = drop.getItem().copy(); drop.discard();
        return result;
    }
    static ItemStack projection(BiocomputingLibraryBlockGameTests.Fixture f, ItemStack original) {
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), f.level.registryAccess());
        try { ItemStack.STREAM_CODEC.encode(buffer, original); return ItemStack.STREAM_CODEC.decode(buffer); }
        finally { buffer.release(); }
    }
    static ServerGamePacketListenerImpl listener(BiocomputingLibraryBlockGameTests.Fixture f) {
        return new ServerGamePacketListenerImpl(f.level.getServer(), new Connection(PacketFlow.SERVERBOUND), f.player,
            CommonListenerCookie.createInitial(f.player.getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) {}
                @Override public void send(Packet<?> packet, PacketSendListener callback) {}
            };
    }
}
