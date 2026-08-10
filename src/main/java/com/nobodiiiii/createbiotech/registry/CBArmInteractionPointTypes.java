package com.nobodiiiii.createbiotech.registry;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.buttercat.block.ButterCatArmInteraction;
import com.nobodiiiii.createbiotech.content.beltsurface.StandardItemBeltArmInteraction;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;

import net.minecraft.core.Registry;

public final class CBArmInteractionPointTypes {
	private static boolean registered;

	private CBArmInteractionPointTypes() {}

	public static void register() {
		if (registered)
			return;
		registered = true;
		Registry.register(CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE,
			CreateBiotech.asResource("butter_cat_engine"), new ButterCatArmInteraction.Type());
		Registry.register(CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE,
			CreateBiotech.asResource("slime_belt"), new StandardItemBeltArmInteraction.Type(CBBlocks.SLIME_BELT));
		Registry.register(CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE,
			CreateBiotech.asResource("magma_belt"), new StandardItemBeltArmInteraction.Type(CBBlocks.MAGMA_BELT));
	}
}
