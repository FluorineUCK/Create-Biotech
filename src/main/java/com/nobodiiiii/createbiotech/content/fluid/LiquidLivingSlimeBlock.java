package com.nobodiiiii.createbiotech.content.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class LiquidLivingSlimeBlock extends LiquidBlock {

	public LiquidLivingSlimeBlock(FlowingFluid fluid, BlockBehaviour.Properties properties) {
		super(fluid, properties);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return state.getFluidState().getShape(level, pos);
	}

	@Override
	public void attack(BlockState state, Level level, BlockPos pos, Player player) {
		if (level instanceof ServerLevel serverLevel)
			LiquidLivingSlimeHitManager.hit(serverLevel, pos, state.getFluidState());
	}

	@Override
	public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
		boolean willHarvest, FluidState fluid) {
		if (level instanceof ServerLevel serverLevel)
			LiquidLivingSlimeHitManager.hit(serverLevel, pos, state.getFluidState());
		return false;
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (level instanceof ServerLevel serverLevel
			&& (!newState.is(this) || !newState.getFluidState().isSource()))
			LiquidLivingSlimeHitManager.forget(serverLevel, pos);
		super.onRemove(state, level, pos, newState, movedByPiston);
	}
}
