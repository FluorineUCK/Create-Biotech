package com.nobodiiiii.createbiotech.registry;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CBPotions {
	private static final DeferredRegister<Potion> POTIONS =
		DeferredRegister.create(Registries.POTION, CreateBiotech.MOD_ID);

	public static final DeferredHolder<Potion, Potion> ROTATION = POTIONS.register("rotation_potion",
		() -> new Potion(new MobEffectInstance(CBMobEffects.BUTTER_ROTATION.getDelegate(), 1200, 2)));

	public static final DeferredHolder<Potion, Potion> SUPER_ROTATION = POTIONS.register("super_rotation_potion",
		() -> new Potion(new MobEffectInstance(CBMobEffects.BUTTER_ROTATION.getDelegate(), 3600, 4)));

	private CBPotions() {}

	public static void register(IEventBus modEventBus) {
		POTIONS.register(modEventBus);
	}
}
