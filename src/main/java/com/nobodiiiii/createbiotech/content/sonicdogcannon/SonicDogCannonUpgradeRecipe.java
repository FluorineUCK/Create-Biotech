package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import java.util.stream.Stream;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.nobodiiiii.createbiotech.registry.CBRecipeTypes;

import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.Level;

/**
 * Installs one independent upgrade while preserving the cannon's components and all previous
 * upgrades. The smithing template slot is deliberately left empty.
 */
public class SonicDogCannonUpgradeRecipe implements SmithingRecipe {

	private final Ingredient addition;
	private final SonicDogCannonUpgrade upgrade;

	public SonicDogCannonUpgradeRecipe(Ingredient addition, SonicDogCannonUpgrade upgrade) {
		this.addition = addition;
		this.upgrade = upgrade;
	}

	@Override
	public boolean matches(SmithingRecipeInput input, Level level) {
		return matchesIngredients(input);
	}

	@Override
	public ItemStack assemble(SmithingRecipeInput input, HolderLookup.Provider registries) {
		if (!matchesIngredients(input))
			return ItemStack.EMPTY;

		ItemStack result = input.base().copyWithCount(1);
		if (upgrade == SonicDogCannonUpgrade.DOG_COLLAR) {
			DyeColor color = DyeColor.getColor(input.addition());
			if (color == null)
				return ItemStack.EMPTY;
			SonicDogCannonUpgrade.setCollarColor(result, color);
		} else {
			upgrade.install(result);
		}
		return result;
	}

	private boolean matchesIngredients(SmithingRecipeInput input) {
		if (!input.template().isEmpty() || !isBaseIngredient(input.base()) || !addition.test(input.addition()))
			return false;

		if (upgrade != SonicDogCannonUpgrade.DOG_COLLAR)
			return !upgrade.isInstalled(input.base());

		DyeColor color = DyeColor.getColor(input.addition());
		return color != null && (!upgrade.isInstalled(input.base())
			|| SonicDogCannonUpgrade.getCollarColor(input.base()) != color);
	}

	@Override
	public ItemStack getResultItem(HolderLookup.Provider registries) {
		ItemStack result = new ItemStack(CBItems.SONIC_DOG_CANNON.get());
		if (upgrade == SonicDogCannonUpgrade.DOG_COLLAR)
			SonicDogCannonUpgrade.setCollarColor(result, DyeColor.RED);
		else
			upgrade.install(result);
		return result;
	}

	@Override
	public boolean isTemplateIngredient(ItemStack stack) {
		return false;
	}

	@Override
	public boolean isBaseIngredient(ItemStack stack) {
		return stack.is(CBItems.SONIC_DOG_CANNON.get());
	}

	@Override
	public boolean isAdditionIngredient(ItemStack stack) {
		return addition.test(stack);
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return CBRecipeTypes.SONIC_DOG_CANNON_UPGRADE_SERIALIZER.get();
	}

	@Override
	public boolean isIncomplete() {
		return Stream.of(addition).anyMatch(Ingredient::hasNoItems);
	}

	public Ingredient addition() {
		return addition;
	}

	public SonicDogCannonUpgrade upgrade() {
		return upgrade;
	}

	public static class Serializer implements RecipeSerializer<SonicDogCannonUpgradeRecipe> {
		private static final MapCodec<SonicDogCannonUpgradeRecipe> CODEC = RecordCodecBuilder.mapCodec(instance ->
			instance.group(
				Ingredient.CODEC.fieldOf("addition").forGetter(SonicDogCannonUpgradeRecipe::addition),
				SonicDogCannonUpgrade.CODEC.fieldOf("upgrade").forGetter(SonicDogCannonUpgradeRecipe::upgrade))
				.apply(instance, SonicDogCannonUpgradeRecipe::new));
		private static final StreamCodec<RegistryFriendlyByteBuf, SonicDogCannonUpgradeRecipe> STREAM_CODEC =
			StreamCodec.of(Serializer::toNetwork, Serializer::fromNetwork);

		@Override
		public MapCodec<SonicDogCannonUpgradeRecipe> codec() {
			return CODEC;
		}

		@Override
		public StreamCodec<RegistryFriendlyByteBuf, SonicDogCannonUpgradeRecipe> streamCodec() {
			return STREAM_CODEC;
		}

		private static SonicDogCannonUpgradeRecipe fromNetwork(RegistryFriendlyByteBuf buffer) {
			return new SonicDogCannonUpgradeRecipe(
				Ingredient.CONTENTS_STREAM_CODEC.decode(buffer),
				buffer.readEnum(SonicDogCannonUpgrade.class));
		}

		private static void toNetwork(RegistryFriendlyByteBuf buffer, SonicDogCannonUpgradeRecipe recipe) {
			Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.addition);
			buffer.writeEnum(recipe.upgrade);
		}
	}
}
