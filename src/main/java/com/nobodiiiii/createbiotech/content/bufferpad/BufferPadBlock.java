package com.nobodiiiii.createbiotech.content.bufferpad;

import java.util.List;
import java.util.function.Predicate;

import com.simibubi.create.foundation.block.WrenchableDirectionalBlock;

import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class BufferPadBlock extends WrenchableDirectionalBlock {

	public static final DirectionProperty FACING = BlockStateProperties.FACING;
	private static final int PLACEMENT_HELPER_ID = PlacementHelpers.register(new PlacementHelper());

	private static final VoxelShape DOWN_SHAPE = Block.box(0, 0, 0, 16, 8, 16);
	private static final VoxelShape UP_SHAPE = Block.box(0, 8, 0, 16, 16, 16);
	private static final VoxelShape NORTH_SHAPE = Block.box(0, 0, 0, 16, 16, 8);
	private static final VoxelShape SOUTH_SHAPE = Block.box(0, 0, 8, 16, 16, 16);
	private static final VoxelShape WEST_SHAPE = Block.box(0, 0, 0, 8, 16, 16);
	private static final VoxelShape EAST_SHAPE = Block.box(8, 0, 0, 16, 16, 16);

	public BufferPadBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.DOWN));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack heldItem, BlockState state, Level world, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult ray) {
		if (player.isShiftKeyDown() || !player.mayBuild())
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		IPlacementHelper placementHelper = PlacementHelpers.get(PLACEMENT_HELPER_ID);
		if (!placementHelper.matchesItem(heldItem))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		return placementHelper.getOffset(player, world, state, pos, ray)
			.placeInWorld(world, (BlockItem) heldItem.getItem(), player, hand, ray);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapeFor(state.getValue(FACING));
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapeFor(state.getValue(FACING));
	}

	private static VoxelShape shapeFor(Direction facing) {
		return switch (facing) {
		case DOWN -> DOWN_SHAPE;
		case UP -> UP_SHAPE;
		case NORTH -> NORTH_SHAPE;
		case SOUTH -> SOUTH_SHAPE;
		case WEST -> WEST_SHAPE;
		case EAST -> EAST_SHAPE;
		};
	}

	@MethodsReturnNonnullByDefault
	private static class PlacementHelper implements IPlacementHelper {
		@Override
		public Predicate<ItemStack> getItemPredicate() {
			return itemStack -> itemStack.getItem() instanceof BlockItem blockItem
				&& blockItem.getBlock() instanceof BufferPadBlock;
		}

		@Override
		public Predicate<BlockState> getStatePredicate() {
			return state -> state.getBlock() instanceof BufferPadBlock;
		}

		@Override
		public PlacementOffset getOffset(Player player, Level world, BlockState state, BlockPos pos,
			BlockHitResult ray) {
			List<Direction> directions = IPlacementHelper.orderedByDistanceExceptAxis(pos, ray.getLocation(),
				state.getValue(FACING).getAxis(), dir -> world.getBlockState(pos.relative(dir)).canBeReplaced());

			if (directions.isEmpty())
				return PlacementOffset.fail();

			return PlacementOffset.success(pos.relative(directions.get(0)),
				placedState -> placedState.setValue(FACING, state.getValue(FACING)));
		}
	}
}
