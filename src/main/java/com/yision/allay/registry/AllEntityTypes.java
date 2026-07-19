package com.yision.allay.registry;

import com.nobodiiiii.createbiotech.registry.CBEntityTypes;
import com.yision.allay.entity.courier.AllayCourierEntity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class AllEntityTypes {
	public static final DeferredHolder<EntityType<?>, EntityType<AllayCourierEntity>> ALLAY_COURIER =
		CBEntityTypes.ALLAY_COURIER;

	private AllEntityTypes() {}
}
