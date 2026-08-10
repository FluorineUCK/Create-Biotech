package com.nobodiiiii.createbiotech.mixin.client;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nobodiiiii.createbiotech.compat.jei.CapturedEntityBoxJeiRenderer;

import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import net.minecraft.client.gui.GuiGraphics;

@Pseudo
@Mixin(targets = "mezz.jei.library.gui.recipes.RecipeLayout", remap = false)
public abstract class JeiRecipeLayoutMixin {

	@Shadow(remap = false)
	public abstract Optional<RecipeSlotUnderMouse> getSlotUnderMouse(double mouseX, double mouseY);

	@WrapOperation(method = "drawRecipe", at = @At(value = "INVOKE",
		target = "Lmezz/jei/api/gui/ingredient/IRecipeSlotDrawable;draw(Lnet/minecraft/client/gui/GuiGraphics;Z)V"),
		require = 0)
	private void createBiotech$drawSlotWithHoverContext(IRecipeSlotDrawable slot, GuiGraphics slotGraphics,
		boolean hovered, Operation<Void> original) {
		CapturedEntityBoxJeiRenderer.beginSlotDraw(slot, hovered);
		try {
			original.call(slot, slotGraphics, hovered);
		} finally {
			CapturedEntityBoxJeiRenderer.endSlotDraw();
		}
	}

	@WrapOperation(method = "drawRecipe", at = @At(value = "INVOKE",
		target = "Lmezz/jei/api/gui/ingredient/IRecipeSlotDrawable;draw(Lnet/minecraft/client/gui/GuiGraphics;)V"),
		require = 0)
	private void createBiotech$drawSlotWithHoverContextLegacy(IRecipeSlotDrawable slot, GuiGraphics slotGraphics,
		Operation<Void> original, GuiGraphics graphics, int mouseX, int mouseY) {
		boolean hovered = getSlotUnderMouse(mouseX, mouseY)
			.map(RecipeSlotUnderMouse::slot)
			.filter(slot::equals)
			.isPresent();
		CapturedEntityBoxJeiRenderer.beginSlotDraw(slot, hovered);
		try {
			original.call(slot, slotGraphics);
		} finally {
			CapturedEntityBoxJeiRenderer.endSlotDraw();
		}
	}
}
