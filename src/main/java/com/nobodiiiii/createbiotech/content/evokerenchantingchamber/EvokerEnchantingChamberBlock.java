package com.nobodiiiii.createbiotech.content.evokerenchantingchamber;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.foundation.block.CBWrenchHelper;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

public class EvokerEnchantingChamberBlock extends BaseEntityBlock implements IWrenchable {
	public static final MapCodec<EvokerEnchantingChamberBlock> CODEC =
		simpleCodec(EvokerEnchantingChamberBlock::new);

	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
	public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

	public EvokerEnchantingChamberBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState()
			.setValue(FACING, Direction.NORTH)
			.setValue(HALF, DoubleBlockHalf.LOWER));
	}

	@Override
	protected MapCodec<? extends EvokerEnchantingChamberBlock> codec() {
		return CODEC;
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockPos pos = context.getClickedPos();
		Level level = context.getLevel();
		if (!hasSpaceForUpperHalf(level, pos))
			return null;
		if (!level.getWorldBorder().isWithinBounds(pos.above()))
			return null;
		if (!level.getBlockState(pos.above()).canBeReplaced(context))
			return null;
		return defaultBlockState()
			.setValue(FACING, context.getHorizontalDirection().getOpposite())
			.setValue(HALF, DoubleBlockHalf.LOWER);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
	}

	@Override
	public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
		LevelAccessor level, BlockPos currentPos, BlockPos neighborPos) {
		DoubleBlockHalf half = state.getValue(HALF);
		Direction counterpartDirection = half == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN;

		if (direction == counterpartDirection) {
			return neighborState.is(this) && neighborState.getValue(HALF) != half
				? state
				: Blocks.AIR.defaultBlockState();
		}

		if (half == DoubleBlockHalf.LOWER && direction == Direction.DOWN && !state.canSurvive(level, currentPos))
			return Blocks.AIR.defaultBlockState();

		return super.updateShape(state, direction, neighborState, level, currentPos, neighborPos);
	}

	@Override
	public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
			BlockState belowState = level.getBlockState(pos.below());
			return belowState.is(this) && belowState.getValue(HALF) == DoubleBlockHalf.LOWER;
		}

		BlockPos belowPos = pos.below();
		return level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP);
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		// Survival breaks let the lower half be destroyed by the shape cascade below, so
		// its loot table decides what drops. Creative has to take it out itself, without
		// drops, exactly like vanilla's double blocks.
		if (!level.isClientSide() && state.getValue(HALF) == DoubleBlockHalf.UPPER && player.isCreative()) {
			BlockPos lowerPos = pos.below();
			BlockState lowerState = level.getBlockState(lowerPos);
			if (lowerState.is(this) && lowerState.getValue(HALF) == DoubleBlockHalf.LOWER)
				CBMultiBlockLifecycle.removeAnchorInCreative(level, lowerPos, player);
		}

		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	public PushReaction getPistonPushReaction(BlockState state) {
		// Without this a Create contraption would happily pick up one half and leave the
		// other behind, and the leftover half deletes itself without dropping anything.
		return PushReaction.BLOCK;
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
	public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
		return true;
	}

	@Override
	public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1.0f;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		// Only the lower half owns state. The upper half used to carry a hollow proxy
		// block entity purely so pipes could connect to it; that job now belongs to the
		// block-level capability provider in CBCapabilities.
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER)
			return null;
		return CBBlockEntityTypes.EVOKER_ENCHANTING_CHAMBER.get().create(pos, state);
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack heldStack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hit) {
		if (CBWrenchHelper.isWrench(heldStack))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		InteractionResult result = interact(state, level, pos, player, hand, heldStack);
		return result.consumesAction()
			? ItemInteractionResult.sidedSuccess(level.isClientSide)
			: ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
		BlockHitResult hit) {
		return interact(state, level, pos, player, InteractionHand.MAIN_HAND, ItemStack.EMPTY);
	}

	private InteractionResult interact(BlockState state, Level level, BlockPos pos, Player player,
		InteractionHand hand, ItemStack heldStack) {
		EvokerEnchantingChamberBlockEntity blockEntity = getChamberBlockEntity(level, pos, state);
		if (blockEntity == null)
			return InteractionResult.PASS;

		if (level.isClientSide)
			return blockEntity.canInteract(heldStack) ? InteractionResult.SUCCESS : InteractionResult.PASS;

		if (blockEntity.tryInsertFromPlayer(player, hand, heldStack))
			return InteractionResult.CONSUME;
		if (blockEntity.tryExtractToPlayer(player)) {
			level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.2f, 1f);
			return InteractionResult.CONSUME;
		}
		return InteractionResult.PASS;
	}

	@Override
	public InteractionResult onWrenched(BlockState state, UseOnContext context) {
		Level level = context.getLevel();
		BlockPos clickedPos = context.getClickedPos();
		BlockPos lowerPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? clickedPos : clickedPos.below();
		BlockPos upperPos = lowerPos.above();
		BlockState lowerState = level.getBlockState(lowerPos);
		BlockState upperState = level.getBlockState(upperPos);
		if (!isCompleteStructure(lowerState, upperState))
			return IWrenchable.super.onWrenched(state, context);
		if (context.getClickedFace().getAxis() != Direction.Axis.Y)
			return InteractionResult.PASS;

		Direction rotatedFacing = lowerState.getValue(FACING)
			.getClockWise(context.getClickedFace().getAxis());
		BlockState rotatedLower = lowerState.setValue(FACING, rotatedFacing);
		BlockState rotatedUpper = upperState.setValue(FACING, rotatedFacing);
		if (!rotatedLower.canSurvive(level, lowerPos) || !rotatedUpper.canSurvive(level, upperPos))
			return InteractionResult.PASS;
		if (level.isClientSide())
			return InteractionResult.SUCCESS;

		level.setBlock(lowerPos, rotatedLower, Block.UPDATE_ALL);
		level.setBlock(upperPos, rotatedUpper, Block.UPDATE_ALL);
		IWrenchable.playRotateSound(level, clickedPos);
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
		Level level = context.getLevel();
		BlockPos clickedPos = context.getClickedPos();
		BlockPos lowerPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? clickedPos : clickedPos.below();
		BlockPos upperPos = lowerPos.above();
		BlockState lowerState = level.getBlockState(lowerPos);
		BlockState upperState = level.getBlockState(upperPos);
		if (!isCompleteStructure(lowerState, upperState))
			return IWrenchable.super.onSneakWrenched(state, context);
		if (!(level instanceof ServerLevel serverLevel))
			return InteractionResult.SUCCESS;

		Player player = context.getPlayer();
		BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, clickedPos, state, player);
		NeoForge.EVENT_BUS.post(event);
		if (event.isCanceled())
			return InteractionResult.SUCCESS;

		if (player != null && !player.isCreative()) {
			Block.getDrops(lowerState, serverLevel, lowerPos, level.getBlockEntity(lowerPos), player,
				context.getItemInHand()).forEach(player.getInventory()::placeItemBackInInventory);
		}
		lowerState.spawnAfterBreak(serverLevel, lowerPos, ItemStack.EMPTY, true);
		level.setBlock(upperPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL_IMMEDIATE);
		level.setBlock(lowerPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL_IMMEDIATE);
		IWrenchable.playRemoveSound(level, lowerPos);
		return InteractionResult.SUCCESS;
	}

	private static boolean isCompleteStructure(BlockState lowerState, BlockState upperState) {
		return lowerState.getBlock() instanceof EvokerEnchantingChamberBlock
			&& upperState.getBlock() instanceof EvokerEnchantingChamberBlock
			&& lowerState.getValue(HALF) == DoubleBlockHalf.LOWER
			&& upperState.getValue(HALF) == DoubleBlockHalf.UPPER
			&& lowerState.getValue(FACING) == upperState.getValue(FACING);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		return state.getValue(HALF) == DoubleBlockHalf.LOWER
			? createTickerHelper(type, CBBlockEntityTypes.EVOKER_ENCHANTING_CHAMBER.get(),
				EvokerEnchantingChamberBlockEntity::tick)
			: null;
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		if (!state.is(newState.getBlock()) && state.getValue(HALF) == DoubleBlockHalf.LOWER) {
			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (blockEntity instanceof EvokerEnchantingChamberBlockEntity chamber)
				chamber.dropContentsAndFluid();
		}
		super.onRemove(state, level, pos, newState, isMoving);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, HALF);
	}

	private static EvokerEnchantingChamberBlockEntity getChamberBlockEntity(Level level, BlockPos pos,
		BlockState state) {
		BlockPos basePos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
		BlockEntity blockEntity = level.getBlockEntity(basePos);
		return blockEntity instanceof EvokerEnchantingChamberBlockEntity chamber ? chamber : null;
	}

	public static boolean hasSpaceForUpperHalf(Level level, BlockPos lowerPos) {
		if (lowerPos.getY() >= level.getMaxBuildHeight() - 1)
			return false;
		return level.getBlockState(lowerPos.above()).canBeReplaced();
	}
}
