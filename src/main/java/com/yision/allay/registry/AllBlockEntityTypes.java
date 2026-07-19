package com.yision.allay.registry;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.yision.allay.block.allayport.AllayPortBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class AllBlockEntityTypes {
	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AllayPortBlockEntity>> ALLAY_PORT =
		CBBlockEntityTypes.ALLAY_PORT;

	private AllBlockEntityTypes() {}
}
