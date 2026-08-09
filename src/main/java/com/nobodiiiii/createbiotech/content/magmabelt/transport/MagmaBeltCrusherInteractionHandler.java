package com.nobodiiiii.createbiotech.content.magmabelt.transport;

import com.nobodiiiii.createbiotech.content.beltsurface.CrusherInteractionCore;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;

public class MagmaBeltCrusherInteractionHandler {

	public static boolean checkForCrushers(MagmaBeltInventory beltInventory, TransportedItemStack currentItem,
										   float nextOffset) {
		return CrusherInteractionCore.check(new MagmaBeltSurfaceTickContext(beltInventory), currentItem, nextOffset);
	}


}
