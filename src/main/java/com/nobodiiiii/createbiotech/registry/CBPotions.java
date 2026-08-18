package com.nobodiiiii.createbiotech.registry;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class CBPotions {
	private static final DeferredRegister<Potion> POTIONS =
		DeferredRegister.create(Registries.POTION, CreateBiotech.MOD_ID);

	public static final DeferredHolder<Potion, Potion> ROTATION = POTIONS.register("rotation_potion",
		() -> new Potion(new MobEffectInstance(CBMobEffects.BUTTER_ROTATION.getDelegate(), 1200, 2)));

	public static final DeferredHolder<Potion, Potion> LONG_ROTATION = POTIONS.register("long_rotation_potion",
		() -> new Potion(new MobEffectInstance(CBMobEffects.BUTTER_ROTATION.getDelegate(), 3600, 2)));

	public static final DeferredHolder<Potion, Potion> SUPER_ROTATION = POTIONS.register("super_rotation_potion",
		() -> new Potion(new MobEffectInstance(CBMobEffects.BUTTER_ROTATION.getDelegate(), 1200, 4)));

	private CBPotions() {}

	public static void register(IEventBus modEventBus) {
		POTIONS.register(modEventBus);
	}

	@SubscribeEvent
	public static void registerBrewingRecipes(RegisterBrewingRecipesEvent event) {
		PotionBrewing.Builder builder = event.getBuilder();
		builder.addMix(Potions.AWKWARD, CBItems.SUPER_BUTTER.get(), ROTATION);
		builder.addMix(ROTATION, Items.REDSTONE, LONG_ROTATION);
		builder.addMix(ROTATION, Items.GLOWSTONE_DUST, SUPER_ROTATION);
	}
}
