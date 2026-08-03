package com.nobodiiiii.createbiotech.content.schrodingerscat;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class SchrodingersCatBlock extends BaseEntityBlock implements IWrenchable {
	public static final MapCodec<SchrodingersCatBlock> CODEC = simpleCodec(SchrodingersCatBlock::new);

	private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 12, 14);

	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

	public SchrodingersCatBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends SchrodingersCatBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	public BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	public BlockState mirror(BlockState state, Mirror mirror) {
		return rotate(state, mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	public InteractionResult onWrenched(BlockState state, UseOnContext context) {
		if (context.getClickedFace().getAxis() != Direction.Axis.Y)
			return InteractionResult.PASS;
		BlockState rotated = state.setValue(FACING,
			state.getValue(FACING).getClockWise(context.getClickedFace().getAxis()));
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		if (!rotated.canSurvive(level, pos))
			return InteractionResult.PASS;
		if (level.isClientSide())
			return InteractionResult.SUCCESS;
		level.setBlock(pos, rotated, Block.UPDATE_ALL);
		IWrenchable.playRotateSound(level, pos);
		return InteractionResult.SUCCESS;
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new SchrodingersCatBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		return createTickerHelper(type, CBBlockEntityTypes.SCHRODINGERS_CAT.get(), SchrodingersCatBlockEntity::tick);
	}

	@Override
	public boolean isSignalSource(BlockState state) {
		return true;
	}

	@Override
	public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof SchrodingersCatBlockEntity cat))
			return 0;

		Direction front = state.getValue(FACING);

		if (side == front)
			return cat.getOutputSignal();
		return 0;
	}

	@Override
	public boolean canConnectRedstone(BlockState state, BlockGetter world, BlockPos pos, Direction side) {
		if (side == null)
			return false;
		Direction front = state.getValue(FACING);
		return side == front;
	}
}
