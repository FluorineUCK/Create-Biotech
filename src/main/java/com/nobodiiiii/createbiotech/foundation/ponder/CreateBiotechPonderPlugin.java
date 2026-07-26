package com.nobodiiiii.createbiotech.foundation.ponder;

import com.nobodiiiii.createbiotech.infrastructure.ponder.AllCreateBiotechPonderScenes;
import com.nobodiiiii.createbiotech.infrastructure.ponder.AllCreateBiotechPonderTags;
import com.simibubi.create.Create;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public class CreateBiotechPonderPlugin implements PonderPlugin {

	@Override
	public String getModId() {
		// Mount Smart Super Glue onto Create's own super_glue scene and lang keys.
		return Create.ID;
	}

	@Override
	public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
		AllCreateBiotechPonderScenes.register(helper);
	}

	@Override
	public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
		AllCreateBiotechPonderTags.register(helper);
	}
}
