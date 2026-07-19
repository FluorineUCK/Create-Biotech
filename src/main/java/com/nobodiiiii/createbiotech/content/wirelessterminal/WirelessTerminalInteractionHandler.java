package com.nobodiiiii.createbiotech.content.wirelessterminal;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class WirelessTerminalInteractionHandler {

	private WirelessTerminalInteractionHandler() {}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void bindEndermanStockKeeper(EntityInteractSpecific event) {
		Player player = event.getEntity();
		if (!player.isShiftKeyDown() || player.isSpectator())
			return;

		ItemStack heldItem = event.getItemStack();
		if (!(heldItem.getItem() instanceof WirelessTerminalItem terminal))
			return;

		if (event.getTarget() instanceof EnderMan enderman) {
			BlockPos stockTickerPos = StockTickerInteractionHandler.getStockTickerPosition(enderman);
			if (stockTickerPos == null)
				terminal.showBindingHint(player, event.getLevel());
			else
				terminal.bindToEndermanStockKeeper(player, event.getLevel(), heldItem, stockTickerPos);
		} else {
			terminal.clearBindingOrShowHint(player, event.getLevel(), heldItem);
		}
		event.setCancellationResult(InteractionResult.SUCCESS);
		event.setCanceled(true);
	}
}
