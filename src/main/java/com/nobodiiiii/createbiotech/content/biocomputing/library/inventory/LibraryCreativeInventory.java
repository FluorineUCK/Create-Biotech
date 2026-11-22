package com.nobodiiiii.createbiotech.content.biocomputing.library.inventory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** Server-owned item movement, separate from Computer programs and Library service execution. */
public final class LibraryCreativeInventory {
    private LibraryCreativeInventory() {}
    public static boolean apply(ServerPlayer player, LibraryCreativeInventoryRequest request) {
        if (!player.gameMode.isCreative() || player.isSpectator() || player.containerMenu != player.inventoryMenu) return false;
        var menu = player.inventoryMenu;
        try {
            if (request.action() == LibraryCreativeInventoryRequest.Action.CLEAR_ALL) {
                for (int i = 1; i < menu.slots.size(); i++) menu.getSlot(i).setByPlayer(ItemStack.EMPTY);
                return true;
            }
            if (request.action() == LibraryCreativeInventoryRequest.Action.REPLACE_SLOT) {
                if (request.slot() < 1 || request.slot() >= menu.slots.size()) return false;
                ItemStack shown;
                try {
                    shown = request.decodeCursor(player.registryAccess());
                    if (!shown.isItemEnabled(player.level().enabledFeatures()) || LibraryCreativeItems.privateStack(shown)) return false;
                } catch (RuntimeException invalid) { return false; }
                menu.getSlot(request.slot()).setByPlayer(shown);
                return true;
            }
            if (request.action() == LibraryCreativeInventoryRequest.Action.SET_CURSOR) {
                ItemStack shown;
                try {
                    shown = request.decodeCursor(player.registryAccess());
                    if (!shown.isItemEnabled(player.level().enabledFeatures())) return false;
                    if (LibraryCreativeItems.privateStack(shown)) {
                        if (!LibraryCreativeItems.privateStack(menu.getCarried())) return false;
                        var expected = LibraryCreativeInventoryRequest.of(request.action(), request.slot(), request.button(), request.click(),
                            menu.getCarried(), player.registryAccess()).decodeCursor(player.registryAccess());
                        if (!ItemStack.isSameItemSameComponents(expected, shown)) return false;
                        shown = menu.getCarried().copyWithCount(shown.getCount());
                    }
                } catch (RuntimeException invalid) { return false; }
                // An explicit catalogue/trash action may discard a cursor. A projection may only resize its owned original.
                menu.setCarried(shown);
                return true;
            }
            boolean hotbarCollect = request.action() == LibraryCreativeInventoryRequest.Action.HOTBAR_PICK_ALL;
            if (hotbarCollect && (request.slot() < 36 || request.slot() > 44 || request.button() < 0 || request.button() > 1)) return false;
            if (!hotbarCollect && request.action() != LibraryCreativeInventoryRequest.Action.CLICK
                || !(request.slot() == -999 || request.slot() >= 0 && request.slot() < menu.slots.size())
                || !validButton(request.click(), request.button())) return false;
            ItemStack shown;
            try {
                shown = request.decodeCursor(player.registryAccess());
                if (!shown.isItemEnabled(player.level().enabledFeatures())) return false;
                if (LibraryCreativeItems.privateStack(menu.getCarried())) {
                    var expected = LibraryCreativeInventoryRequest.of(request.action(), request.slot(), request.button(), request.click(),
                        menu.getCarried(), player.registryAccess()).decodeCursor(player.registryAccess());
                    if (!ItemStack.matches(expected, shown)) return false;
                } else if (LibraryCreativeItems.privateStack(shown)) return false;
            } catch (RuntimeException invalid) { return false; }
            // Creative catalogue cursors may be public client-created items, never a private archive projection.
            if (!LibraryCreativeItems.privateStack(menu.getCarried())) menu.setCarried(shown);
            if (hotbarCollect) LibraryCreativeHotbar.collect(player, request.slot(), request.button());
            else menu.clicked(request.slot(), request.button(), request.click(), player);
            return true;
        } finally {
            // Vanilla ignores cursor-only updates on its creative screen. A full inventory update includes it.
            menu.sendAllDataToRemote();
        }
    }
    private static boolean validButton(ClickType type, int button) {
        return switch (type) {
            case PICKUP, QUICK_MOVE, THROW, PICKUP_ALL -> button == 0 || button == 1;
            case SWAP -> button >= 0 && button <= 8 || button == 40;
            case CLONE -> button == 2;
            case QUICK_CRAFT -> button == 0 || button == 1 || button == 2 || button == 4 || button == 5 || button == 6
                || button == 8 || button == 9 || button == 10;
        };
    }
}
