package com.nobodiiiii.createbiotech.registry;

import java.util.Set;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import net.neoforged.neoforge.registries.DeferredHolder;

public class CBPoiTypes {

	private static final DeferredRegister<PoiType> POI_TYPES =
		DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, CreateBiotech.MOD_ID);

	public static final ResourceKey<PoiType> TELEPORTATION_KEY =
		ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, CreateBiotech.asResource("teleportation"));

	public static final DeferredHolder<PoiType, PoiType> TELEPORTATION =
		POI_TYPES.register("teleportation",
			() -> new PoiType(
				Set.copyOf(CBFluids.TELEPORTATION_BLOCK.get()
					.getStateDefinition()
					.getPossibleStates()),
				0,
				1));

	private CBPoiTypes() {}

	public static void register(IEventBus modEventBus) {
		POI_TYPES.register(modEventBus);
	}
}
