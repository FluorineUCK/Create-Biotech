package com.nobodiiiii.createbiotech.content.itemapplication;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;

/**
 * Block entities that keep part of their state when a manual item application swaps their
 * block for another one. Applying a recipe breaks the old block before placing the new one,
 * so state that survives the conversion has to be handed over explicitly.
 */
public interface ManualApplicationStateCarrier {
	/**
	 * Called on the freshly placed block entity with the saved data of the block entity that
	 * was replaced, or null when the previous block carried no state of its own.
	 */
	void restoreManualApplicationState(@Nullable CompoundTag previousData);
}
