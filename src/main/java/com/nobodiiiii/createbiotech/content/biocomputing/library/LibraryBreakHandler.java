package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Keeps player breaking on the Core's controlled resident/output transaction. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class LibraryBreakHandler {
	private LibraryBreakHandler() {}

	@SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
	public static void onBreak(BlockEvent.BreakEvent event) {
		BlockState state = event.getState();
		if (!(state.getBlock() instanceof LibraryCoreBlock)) return;
		Level level = (Level) event.getLevel();
		BlockPos anchor = state.getValue(LibraryCoreBlock.HALF)
			== DoubleBlockHalf.LOWER ? event.getPos() : event.getPos().below();
		event.setCanceled(true);
		Player player = event.getPlayer();
		LibraryRemoval.controlledPlayerBreak(level, anchor, player,
			entity -> level instanceof ServerLevel server && server.addFreshEntity(entity));
	}
}
