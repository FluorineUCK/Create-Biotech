package com.nobodiiiii.createbiotech.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.nobodiiiii.createbiotech.compat.jei.CapturedEntityBoxJeiRenderer;

import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import net.minecraft.client.gui.GuiGraphics;

@Pseudo
@Mixin(targets = "mezz.jei.library.gui.recipes.RecipeLayout", remap = false)
public abstract class JeiRecipeLayoutMixin {
	@Redirect(method = "drawRecipe", at = @At(value = "INVOKE",
		target = "Lmezz/jei/api/gui/ingredient/IRecipeSlotDrawable;draw(Lnet/minecraft/client/gui/GuiGraphics;Z)V"),
		remap = true)
	private void createBiotech$drawSlotWithHoverContext(IRecipeSlotDrawable slot, GuiGraphics slotGraphics,
		boolean hovered) {
		CapturedEntityBoxJeiRenderer.drawSlotWithHoverContext(slot, slotGraphics, hovered);
	}
}
