package com.nobodiiiii.createbiotech.registry;

import com.simibubi.create.AllDisplaySources;
import com.simibubi.create.api.behaviour.display.DisplaySource;

public final class CBDisplaySources {
	private static boolean registered;

	private CBDisplaySources() {}

	public static void register() {
		if (registered)
			return;
		DisplaySource.BY_BLOCK.add(CBBlocks.SLIME_BELT.get(), AllDisplaySources.ITEM_NAMES.get());
		registered = true;
	}
}
