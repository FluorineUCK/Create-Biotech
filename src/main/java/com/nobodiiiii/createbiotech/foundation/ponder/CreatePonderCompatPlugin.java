package com.nobodiiiii.createbiotech.foundation.ponder;

import com.nobodiiiii.createbiotech.infrastructure.ponder.AllCreateBiotechPonderScenes;
import com.simibubi.create.Create;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/**
 * Registers scenes that deliberately reuse Create's own scene IDs and language keys.
 */
public class CreatePonderCompatPlugin implements PonderPlugin {

	@Override
	public String getModId() {
		return Create.ID;
	}

	@Override
	public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
		AllCreateBiotechPonderScenes.registerCreateCompat(helper);
	}
}
