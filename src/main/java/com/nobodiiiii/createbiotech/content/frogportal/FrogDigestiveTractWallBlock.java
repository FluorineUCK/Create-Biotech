package com.nobodiiiii.createbiotech.content.frogportal;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An upright digestive-tract wall generated around a Frog Stomach exit. Each wall block accepts one
 * slimeball; filling the twelfth block creates the 3x3 {@link FrogDigestiveTractBlock} field.
 */
public class FrogDigestiveTractWallBlock extends Block {

	public static final BooleanProperty HAS_SLIME = BooleanProperty.create("slime");
	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

	private static final VoxelShape NORTH_BASE_SHAPE = Block.box(0.0, 0.0, 3.0, 16.0, 16.0, 16.0);
	private static final VoxelShape NORTH_SLIME_SHAPE = Block.box(4.0, 4.0, -5.0, 12.0, 12.0, 3.0);
	private static final VoxelShape SOUTH_BASE_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 13.0);
	private static final VoxelShape SOUTH_SLIME_SHAPE = Block.box(4.0, 4.0, 13.0, 12.0, 12.0, 21.0);
	private static final VoxelShape WEST_BASE_SHAPE = Block.box(3.0, 0.0, 0.0, 16.0, 16.0, 16.0);
	private static final VoxelShape WEST_SLIME_SHAPE = Block.box(-5.0, 4.0, 4.0, 3.0, 12.0, 12.0);
	private static final VoxelShape EAST_BASE_SHAPE = Block.box(0.0, 0.0, 0.0, 13.0, 16.0, 16.0);
	private static final VoxelShape EAST_SLIME_SHAPE = Block.box(13.0, 4.0, 4.0, 21.0, 12.0, 12.0);

	public FrogDigestiveTractWallBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any()
			.setValue(HAS_SLIME, false)
			.setValue(FACING, Direction.NORTH));
	}

	@Nullable
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack heldItem, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hit) {
		if (!heldItem.is(Items.SLIME_BALL) || state.getValue(HAS_SLIME))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (level.isClientSide)
			return ItemInteractionResult.SUCCESS;

		BlockState filledState = state.setValue(HAS_SLIME, true);
		Block.pushEntitiesUp(state, filledState, level, pos);
		level.setBlock(pos, filledState, Block.UPDATE_CLIENTS);
		level.updateNeighbourForOutputSignal(pos, this);
		heldItem.shrink(1);
		level.playSound(null, pos, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f);
		if (level instanceof ServerLevel serverLevel)
			FrogStomachSpace.tryActivatePortal(serverLevel, pos);
		return ItemInteractionResult.CONSUME;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		VoxelShape base = switch (state.getValue(FACING)) {
			case SOUTH -> SOUTH_BASE_SHAPE;
			case WEST -> WEST_BASE_SHAPE;
			case EAST -> EAST_BASE_SHAPE;
			default -> NORTH_BASE_SHAPE;
		};
		if (!state.getValue(HAS_SLIME))
			return base;
		VoxelShape slime = switch (state.getValue(FACING)) {
			case SOUTH -> SOUTH_SLIME_SHAPE;
			case WEST -> WEST_SLIME_SHAPE;
			case EAST -> EAST_SLIME_SHAPE;
			default -> NORTH_SLIME_SHAPE;
		};
		return Shapes.or(base, slime);
	}

	@Override
	protected boolean useShapeForLightOcclusion(BlockState state) {
		return true;
	}

	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
		return state.getValue(HAS_SLIME) ? 15 : 0;
	}

	@Override
	protected boolean isPathfindable(BlockState state, PathComputationType pathType) {
		return false;
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return rotate(state, mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HAS_SLIME, FACING);
	}

	@Override
	public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, float fallDistance) {
		entity.causeFallDamage(fallDistance, 0.0f, level.damageSources().fall());
	}
}
