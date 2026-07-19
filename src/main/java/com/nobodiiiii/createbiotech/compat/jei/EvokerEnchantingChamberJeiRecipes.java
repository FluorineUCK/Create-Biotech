package com.nobodiiiii.createbiotech.compat.jei;

import net.minecraft.core.registries.Registries;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.experience.ExperienceConstants;
import com.nobodiiiii.createbiotech.content.squidprinter.EnchantmentBookCopyItem;
import com.nobodiiiii.createbiotech.registry.CBItems;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

public final class EvokerEnchantingChamberJeiRecipes {

	private EvokerEnchantingChamberJeiRecipes() {
	}

	public static List<EvokerEnchantingChamberJeiRecipe> create() {
		List<EvokerEnchantingChamberJeiRecipe> recipes = new ArrayList<>();
		if (Minecraft.getInstance().level == null)
			return recipes;
		Registry<Enchantment> enchantments = Minecraft.getInstance().level.registryAccess()
			.registryOrThrow(Registries.ENCHANTMENT);
		for (Holder.Reference<Enchantment> enchantmentHolder : enchantments.holders()
			.sorted((left, right) -> left.getKey().location().compareTo(right.getKey().location()))
			.toList()) {
			ResourceLocation enchId = enchantmentHolder.getKey().location();
			Enchantment enchantment = enchantmentHolder.value();
			int maxLevel = Math.max(1, enchantment.getMaxLevel());

			List<ItemStack> outputBooks = IntStream.rangeClosed(1, maxLevel)
				.mapToObj(level -> {
					ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
					var mutable = new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(
						net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
					mutable.set(enchantmentHolder, level);
					book.set(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
					return book;
				})
				.toList();
			List<ItemStack> inputCopies = outputBooks.stream()
				.map(book -> EnchantmentBookCopyItem.fromTemplate(book, CBItems.ENCHANTMENT_BOOK_COPY.get()))
				.toList();
			List<Integer> fluidAmounts = IntStream.rangeClosed(1, maxLevel)
				.map(level -> level * ExperienceConstants.chamberFluidPerLevel())
				.boxed()
				.toList();

			ResourceLocation id = CreateBiotech.asResource(
				"evoker_enchanting_chamber/" + enchId.getNamespace() + "_" + enchId.getPath());
			recipes.add(new EvokerEnchantingChamberJeiRecipe(id, inputCopies, outputBooks, fluidAmounts));
		}
		return recipes;
	}
}
