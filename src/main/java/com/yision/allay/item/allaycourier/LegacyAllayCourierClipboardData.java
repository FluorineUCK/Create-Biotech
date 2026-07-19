package com.yision.allay.item.allaycourier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

final class LegacyAllayCourierClipboardData {
	// Keep the historical key so clipboard data written by older releases can still be recovered.
	private static final String LEGACY_ROOT_KEY = "CreateBiotechMiniAllayClipboard";

	private LegacyAllayCourierClipboardData() {}

	static void recoverClipboard(Player player) {
		CompoundTag persistentData = player.getPersistentData();
		if (!persistentData.contains(LEGACY_ROOT_KEY, Tag.TAG_COMPOUND)) {
			return;
		}

		ItemStackHandler legacyInventory = new ItemStackHandler(1);
		legacyInventory.deserializeNBT(player.registryAccess(), persistentData.getCompound(LEGACY_ROOT_KEY));
		ItemStack clipboard = legacyInventory.getStackInSlot(0).copy();
		persistentData.remove(LEGACY_ROOT_KEY);
		if (!clipboard.isEmpty()) {
			ItemHandlerHelper.giveItemToPlayer(player, clipboard);
		}
	}
}
