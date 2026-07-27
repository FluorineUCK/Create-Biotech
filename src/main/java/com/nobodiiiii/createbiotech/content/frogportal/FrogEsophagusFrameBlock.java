package com.nobodiiiii.createbiotech.content.frogportal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An upright End Portal-style frame generated around a Frog Stomach exit. Each frame accepts one
 * slimeball; filling the twelfth frame creates the 3x3 {@link FrogEsophagusBlock} field.
 */
public class FrogEsophagusFrameBlock extends Block {

	public static final BooleanProperty HAS_SLIME = BooleanProperty.create("slime");

	private static final VoxelShape BASE_SHAPE = Block.box(0.0, 0.0, 3.0, 16.0, 16.0, 16.0);
	private static final VoxelShape SLIME_SHAPE = Block.box(4.0, 4.0, 0.0, 12.0, 12.0, 3.0);
	private static final VoxelShape FILLED_SHAPE = Shapes.or(BASE_SHAPE, SLIME_SHAPE);

	public FrogEsophagusFrameBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(HAS_SLIME, false));
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
		// Matches an Eye of Ender being inserted into an End Portal frame.
		level.levelEvent(1503, pos, 0);
		if (level instanceof ServerLevel serverLevel)
			FrogStomachSpace.tryActivatePortal(serverLevel, pos);
		return ItemInteractionResult.CONSUME;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return state.getValue(HAS_SLIME) ? FILLED_SHAPE : BASE_SHAPE;
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
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HAS_SLIME);
	}
}
