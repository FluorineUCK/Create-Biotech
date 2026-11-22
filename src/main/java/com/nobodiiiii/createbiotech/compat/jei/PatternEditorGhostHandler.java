package com.nobodiiiii.createbiotech.compat.jei;

import java.util.List;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternRecipeEditorScreen;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

final class PatternEditorGhostHandler implements IGhostIngredientHandler<PatternRecipeEditorScreen> {
    @Override public <I> List<Target<I>> getTargetsTyped(PatternRecipeEditorScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
        if (!(ingredient.getIngredient() instanceof ItemStack)) return List.of();
        return screen.ghostTargets().stream().map(target -> (Target<I>) new Target<I>() {
            @Override public Rect2i getArea() { return target.area(); }
            @Override public void accept(I value) { if (value instanceof ItemStack stack) target.accept().accept(stack); }
        }).toList();
    }
    @Override public void onComplete() {}
}
