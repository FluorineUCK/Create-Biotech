package com.yision.allay.registry;

import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import com.yision.allay.block.allayport.AllayPortMenu;
import com.yision.allay.item.allaycourier.AllayCourierMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class AllMenuTypes {
	public static final DeferredHolder<MenuType<?>, MenuType<AllayPortMenu>> ALLAY_PORT = CBMenuTypes.ALLAY_PORT;
	public static final DeferredHolder<MenuType<?>, MenuType<AllayCourierMenu>> ALLAY_COURIER = CBMenuTypes.ALLAY_COURIER;

	private AllMenuTypes() {}
}
