package com.nobodiiiii.createbiotech.content.biocomputing.library.inventory;

import com.nobodiiiii.createbiotech.network.CBPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import static com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest.Action.*;

/** Keeps the vanilla creative UI, but never sends an archive's display copy as its replacement. */
public final class LibraryCreativeScreenSession {
    private boolean managed;
    private boolean catalogueCursor;
    private ItemStack previousCursor = ItemStack.EMPTY;

    public interface Access { LibraryCreativeScreenSession biocomputing$inventorySession(); }

    public boolean active() {
        var player = Minecraft.getInstance().player;
        if (!managed && player != null) {
            managed = LibraryCreativeItems.privateStack(player.inventoryMenu.getCarried())
                || player.inventoryMenu.getItems().stream().anyMatch(LibraryCreativeItems::privateStack);
        }
        return managed;
    }

    public static boolean suppressLegacyEcho() {
        return Minecraft.getInstance().screen instanceof Access screen && screen.biocomputing$inventorySession().active();
    }

    /** True means this session has handled (or explicitly rejected) the click. */
    public boolean before(CreativeModeInventoryScreen screen, Slot slot, int slotId, int button, ClickType type,
                          Slot trash, boolean clickedOutside) {
        catalogueCursor = false;
        if (!active()) return false;
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || slot != null && !slot.mayPickup(player)) return true;
        var menu = player.inventoryMenu;

        if (screen.isInventoryOpen() && slot == trash && slot != null) {
            if (type == ClickType.QUICK_MOVE) {
                send(CLEAR_ALL, -1, 0, ClickType.PICKUP, ItemStack.EMPTY,
                    () -> { for (int i = 1; i < menu.slots.size(); i++) menu.getSlot(i).set(ItemStack.EMPTY); });
            } else send(SET_CURSOR, -1, 0, ClickType.PICKUP, ItemStack.EMPTY, () -> menu.setCarried(ItemStack.EMPTY));
            return true;
        }

        int index = -1;
        if (slot == null) {
            if (slotId != -999 || !screen.isInventoryOpen() && type != ClickType.QUICK_CRAFT && !clickedOutside) return true;
            index = -999;
        } else if (screen.isInventoryOpen()) index = inventorySlot(screen.getMenu().slots, slot);
        else if (slot.container == player.getInventory() && slot.getSlotIndex() >= 0 && slot.getSlotIndex() < 9)
            index = 36 + slot.getSlotIndex();

        if (index != -1) {
            if (!screen.isInventoryOpen() && index >= 36 && index <= 44) {
                int target = index;
                if (type == ClickType.QUICK_MOVE) {
                    send(REPLACE_SLOT, target, 0, ClickType.PICKUP, ItemStack.EMPTY, () -> menu.getSlot(target).set(ItemStack.EMPTY));
                    return true;
                }
                if (type == ClickType.PICKUP_ALL) {
                    send(HOTBAR_PICK_ALL, target, button, type, menu.getCarried(), () -> LibraryCreativeHotbar.collect(player, target, button));
                    return true;
                }
            }
            // Vanilla's outside creative THROW means drop the cursor; InventoryMenu expresses that as PICKUP.
            var click = index == -999 && type != ClickType.QUICK_CRAFT ? ClickType.PICKUP : type;
            int target = index;
            send(CLICK, target, button, click, menu.getCarried(), () -> menu.clicked(target, button, click, player));
            return true;
        }

        // A catalogue slot is a template, not a source from which a private archive can be recovered.
        if (type == ClickType.QUICK_CRAFT) return true;
        var template = slot.getItem();
        if (LibraryCreativeItems.privateStack(template)) { rejected(); return true; }
        if (type == ClickType.SWAP) {
            if (!template.isEmpty() && (button >= 0 && button <= 8 || button == 40)) {
                int target = button == 40 ? 45 : 36 + button;
                var replacement = template.copyWithCount(template.getMaxStackSize());
                send(REPLACE_SLOT, target, 0, ClickType.PICKUP, replacement, () -> menu.getSlot(target).set(replacement));
            }
            return true;
        }
        // Vanilla retains catalogue search/clone/count behavior. Preflight both possible cursor values before mutation.
        try {
            LibraryCreativeInventoryRequest.of(SET_CURSOR, -1, 0, ClickType.PICKUP, template, player.registryAccess());
            LibraryCreativeInventoryRequest.of(SET_CURSOR, -1, 0, ClickType.PICKUP, menu.getCarried(), player.registryAccess());
        } catch (RuntimeException tooLarge) { rejected(); return true; }
        previousCursor = menu.getCarried().copy();
        catalogueCursor = true;
        return false;
    }

    public void after() {
        if (!catalogueCursor) return;
        catalogueCursor = false;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!send(SET_CURSOR, -1, 0, ClickType.PICKUP, player.inventoryMenu.getCarried(), () -> {}))
            player.inventoryMenu.setCarried(previousCursor);
        previousCursor = ItemStack.EMPTY;
    }

    public static int inventorySlot(java.util.List<Slot> displayed, Slot clicked) {
        // Creative selectTab appends wrappers without addSlot: their public index remains zero.
        // getSlotIndex is also unsuitable, since it delegates to the player's underlying container.
        return displayed.indexOf(clicked);
    }

    private static boolean send(LibraryCreativeInventoryRequest.Action action, int slot, int button, ClickType click,
                                ItemStack cursor, Runnable predict) {
        var player = Minecraft.getInstance().player;
        if (player == null) return false;
        LibraryCreativeInventoryRequest request;
        try { request = LibraryCreativeInventoryRequest.of(action, slot, button, click, cursor, player.registryAccess()); }
        catch (RuntimeException tooLarge) { rejected(); return false; }
        predict.run();
        CBPackets.sendToServer(request);
        return true;
    }

    private static void rejected() {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.translatable("create_biotech.biocomputing.library.creative_rejected"), true);
    }
}
