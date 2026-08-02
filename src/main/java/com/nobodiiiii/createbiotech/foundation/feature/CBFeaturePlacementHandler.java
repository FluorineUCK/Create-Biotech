package com.nobodiiiii.createbiotech.foundation.feature;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class CBFeaturePlacementHandler {
	private CBFeaturePlacementHandler() {}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
		if (!(event.getEntity() instanceof Player))
			return;
		CBFeature feature = CBFeature.forBlock(event.getPlacedBlock().getBlock());
		if (feature != null && !feature.isEnabled())
			event.setCanceled(true);
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		CBFeature feature = CBFeature.forPlaceableItem(event.getItemStack().getItem());
		if (feature != null && !feature.isEnabled()) {
			event.setCanceled(true);
			event.setCancellationResult(InteractionResult.FAIL);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		CBFeature feature = CBFeature.forPlaceableItem(event.getItemStack().getItem());
		if (feature != null && !feature.isEnabled()) {
			event.setCanceled(true);
			event.setCancellationResult(InteractionResult.FAIL);
		}
	}
}
