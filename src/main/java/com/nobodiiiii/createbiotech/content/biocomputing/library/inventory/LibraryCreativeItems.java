package com.nobodiiiii.createbiotech.content.biocomputing.library.inventory;

import com.nobodiiiii.createbiotech.registry.CBDataComponents;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** A client display projection is never an authoritative replacement for a physical archive. */
public final class LibraryCreativeItems {
    private LibraryCreativeItems() {}
    public static boolean privateStack(ItemStack stack) {
        // Walk vanilla item-bearing components without serializing or reading private service contents.
        // Over-complex trees fail closed. This is an inspection bound, not Library storage capacity.
        var pending = new ArrayDeque<Iterator<ItemStack>>();
        pending.push(List.of(stack).iterator());
        int visited = 0;
        while (!pending.isEmpty()) {
            if (!pending.peek().hasNext()) { pending.pop(); continue; }
            if (++visited > 4096) return true;
            var item = pending.peek().next();
            if (item.isEmpty()) continue;
            if (item.has(CBDataComponents.BIOCOMPUTING_LIBRARY_SERVICE.get())) return true;
            var container = item.get(DataComponents.CONTAINER);
            if (container != null) pending.push(container.nonEmptyItems().iterator());
            var bundle = item.get(DataComponents.BUNDLE_CONTENTS);
            if (bundle != null) pending.push(bundle.items().iterator());
            var projectiles = item.get(DataComponents.CHARGED_PROJECTILES);
            if (projectiles != null) pending.push(projectiles.getItems().iterator());
        }
        return false;
    }
    public static boolean rejectLegacyReplacement(ServerPlayer player, int slot, ItemStack incoming) {
        if (!player.gameMode.isCreative()) return false;
        boolean protectedTarget = slot >= 1 && slot < player.inventoryMenu.slots.size()
            && privateStack(player.inventoryMenu.getSlot(slot).getItem());
        if (!privateStack(incoming) && !protectedTarget) return false;
        player.inventoryMenu.sendAllDataToRemote();
        reportRejected(player);
        return true;
    }
    public static void reportRejected(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("create_biotech.biocomputing.library.creative_rejected"), true);
    }
}
