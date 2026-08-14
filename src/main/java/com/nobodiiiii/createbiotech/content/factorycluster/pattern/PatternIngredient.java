package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.Objects;

import javax.annotation.Nullable;

import com.simibubi.create.content.logistics.BigItemStack;

import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

public record PatternIngredient(@Nullable ResourceLocation itemId, @Nullable ResourceLocation tagId,
	int count, DataComponentPredicate components) {
	public PatternIngredient {
		if ((itemId == null) == (tagId == null))
			throw new IllegalArgumentException("Pattern ingredient needs exactly one selector");
		if (count <= 0 || count > BigItemStack.INF)
			throw new IllegalArgumentException("Pattern ingredient count must be positive");
		components = Objects.requireNonNull(components, "components");
	}

	public boolean matches(ItemStack stack) {
		boolean selectorMatches = itemId != null
			? itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))
			: stack.is(TagKey.create(Registries.ITEM, tagId));
		return selectorMatches && components.test(stack);
	}
}
