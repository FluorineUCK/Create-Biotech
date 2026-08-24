package com.nobodiiiii.createbiotech.registry;

import java.util.Optional;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.fixedcarrotfishingrod.FixedCarrotFishingRodTarget;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CBMemoryModuleTypes {

	private static final DeferredRegister<MemoryModuleType<?>> MEMORY_MODULE_TYPES =
		DeferredRegister.create(Registries.MEMORY_MODULE_TYPE, CreateBiotech.MOD_ID);

	public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<FixedCarrotFishingRodTarget>>
		FIXED_CARROT_FISHING_ROD_TARGET = MEMORY_MODULE_TYPES.register("fixed_carrot_fishing_rod_target",
			() -> new MemoryModuleType<>(Optional.empty()));

	private CBMemoryModuleTypes() {}

	public static void register(IEventBus modEventBus) {
		MEMORY_MODULE_TYPES.register(modEventBus);
	}
}
