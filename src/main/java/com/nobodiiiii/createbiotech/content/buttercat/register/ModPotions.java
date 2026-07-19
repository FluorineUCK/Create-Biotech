package com.nobodiiiii.createbiotech.content.buttercat.register;

import com.nobodiiiii.createbiotech.content.buttercat.ButterCatModule;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;


public class ModPotions {

    public static final DeferredRegister<Potion> POTIONS =
            DeferredRegister.create(Registries.POTION, ButterCatModule.MODID);

    public static final DeferredHolder<Potion, Potion> ROTATION =
            POTIONS.register("rotation_potion", () -> new Potion(new MobEffectInstance(
                    ModEffects.BUTTER_ROTATION_EFFECT,
                    1200,
                    2
            )));

    public static final DeferredHolder<Potion, Potion> SUPER_ROTATION =
            POTIONS.register("super_rotation_potion", () -> new Potion(new MobEffectInstance(
                    ModEffects.BUTTER_ROTATION_EFFECT,
                    3600,
                    4
            )));

    public static void register(IEventBus modBus) {
        POTIONS.register(modBus);
    }
}
