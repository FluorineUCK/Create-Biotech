package com.nobodiiiii.createbiotech.content.magmabelt.transport;

import com.nobodiiiii.createbiotech.content.beltsurface.FunnelInteractionCore;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;

public class MagmaBeltFunnelInteractionHandler {

	public static boolean checkForFunnels(MagmaBeltInventory beltInventory, TransportedItemStack currentItem,
										  float nextOffset) {
		return FunnelInteractionCore.check(new MagmaBeltSurfaceTickContext(beltInventory), currentItem, nextOffset);
	}

}
