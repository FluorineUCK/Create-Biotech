package com.nobodiiiii.createbiotech.content.biocomputing.computer.resident;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerPhysicalHost;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
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
			|| !(event.getPlayer() instanceof ServerPlayer player)
			|| !(event.getState().getBlock() instanceof ComputerBlock)) return;
		BlockPos lowerPos = ComputerBlock.lowerPos(event.getState(), event.getPos());
		BlockState lower = level.getBlockState(lowerPos);
		if (!(lower.getBlock() instanceof ComputerBlock)
			|| lower.getValue(ComputerBlock.HALF)
				!= net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) return;
		// The inner lower-half vanilla break runs under this guard.  Returning here avoids
		// only our redirect recursion; every other BreakEvent listener still sees it.
		if (ComputerResidentLifecycle.isRemovalActive(level, lowerPos)) return;

		boolean upperRequest = !event.getPos().equals(lowerPos);
		ComputerPhysicalHost computer = level.getBlockEntity(lowerPos) instanceof ComputerPhysicalHost value
			&& value.hasResidentSource() ? value : null;
		// Empty lower breaks already are the exact vanilla owner path.
		if (!upperRequest && computer == null) return;

		event.setCanceled(true);
		if (computer != null) {
			ComputerResidentLifecycle.releaseBeforeExternalDestruction(level, lowerPos, lower, computer,
				() -> player.gameMode.destroyBlock(lowerPos));
			return;
		}
		// Upper clicks are redirected through ServerPlayerGameMode, not Level#destroyBlock,
		// preserving protection events, playerWillDestroy/playerDestroy, tool wear and stats.
		player.gameMode.destroyBlock(lowerPos);
	}
}
