package com.nobodiiiii.createbiotech.mixin.client;

import com.simibubi.create.content.fluids.transfer.FillingRecipe;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nobodiiiii.createbiotech.compat.jei.AnimatedSquidSpout;
import com.nobodiiiii.createbiotech.registry.CBBlocks;

import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import net.minecraft.client.gui.GuiGraphics;
import java.util.Arrays;

@Pseudo
@Mixin(targets = "com.simibubi.create.compat.jei.category.SpoutCategory")
public abstract class SpoutCategoryMixin {
	@Unique
	private final AnimatedSquidSpout createBiotech$squidSpout = new AnimatedSquidSpout();

	@Inject(method = "draw", at = @At("HEAD"), cancellable = true)
	private void createBiotech$drawSquid(FillingRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics graphics, double mouseX,
		double mouseY, CallbackInfo ci) {
		if (recipe.getRollableResultsAsItemStacks()
			.stream()
			.noneMatch(stack -> stack.is(CBBlocks.SQUID_PRINTER.get().asItem())))
			return;

		AllGuiTextures.JEI_SHADOW.render(graphics, 62, 57);
		AllGuiTextures.JEI_DOWN_ARROW.render(graphics, 126, 29);
		createBiotech$squidSpout.withFluids(recipe.getRequiredFluid()
			.getFluids()
			.length == 0 ? java.util.List.of() : Arrays.asList(recipe.getRequiredFluid().getFluids()))
			.draw(graphics, 75, 22);
		ci.cancel();
	}
}
