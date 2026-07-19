package com.nobodiiiii.createbiotech.registry;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxIngredient;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.crafting.IngredientType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public class CBIngredients {
	private static final DeferredRegister<IngredientType<?>> INGREDIENT_TYPES =
		DeferredRegister.create(NeoForgeRegistries.Keys.INGREDIENT_TYPES, CreateBiotech.MOD_ID);

	public static final DeferredHolder<IngredientType<?>, IngredientType<CapturedEntityBoxIngredient>>
		CAPTURED_ENTITY_BOX = INGREDIENT_TYPES.register("captured_entity_box",
			() -> new IngredientType<>(CapturedEntityBoxIngredient.CODEC));

	private CBIngredients() {
	}

	public static void register(IEventBus modEventBus) {
		INGREDIENT_TYPES.register(modEventBus);
	}
}
