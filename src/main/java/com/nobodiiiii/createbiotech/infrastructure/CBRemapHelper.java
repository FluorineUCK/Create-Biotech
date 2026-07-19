package com.nobodiiiii.createbiotech.infrastructure;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public class CBRemapHelper {
	private CBRemapHelper() {
	}

	@SubscribeEvent
	public static void remap(RegisterEvent event) {
		Registry<?> registry = event.getRegistry();

		if (registry == Registries.BLOCK) {
			registry.addAlias(CreateBiotech.asResource("experience_pipe"), AllBlocks.FLUID_PIPE.getId());
			registry.addAlias(CreateBiotech.asResource("encased_experience_pipe"), AllBlocks.ENCASED_FLUID_PIPE.getId());
			registry.addAlias(CreateBiotech.asResource("experience_tank"), AllBlocks.FLUID_TANK.getId());
		}

		if (registry == Registries.ITEM) {
			registry.addAlias(CreateBiotech.asResource("experience_pipe"), AllBlocks.FLUID_PIPE.getId());
			registry.addAlias(CreateBiotech.asResource("encased_experience_pipe"), AllBlocks.ENCASED_FLUID_PIPE.getId());
			registry.addAlias(CreateBiotech.asResource("experience_tank"), AllBlocks.FLUID_TANK.getId());
			registry.addAlias(CreateBiotech.asResource("mini_allay"), CBItems.ALLAY_COURIER.getId());
			registry.addAlias(CreateBiotech.asResource("incomplete_mini_allay"),
				CBItems.INCOMPLETE_ALLAY_COURIER.getId());
		}

		if (registry == Registries.BLOCK_ENTITY_TYPE) {
			registry.addAlias(CreateBiotech.asResource("experience_pipe"), AllBlockEntityTypes.FLUID_PIPE.getId());
			registry.addAlias(CreateBiotech.asResource("encased_experience_pipe"),
				AllBlockEntityTypes.ENCASED_FLUID_PIPE.getId());
			registry.addAlias(CreateBiotech.asResource("experience_tank"), AllBlockEntityTypes.FLUID_TANK.getId());
		}
	}
}
