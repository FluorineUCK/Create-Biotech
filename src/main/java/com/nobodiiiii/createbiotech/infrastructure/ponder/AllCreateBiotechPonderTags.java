package com.nobodiiiii.createbiotech.infrastructure.ponder;

import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.infrastructure.ponder.AllCreatePonderTags;

import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;

public class AllCreateBiotechPonderTags {

	public static void register(PonderTagRegistrationHelper<ResourceLocation> helper) {
		PonderTagRegistrationHelper<DeferredHolder<?, ?>> HELPER = helper.withKeyFunction(DeferredHolder::getId);

		HELPER.addToTag(AllCreatePonderTags.KINETIC_RELAYS)
			.add(CBItems.BONE_RATCHET)
			.add(CBItems.SLIME_CLUTCH)
			.add(CBItems.UNIVERSAL_JOINT);

		HELPER.addToTag(AllCreatePonderTags.KINETIC_SOURCES)
			.add(CBItems.POWER_BELT_CONNECTOR);

		HELPER.addToTag(AllCreatePonderTags.KINETIC_APPLIANCES)
			.add(CBItems.CREEPER_BLAST_CHAMBER)
			.add(CBItems.MAGMA_BELT_CONNECTOR)
			.add(CBItems.SHULKER_TELEPORTER)
			.add(CBItems.SPIDER_ASSEMBLY_TABLE);

		HELPER.addToTag(AllCreatePonderTags.FLUIDS)
			.add(CBItems.EVOKER_ENCHANTING_CHAMBER)
			.add(CBItems.EXPERIENCE_PUMP)
			.add(CBItems.PETRI_DISH)
			.add(CBItems.SQUID_PRINTER);

		HELPER.addToTag(AllCreatePonderTags.LOGISTICS)
			.add(CBItems.SLIME_BELT_CONNECTOR);

		HELPER.addToTag(AllCreatePonderTags.HIGH_LOGISTICS)
			.add(CBItems.ALLAY_PORT)
			.add(CBItems.SHULKER_PACKAGER);

		HELPER.addToTag(AllCreatePonderTags.REDSTONE)
			.add(CBItems.SCHRODINGERS_CAT);

		HELPER.addToTag(AllCreatePonderTags.MOVEMENT_ANCHOR)
			.add(CBItems.GHAST_HOT_AIR_BALLOON_ASSEMBLY_STATION);

		HELPER.addToTag(AllCreatePonderTags.CONTRAPTION_ACTOR)
			.add(CBItems.BIO_PACKAGER)
			.add(CBItems.GHAST_HELM);

		HELPER.addToTag(AllCreatePonderTags.CONTRAPTION_ASSEMBLY)
			.add(CBItems.SMART_SUPER_GLUE);
	}
}
