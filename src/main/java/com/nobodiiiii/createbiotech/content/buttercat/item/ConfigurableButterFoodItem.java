package com.nobodiiiii.createbiotech.content.buttercat.item;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.buttercat.item.ButterFoodProperties.Variant;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class ConfigurableButterFoodItem extends Item {
	private final Variant variant;

	public ConfigurableButterFoodItem(Properties properties, Variant variant) {
		super(properties);
		this.variant = variant;
	}

	@Override
	public FoodProperties getFoodProperties(ItemStack stack, @Nullable LivingEntity entity) {
		return ButterFoodProperties.create(variant);
	}

	@Override
	public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity livingEntity) {
		ItemStack result = super.finishUsingItem(stack, level, livingEntity);
		if (!level.isClientSide && variant == Variant.SUPER_BUTTER)
			SuperButterMelody.start(livingEntity);
		return result;
	}
}
