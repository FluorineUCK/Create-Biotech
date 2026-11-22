package com.nobodiiiii.createbiotech.content.biocomputing.block;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreHost;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryRemoval;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryRemovalHost;

import java.util.List;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Physical Library shell; books and application state belong to their own services. */
public class LibraryCoreBlock extends BaseEntityBlock
	implements IWrenchable, CBMultiBlockLifecycle.Part, DataPort {
	@Override public boolean acceptsCable(BlockState state, Direction side) {
		return state.getValue(HALF) == DoubleBlockHalf.LOWER && side == state.getValue(FACING).getOpposite();
	}
	public static final MapCodec<LibraryCoreBlock> CODEC =
		simpleCodec(LibraryCoreBlock::new);
	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
	public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
	private static final VoxelShape LOWER_NORTH_SHAPE = Shapes.or(
		Block.box(1, 0, 1, 15, 2, 15), Block.box(4, 2, 4, 12, 13, 12),
		Block.box(1, 13, 0, 15, 16, 14));
	private static final VoxelShape LOWER_SOUTH_SHAPE = Shapes.or(
		Block.box(1, 0, 1, 15, 2, 15), Block.box(4, 2, 4, 12, 13, 12),
		Block.box(1, 13, 2, 15, 16, 16));
	private static final VoxelShape LOWER_WEST_SHAPE = Shapes.or(
		Block.box(1, 0, 1, 15, 2, 15), Block.box(4, 2, 4, 12, 13, 12),
		Block.box(0, 13, 1, 14, 16, 15));
	private static final VoxelShape LOWER_EAST_SHAPE = Shapes.or(
		Block.box(1, 0, 1, 15, 2, 15), Block.box(4, 2, 4, 12, 13, 12),
		Block.box(2, 13, 1, 16, 16, 15));
	private static final VoxelShape UPPER_SHAPE = Block.box(3, 0, 3, 13, 16, 13);

	public LibraryCoreBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState()
			.setValue(FACING, Direction.NORTH)
			.setValue(HALF, DoubleBlockHalf.LOWER));
	}

	@Override
	protected MapCodec<? extends LibraryCoreBlock> codec() {
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
			&& !(level.getBlockEntity(anchor) instanceof LibraryRemovalHost)) {
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
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
		CollisionContext context) {
		return shapeFor(state);
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
		CollisionContext context) {
		return shapeFor(state);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		if (state.getValue(HALF) != DoubleBlockHalf.LOWER)
			return null;
		BlockEntityType<?> type = CBBlockEntityTypes.PATTERN_STORAGE_CORE.get();
		return type.create(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		BlockEntityType<?> expected = CBBlockEntityTypes.PATTERN_STORAGE_CORE.get();
		if (level.isClientSide || type != expected) return null;
		return (ignoredLevel, ignoredPos, ignoredState, entity) -> {
			if (entity instanceof LibraryCoreHost host) host.tick();
		};
	}

	@Override
	public InteractionResult onWrenched(BlockState state, UseOnContext context) {
		Level level = context.getLevel();
		BlockPos lowerPos = getMultiBlockAnchor(context.getClickedPos(), state);
		BlockState lower = level.getBlockState(lowerPos);
		BlockState upper = level.getBlockState(lowerPos.above());
		if (!isComplete(lower, upper) || context.getClickedFace().getAxis() != Direction.Axis.Y)
			return InteractionResult.PASS;
		Direction rotatedFacing = lower.getValue(FACING)
			.getClockWise(context.getClickedFace().getAxis());
		if (level.isClientSide) return InteractionResult.SUCCESS;
		level.setBlock(lowerPos, lower.setValue(FACING, rotatedFacing), Block.UPDATE_ALL);
		level.setBlock(lowerPos.above(), upper.setValue(FACING, rotatedFacing), Block.UPDATE_ALL);
		IWrenchable.playRotateSound(level, context.getClickedPos());
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
		Level level = context.getLevel();
		if (level instanceof ServerLevel server) {
			if (LibraryRemoval.controlledWrench(level, context.getClickedPos(), server::addFreshEntity))
				IWrenchable.playRemoveSound(level, context.getClickedPos());
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack heldItem, BlockState state, Level level,
		BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		// Let the item dispatch IWrenchable; the default Library menu would consume this click first.
		if (AllItems.WRENCH.isIn(heldItem))
			return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
		if (!CapturedEntityBoxItem.isBox(heldItem)
			|| !CapturedEntityBoxHelper.hasCapturedEntity(heldItem))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (state.getValue(HALF) != DoubleBlockHalf.LOWER)
			return ItemInteractionResult.FAIL;
		if (level.isClientSide)
			return ItemInteractionResult.sidedSuccess(true);
		if (!(player instanceof ServerPlayer)
			|| !(level.getBlockEntity(pos) instanceof LibraryCoreHost core))
			return ItemInteractionResult.FAIL;
		return core.installLibraryResident(player, hand)
				? ItemInteractionResult.sidedSuccess(false) : ItemInteractionResult.FAIL;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
		Player player, BlockHitResult hit) {
		BlockPos anchor = getMultiBlockAnchor(pos, state);
		return level.getBlockEntity(anchor) instanceof LibraryCoreHost host
			? host.useLibrary(player) : InteractionResult.FAIL;
	}

	@Override
	public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		return List.of();
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos,
		BlockState newState, boolean isMoving) {
		if (LibraryRemoval.onRemove(state, level, pos, newState, isMoving,
			entity -> level instanceof ServerLevel server && server.addFreshEntity(entity))
			== LibraryRemoval.RemovalDisposition.CALL_SUPER)
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

	public static boolean isHalf(BlockState state, DoubleBlockHalf half) {
		return state.getBlock() instanceof LibraryCoreBlock
			&& state.getValue(HALF) == half;
	}

	public static boolean isComplete(BlockState lower, BlockState upper) {
		return isHalf(lower, DoubleBlockHalf.LOWER)
			&& isHalf(upper, DoubleBlockHalf.UPPER)
			&& lower.getBlock() == upper.getBlock()
			&& lower.getValue(FACING) == upper.getValue(FACING);
	}

	private static VoxelShape shapeFor(BlockState state) {
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER) return UPPER_SHAPE;
		return switch (state.getValue(FACING)) {
			case NORTH -> LOWER_NORTH_SHAPE;
			case SOUTH -> LOWER_SOUTH_SHAPE;
			case WEST -> LOWER_WEST_SHAPE;
			case EAST -> LOWER_EAST_SHAPE;
			default -> throw new IllegalStateException("pattern core requires horizontal facing");
		};
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
