package com.nobodiiiii.createbiotech.compat.jei;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternRecipeEditorScreen;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.*;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.Recipe;

public final class PatternRecipeTransfer<R extends Recipe<?>> implements IRecipeTransferHandler<PatternStorageCoreMenu, RecipeHolder<R>> {
    static IJeiRuntime runtime;
    private final IRecipeTransferHandlerHelper helper;
    private final RecipeType<RecipeHolder<R>> category;
    PatternRecipeTransfer(IRecipeTransferHandlerHelper helper, RecipeType<RecipeHolder<R>> category) {
        this.helper = helper; this.category = category;
    }
    public static boolean openChooser() {
        if (runtime == null) return false;
        runtime.getRecipesGui().showTypes(List.of(RecipeTypes.CRAFTING, RecipeTypes.STONECUTTING));
        return true;
    }
    @Override public Class<PatternStorageCoreMenu> getContainerClass() { return PatternStorageCoreMenu.class; }
    @Override public Optional<MenuType<PatternStorageCoreMenu>> getMenuType() { return Optional.of(CBMenuTypes.PATTERN_STORAGE_CORE.get()); }
    @Override public RecipeType<RecipeHolder<R>> getRecipeType() { return category; }
    @Override public IRecipeTransferError transferRecipe(PatternStorageCoreMenu menu, RecipeHolder<R> holder,
            IRecipeSlotsView ignored, Player player, boolean maxTransfer, boolean doTransfer) {
        if (runtime == null || player.containerMenu != menu) return helper.createInternalError();
        var parent = runtime.getRecipesGui().getParentScreen().orElse(null);
        if (!(parent instanceof PatternRecipeEditorScreen editor) || editor.getMenu() != menu) return helper.createInternalError();
        if (!editor.canInitializeRecipe()) return helper.createUserErrorWithTooltip(Component.translatable("create_biotech.pattern_editor.jei.empty_draft"));
        if (doTransfer) editor.requestRecipePreview(holder.id());
        return null;
    }
}
