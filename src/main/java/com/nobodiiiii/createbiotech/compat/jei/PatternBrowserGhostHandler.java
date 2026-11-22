package com.nobodiiiii.createbiotech.compat.jei;

import java.util.List;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreScreen;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

/** Optional JEI adapter only; the Core browser owns the exact, non-consuming ghost target. */
final class PatternBrowserGhostHandler implements IGhostIngredientHandler<PatternStorageCoreScreen> {
    @Override public <I> List<Target<I>> getTargetsTyped(PatternStorageCoreScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
        if (!(ingredient.getIngredient() instanceof ItemStack)) return List.of();
        return List.of(new Target<>() {
            @Override public Rect2i getArea() { return screen.ghostTarget(); }
            @Override public void accept(I value) { if (value instanceof ItemStack stack) screen.acceptGhost(stack); }
        });
    }
    @Override public void onComplete() {}
}
