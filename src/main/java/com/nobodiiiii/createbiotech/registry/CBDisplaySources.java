package com.nobodiiiii.createbiotech.registry;

import com.simibubi.create.AllDisplaySources;
import com.simibubi.create.api.behaviour.display.DisplaySource;

public final class CBDisplaySources {
	private static final net.neoforged.neoforge.registries.DeferredRegister<DisplaySource> SOURCES =
        net.neoforged.neoforge.registries.DeferredRegister.create(com.simibubi.create.api.registry.CreateRegistries.DISPLAY_SOURCE, "create_biotech");
    public static final java.util.function.Supplier<com.nobodiiiii.createbiotech.content.biocomputing.device.display.TextOutputDisplaySource> TEXT_OUTPUT =
        SOURCES.register("biocomputing_text_output", com.nobodiiiii.createbiotech.content.biocomputing.device.display.TextOutputDisplaySource::new);
    public static void register(net.neoforged.bus.api.IEventBus bus) { SOURCES.register(bus); }
    private static boolean registered;

	private CBDisplaySources() {}

	public static void register() {
		if (registered)
			return;
		DisplaySource.BY_BLOCK.add(CBBlocks.SLIME_BELT.get(), AllDisplaySources.ITEM_NAMES.get());
		DisplaySource.BY_BLOCK.add(CBBlocks.MAGMA_BELT.get(), AllDisplaySources.ITEM_NAMES.get());
		DisplaySource.BY_BLOCK.add(CBBlocks.TEXT_OUTPUT.get(), TEXT_OUTPUT.get());
		registered = true;
	}
}
