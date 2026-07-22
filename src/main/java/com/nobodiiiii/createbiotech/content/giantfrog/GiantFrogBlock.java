package com.nobodiiiii.createbiotech.content.giantfrog;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class GiantFrogBlock extends BaseEntityBlock {
	public static final MapCodec<GiantFrogBlock> CODEC = simpleCodec(GiantFrogBlock::new);
	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

	public static final float FROG_SCALE = 4.0f;
	public static final double BODY_WIDTH = 3.0d;
	public static final double BODY_HEIGHT = 2.0d;

	private static final double BODY_MIN = 0.5d - BODY_WIDTH / 2.0d;
	private static final double BODY_MAX = 0.5d + BODY_WIDTH / 2.0d;
	private static final VoxelShape SHAPE = Block.box(BODY_MIN * 16.0d, 0, BODY_MIN * 16.0d,
		BODY_MAX * 16.0d, BODY_HEIGHT * 16.0d, BODY_MAX * 16.0d);
	private static final AABB BODY_BOUNDS = new AABB(BODY_MIN, 0.0d, BODY_MIN, BODY_MAX, BODY_HEIGHT, BODY_MAX);

	public GiantFrogBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends GiantFrogBlock> codec() {
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
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new GiantFrogBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		return createTickerHelper(type, CBBlockEntityTypes.GIANT_FROG.get(), GiantFrogBlockEntity::tick);
	}

	public static AABB getBodyBounds(BlockPos pos) {
		return BODY_BOUNDS.move(pos);
	}
}
