package com.nobodiiiii.createbiotech.content.buttercat.register;

import com.nobodiiiii.createbiotech.registry.CBCreativeModeTabs;

import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class ModCreativeModeTabs {
	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CBC_TAB = CBCreativeModeTabs.MAIN;

	private ModCreativeModeTabs() {}

	public static void register(IEventBus eventBus) {}
}
