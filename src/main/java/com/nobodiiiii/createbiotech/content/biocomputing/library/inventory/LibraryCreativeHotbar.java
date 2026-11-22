package com.nobodiiiii.createbiotech.content.biocomputing.library.inventory;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Stateless view used only for catalogue double-click, whose visible inventory is the hotbar. */
public final class LibraryCreativeHotbar {
    private LibraryCreativeHotbar() {}
    public static void collect(Player player, int inventorySlot, int button) {
        var view = new AbstractContainerMenu(null, 0) {
            { for (int i = 0; i < 9; i++) addSlot(new Slot(player.getInventory(), i, 0, 0)); }
            @Override public boolean stillValid(Player viewer) { return viewer == player; }
            @Override public ItemStack quickMoveStack(Player viewer, int index) { return ItemStack.EMPTY; }
            @Override public ItemStack getCarried() { return player.inventoryMenu.getCarried(); }
            @Override public void setCarried(ItemStack stack) { player.inventoryMenu.setCarried(stack); }
        };
        view.clicked(inventorySlot - 36, button, ClickType.PICKUP_ALL, player);
    }
}
