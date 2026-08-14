package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.PushReaction;

public class PatternStorageCoreBlock extends BaseEntityBlock
	implements IWrenchable, CBMultiBlockLifecycle.Part {
	public static final MapCodec<PatternStorageCoreBlock> CODEC =
		simpleCodec(PatternStorageCoreBlock::new);
	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
	public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

	public PatternStorageCoreBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState()
			.setValue(FACING, Direction.NORTH)
			.setValue(HALF, DoubleBlockHalf.LOWER));
	}

	@Override
	protected MapCodec<? extends PatternStorageCoreBlock> codec() {
		return CODEC;
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockPos pos = context.getClickedPos();
		if (!hasSpaceForUpperHalf(context.getLevel(), pos))
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
	public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
		super.onPlace(state, level, pos, oldState, isMoving);
		scheduleStructureCheck(level, pos, state);
	}

	@Override
	public BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
		LevelAccessor level, BlockPos currentPos, BlockPos neighbourPos) {
		scheduleStructureCheck(level, currentPos, state);
		return super.updateShape(state, direction, neighbourState, level, currentPos, neighbourPos);
	}

	@Override
	public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (isValidStructure(level, pos, state))
			return;
		BlockPos anchor = getMultiBlockAnchor(pos, state);
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER
			&& !(level.getBlockEntity(anchor) instanceof PatternStorageCoreBlockEntity)) {
			CBMultiBlockLifecycle.removeSilently(level, pos);
			return;
		}
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
	}

	@Override
	public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER)
			return isHalf(level.getBlockState(pos.below()), DoubleBlockHalf.LOWER);
		BlockPos below = pos.below();
		return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
	}

	@Override
	public PushReaction getPistonPushReaction(BlockState state) {
		return PushReaction.BLOCK;
	}

	@Override
	public BlockPos getMultiBlockAnchor(BlockPos pos, BlockState state) {
		return state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
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
		return RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		if (state.getValue(HALF) != DoubleBlockHalf.LOWER)
			return null;
		return CBBlockEntityTypes.PATTERN_STORAGE_CORE.get().create(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		return level.isClientSide ? null : createTickerHelper(type,
			CBBlockEntityTypes.PATTERN_STORAGE_CORE.get(),
			(ignoredLevel, ignoredPos, ignoredState, core) -> core.tick());
	}

	@Override
	public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
		Level level = context.getLevel();
		if (level instanceof ServerLevel server) {
			if (PatternStorageCoreLifecycle.controlledWrench(level, context.getClickedPos(),
				server::addFreshEntity))
				IWrenchable.playRemoveSound(level, context.getClickedPos());
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos,
		BlockState newState, boolean isMoving) {
		PatternStorageCoreLifecycle.RemovalDisposition disposition =
			PatternStorageCoreLifecycle.onRemove(state, level, pos, newState, isMoving,
				entity -> level instanceof ServerLevel server && server.addFreshEntity(entity));
		if (disposition == PatternStorageCoreLifecycle.RemovalDisposition.CALL_SUPER)
			super.onRemove(state, level, pos, newState, isMoving);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, HALF);
	}

	public static boolean hasSpaceForUpperHalf(Level level, BlockPos lowerPos) {
		return lowerPos.getY() < level.getMaxBuildHeight() - 1
			&& level.getWorldBorder().isWithinBounds(BlockPos.containing(
				SubLevelCompat.toWorld(level, lowerPos, net.minecraft.world.phys.Vec3.atCenterOf(lowerPos.above()))))
			&& level.getBlockState(lowerPos.above()).canBeReplaced();
	}

	static boolean isHalf(BlockState state, DoubleBlockHalf half) {
		return state.getBlock() instanceof PatternStorageCoreBlock
			&& state.getValue(HALF) == half;
	}

	static boolean isComplete(BlockState lower, BlockState upper) {
		return isHalf(lower, DoubleBlockHalf.LOWER)
			&& isHalf(upper, DoubleBlockHalf.UPPER)
			&& lower.getBlock() == upper.getBlock()
			&& lower.getValue(FACING) == upper.getValue(FACING);
	}

	private static boolean isValidStructure(LevelReader level, BlockPos pos, BlockState state) {
		BlockPos anchor = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
		BlockPos upper = anchor.above();
		if (!CBMultiBlockLifecycle.isLoaded(level, anchor)
			|| !CBMultiBlockLifecycle.isLoaded(level, upper))
			return true;
		return isComplete(level.getBlockState(anchor), level.getBlockState(upper))
			&& level.getBlockState(anchor).canSurvive(level, anchor);
	}

	private void scheduleStructureCheck(LevelAccessor level, BlockPos pos, BlockState state) {
		if (level.isClientSide())
			return;
		BlockPos anchor = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
		BlockPos upper = anchor.above();
		if (CBMultiBlockLifecycle.isLoaded(level, anchor)
			&& isHalf(level.getBlockState(anchor), DoubleBlockHalf.LOWER)) {
			CBMultiBlockLifecycle.scheduleValidation(level, anchor, this);
			return;
		}
		if (CBMultiBlockLifecycle.isLoaded(level, upper)
			&& isHalf(level.getBlockState(upper), DoubleBlockHalf.UPPER))
			CBMultiBlockLifecycle.scheduleValidation(level, upper, this);
	}
}
