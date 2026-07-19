package com.nobodiiiii.createbiotech.content.ghasthotairballoon;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllShapes;
import com.simibubi.create.content.contraptions.ContraptionWorld;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class GhastHelmBlock extends HorizontalDirectionalBlock implements IWrenchable, ProperWaterloggedBlock {
	public static final MapCodec<GhastHelmBlock> CODEC = simpleCodec(GhastHelmBlock::new);

	public GhastHelmBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(ControlsBlock.OPEN, false)
			.setValue(WATERLOGGED, false)
			.setValue(ControlsBlock.VIRTUAL, false)
			.setValue(FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends GhastHelmBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder.add(FACING, ControlsBlock.OPEN, WATERLOGGED, ControlsBlock.VIRTUAL));
	}

	@Override
	public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level,
		BlockPos currentPos, BlockPos neighborPos) {
		updateWater(level, state, currentPos);
		if (level instanceof ContraptionWorld)
			return state;
		return state.setValue(ControlsBlock.OPEN, false);
	}

	@Override
	public FluidState getFluidState(BlockState state) {
		return fluidState(state);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockState state = withWater(defaultBlockState(), context);
		Direction horizontalDirection = context.getHorizontalDirection();
		Player player = context.getPlayer();

		state = state.setValue(FACING, horizontalDirection.getOpposite());
		if (player != null && player.isShiftKeyDown())
			state = state.setValue(FACING, horizontalDirection);

		return state;
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return AllShapes.CONTROLS.get(state.getValue(FACING));
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
		CollisionContext context) {
		return AllShapes.CONTROLS_COLLISION.get(state.getValue(FACING));
	}
}
