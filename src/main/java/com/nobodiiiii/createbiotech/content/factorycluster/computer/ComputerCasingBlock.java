package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.Optional;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingSelection;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
import com.simibubi.create.content.decoration.encasing.CasingBlock;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;

public class ComputerCasingBlock extends CasingBlock {
	public ComputerCasingBlock(Properties properties) { super(properties); }

	@Override
	public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
		if (!(context.getPlayer() instanceof ServerPlayer serverPlayer))
			return InteractionResult.SUCCESS;
		Optional<ClusterMember> source = ClusterBindingSelection.resolve(serverPlayer);
		if (source.isEmpty())
			return super.onSneakWrenched(state, context);
		Optional<ComputerCoordinatorMember> coordinator =
			ComputerStructureLocator.findCoordinator(serverPlayer.serverLevel(),
				context.getClickedPos(), ComputerStructureScanner.Limits.fromConfig());
		if (coordinator.isEmpty()) return InteractionResult.FAIL;
		ClusterBindingService.BindResult result = ClusterBindingService.bind(
			serverPlayer, source.get(), coordinator.get());
		return result.succeeded() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
	}
}
