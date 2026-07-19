package com.nobodiiiii.createbiotech.infrastructure.ponder;

import net.minecraft.core.registries.Registries;

import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.infrastructure.ponder.AllCreatePonderTags;
import com.simibubi.create.infrastructure.ponder.scenes.ChassisScenes;

import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;

public class AllCreateBiotechPonderScenes {

	public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
		PonderSceneRegistrationHelper<DeferredHolder<?, ?>> HELPER = helper.withKeyFunction(DeferredHolder::getId);
		HELPER.forComponents(CBItems.SMART_SUPER_GLUE)
			.addStoryBoard("super_glue", ChassisScenes::superGlue, AllCreatePonderTags.CONTRAPTION_ASSEMBLY);
	}
}
