package com.nobodiiiii.createbiotech.registry;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.foundation.feature.FeatureEnabledCondition;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class CBRecipeConditions {
	private static final DeferredRegister<MapCodec<? extends ICondition>> CONDITIONS =
		DeferredRegister.create(NeoForgeRegistries.Keys.CONDITION_CODECS, CreateBiotech.MOD_ID);

	public static final DeferredHolder<MapCodec<? extends ICondition>, MapCodec<FeatureEnabledCondition>> FEATURE_ENABLED =
		CONDITIONS.register("feature_enabled", () -> FeatureEnabledCondition.CODEC);

	private CBRecipeConditions() {}

	public static void register(IEventBus modEventBus) {
		CONDITIONS.register(modEventBus);
	}
}
