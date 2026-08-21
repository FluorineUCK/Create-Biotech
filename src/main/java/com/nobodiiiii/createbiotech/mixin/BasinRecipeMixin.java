package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.processing.basin.BasinEntityProcessing;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinRecipe;

import net.minecraft.world.item.crafting.Recipe;

/**
 * Marks Create's basin recipe pass as an authorised captured-slime item mover.
 *
 * <p>{@code BasinRecipe} reads and extracts ingredients through the basin's item capability - the
 * same handler hoppers and pipes see - so {@code BasinInventoryMixin} cannot tell the two apart on
 * its own. Both {@code match} and {@code apply} funnel through this private overload, so scoping it
 * covers ingredient extraction and, transitively, {@code acceptOutputs}.</p>
 */
@Mixin(BasinRecipe.class)
public abstract class BasinRecipeMixin {

	@Inject(
		method = "apply(Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;Lnet/minecraft/world/item/crafting/Recipe;Z)Z",
		at = @At("HEAD"))
	private static void createBiotech$beginCapturedSlimeItemMovement(BasinBlockEntity basin, Recipe<?> recipe,
		boolean test, CallbackInfoReturnable<Boolean> cir) {
		BasinEntityProcessing.beginCapturedSlimeItemMovement();
	}

	@Inject(
		method = "apply(Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;Lnet/minecraft/world/item/crafting/Recipe;Z)Z",
		at = @At("RETURN"))
	private static void createBiotech$endCapturedSlimeItemMovement(BasinBlockEntity basin, Recipe<?> recipe,
		boolean test, CallbackInfoReturnable<Boolean> cir) {
		BasinEntityProcessing.endCapturedSlimeItemMovement();
	}
}
