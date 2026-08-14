package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class ComputerBreakHandler {
	private ComputerBreakHandler() {}

	@SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
	public static void onBreak(BlockEvent.BreakEvent event) {
		if (!(event.getLevel() instanceof ServerLevel level)
			|| !(event.getState().getBlock() instanceof ComputerBlock)
			|| !(level.getBlockEntity(event.getPos()) instanceof ComputerBlockEntity computer)
			|| !computer.hasResidentSource()) return;
		event.setCanceled(true);
		ComputerResidentLifecycle.controlledPlayerBreak(level, event.getPos(), event.getState(), computer,
			event.getPlayer(), event.getPlayer().getItemInHand(InteractionHand.MAIN_HAND).copy());
	}
}
