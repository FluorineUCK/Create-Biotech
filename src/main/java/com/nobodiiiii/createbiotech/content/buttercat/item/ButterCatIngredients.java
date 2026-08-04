package com.nobodiiiii.createbiotech.content.buttercat.item;

import java.util.stream.Stream;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

public final class ButterCatIngredients {
	private static final TagKey<Item> BUTTER = commonTag("butter");
	private static final TagKey<Item> FOOD_BUTTER = commonTag("foods/butter");

	private ButterCatIngredients() {}

	public static Ingredient butters() {
		return Ingredient.fromValues(Stream.of(
			new Ingredient.TagValue(BUTTER),
			new Ingredient.TagValue(FOOD_BUTTER)));
	}

	public static boolean isButter(ItemStack stack) {
		return butters().test(stack);
	}

	public static Ingredient breads() {
		return Ingredient.fromValues(Stream.of(
			new Ingredient.TagValue(commonTag("bread")),
			new Ingredient.TagValue(commonTag("foods/bread")),
			new Ingredient.TagValue(commonTag("wheat")),
			new Ingredient.TagValue(commonTag("foods/wheat")),
			new Ingredient.ItemValue(new ItemStack(Items.BREAD))));
	}

	private static TagKey<Item> commonTag(String path) {
		return ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", path));
	}
}
