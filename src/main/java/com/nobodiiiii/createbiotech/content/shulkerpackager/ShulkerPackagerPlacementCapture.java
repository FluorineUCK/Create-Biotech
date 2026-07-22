package com.nobodiiiii.createbiotech.content.shulkerpackager;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** Client-only bridge mixed into ClientLevel without linking client classes from the common item. */
public interface ShulkerPackagerPlacementCapture {

	void createBiotech$capturePlacementSelection(InteractionHand hand, ItemStack placedStack);
}
