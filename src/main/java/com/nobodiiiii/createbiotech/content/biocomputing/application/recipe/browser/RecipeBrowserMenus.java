package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import java.util.Optional;
import java.util.UUID;
import java.util.Objects;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Opens the installed manual recipe application for an authorized physical host. */
public final class RecipeBrowserMenus {
    private RecipeBrowserMenus() {}
    public static boolean open(RecipeBrowserHost host, ServerPlayer player, Optional<UUID> selection) {
        Objects.requireNonNull(host); Objects.requireNonNull(player); Objects.requireNonNull(selection);
        if (!host.canPlayerUse(player)) return false;
        if (selection.isPresent() && (!host.canPlayerEdit(player) || host.recipeSnapshot().records().stream()
            .noneMatch(entry -> selection.get().equals(entry.patternId())))) return false;
        var provider = new MenuProvider() {
            private PatternStorageCoreMenu menu;
            @Override public Component getDisplayName() { return Component.translatable("create_biotech.pattern_core.browser.title"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player ignored) {
                menu = new PatternStorageCoreMenu(id, inventory, host, selection);
                return menu;
            }
            void write(RegistryFriendlyByteBuf buffer) { Objects.requireNonNull(menu).writeOpeningData(buffer); }
        };
        return player.openMenu(provider, provider::write).isPresent();
    }
}
