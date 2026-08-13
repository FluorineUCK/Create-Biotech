package com.nobodiiiii.createbiotech.compat.jei;

import com.nobodiiiii.createbiotech.content.sonicdogcannon.SonicDogCannonUpgradeRecipe;
import com.nobodiiiii.createbiotech.registry.CBItems;

import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.recipe.category.extensions.vanilla.smithing.ISmithingCategoryExtension;
import net.minecraft.world.item.ItemStack;

public class SonicDogCannonUpgradeJeiExtension
	implements ISmithingCategoryExtension<SonicDogCannonUpgradeRecipe> {

	@Override
	public <T extends IIngredientAcceptor<T>> void setTemplate(SonicDogCannonUpgradeRecipe recipe,
		T ingredientAcceptor) {
		// This upgrade system intentionally does not consume a smithing template.
	}

	@Override
	public <T extends IIngredientAcceptor<T>> void setBase(SonicDogCannonUpgradeRecipe recipe,
		T ingredientAcceptor) {
		ingredientAcceptor.addItemStack(new ItemStack(CBItems.SONIC_DOG_CANNON.get()));
	}

	@Override
	public <T extends IIngredientAcceptor<T>> void setAddition(SonicDogCannonUpgradeRecipe recipe,
		T ingredientAcceptor) {
		ingredientAcceptor.addIngredients(recipe.addition());
	}

	@Override
	public <T extends IIngredientAcceptor<T>> void setOutput(SonicDogCannonUpgradeRecipe recipe,
		T ingredientAcceptor) {
		ItemStack output = new ItemStack(CBItems.SONIC_DOG_CANNON.get());
		recipe.upgrade().install(output);
		ingredientAcceptor.addItemStack(output);
	}
}
