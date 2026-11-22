package com.nobodiiiii.createbiotech.content.biocomputing.block;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentLifecycle;

import java.util.List;
import java.util.function.BiConsumer;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerPhysicalHost;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramMenu;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
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
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A two-block computer whose lower half is the sole owner of persistent state. */
public class ComputerBlock extends BaseEntityBlock
	implements IWrenchable, CBMultiBlockLifecycle.Part, DataPort {
	public static final MapCodec<ComputerBlock> CODEC = simpleCodec(ComputerBlock::new);
	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
	public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

	/* Coordinates copied from the supplied biotech_computer.json. Upper boxes are
	 * rebased from source y=23..32 into the upper half's local y=7..16 range. */
	private static final ModelBox[] LOWER_MODEL_BOXES = {
		new ModelBox(2, 2, 2, 14, 3, 14), new ModelBox(0, 0, 0, 16, 2, 16),
		new ModelBox(10, 3, 10, 13, 4, 11), new ModelBox(10, 3, 5, 13, 4, 6),
		new ModelBox(3, 3, 10, 6, 4, 11), new ModelBox(3, 3, 6, 4, 4, 10),
		new ModelBox(3, 3, 5, 6, 4, 6), new ModelBox(12, 3, 6, 13, 4, 10)
	};
	private static final ModelBox[] UPPER_MODEL_BOXES = {
		new ModelBox(0, 14, 0, 16, 16, 16), new ModelBox(2, 13, 2, 14, 14, 14),
		new ModelBox(3, 7, 3, 13, 13, 13)
	};
	private static final VoxelShape[] LOWER_SHAPES = shapesFor(LOWER_MODEL_BOXES);
	private static final VoxelShape[] UPPER_SHAPES = shapesFor(UPPER_MODEL_BOXES);

	public ComputerBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState()
			.setValue(FACING, Direction.NORTH)
			.setValue(HALF, DoubleBlockHalf.LOWER));
	}
	@Override protected MapCodec<? extends ComputerBlock> codec() { return CODEC; }
	@Override public boolean acceptsCable(BlockState state, Direction side) { return state.getValue(HALF) == DoubleBlockHalf.LOWER; }

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockPos lowerPos = context.getClickedPos();
		if (!hasSpaceForUpperHalf(context.getLevel(), lowerPos)) return null;
		return defaultBlockState()
			.setValue(FACING, context.getHorizontalDirection().getOpposite())
			.setValue(HALF, DoubleBlockHalf.LOWER);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer,
		ItemStack stack) {
		if (ownsBlockEntity(state))
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
		BlockPos lowerPos = lowerPos(state, pos);
		if (isValidStructure(level, pos, state)) return;
		if (isHalf(level.getBlockState(lowerPos), DoubleBlockHalf.LOWER))
			level.destroyBlock(lowerPos, true);
		else level.destroyBlock(pos, true);
	}

	@Override
	public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
			BlockState lower = level.getBlockState(pos.below());
			return isHalf(lower, DoubleBlockHalf.LOWER)
				&& lower.getValue(FACING) == state.getValue(FACING);
		}
		BlockPos below = pos.below();
		return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
	}

	@Override public PushReaction getPistonPushReaction(BlockState state) { return PushReaction.BLOCK; }
	@Override public BlockPos getMultiBlockAnchor(BlockPos pos, BlockState state) { return lowerPos(state, pos); }
	@Override public BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}
	@Override public BlockState mirror(BlockState state, Mirror mirror) {
		return rotate(state, mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, HALF);
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack ignored, BlockState state, Level level,
		BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		ItemStack held = player.getItemInHand(hand);
		if (!CapturedEntityBoxItem.isBox(held))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!CapturedEntityBoxHelper.hasCapturedEntity(held))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (level.isClientSide) return ItemInteractionResult.sidedSuccess(true);
		BlockPos ownerPos = lowerPos(state, pos);
		if (!(player instanceof ServerPlayer serverPlayer)
			|| !(level.getBlockEntity(ownerPos) instanceof ComputerPhysicalHost computer))
			return ItemInteractionResult.FAIL;
		return computer.installResident(serverPlayer, hand) == ComputerInstallResult.SUCCESS
			? ItemInteractionResult.sidedSuccess(false) : ItemInteractionResult.FAIL;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
		Player player, BlockHitResult hit) {
		if (level.isClientSide) return InteractionResult.SUCCESS;
		if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
		BlockPos ownerPos = lowerPos(state, pos);
		if (!(level.getBlockEntity(ownerPos) instanceof ComputerPhysicalHost computer)) return InteractionResult.PASS;
		if (player.isShiftKeyDown() && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()) {
			var provider = ComputerProgramMenu.provider(computer.residentOwner());
			serverPlayer.openMenu(provider, provider::writeOpeningData);
			return InteractionResult.SUCCESS;
		}
		return computer.openComputerApplication(serverPlayer) ? InteractionResult.SUCCESS : InteractionResult.PASS;
	}

	@Override
	public InteractionResult onWrenched(BlockState state, UseOnContext context) {
		Level level = context.getLevel();
		BlockPos lowerPos = lowerPos(state, context.getClickedPos());
		BlockState lower = level.getBlockState(lowerPos);
		BlockState upper = level.getBlockState(lowerPos.above());
		if (!isCompletePair(lower, upper) || context.getClickedFace().getAxis() != Direction.Axis.Y)
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
		BlockPos lowerPos = lowerPos(state, context.getClickedPos());
		if (level.isClientSide) return InteractionResult.SUCCESS;
		BlockState lower = level.getBlockState(lowerPos);
		if (!isHalf(lower, DoubleBlockHalf.LOWER)) return InteractionResult.PASS;
		if (level instanceof ServerLevel server
			&& level.getBlockEntity(lowerPos) instanceof ComputerPhysicalHost computer
			&& computer.hasResidentSource()) {
			if (ComputerResidentLifecycle.controlledPlayerBreak(server, lowerPos, lower, computer,
				context.getPlayer(), context.getItemInHand()))
				IWrenchable.playRemoveSound(level, lowerPos);
			return InteractionResult.SUCCESS;
		}
		Player player = context.getPlayer();
		level.destroyBlock(lowerPos, player == null || !player.isCreative());
		IWrenchable.playRemoveSound(level, lowerPos);
		return InteractionResult.SUCCESS;
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
		boolean isMoving) {
		if (state.getBlock() == newState.getBlock()) {
			super.onRemove(state, level, pos, newState, isMoving);
			return;
		}
		BlockPos lowerPos = lowerPos(state, pos);
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
			if (!isMoving && !ComputerResidentLifecycle.isRemovalActive(level, lowerPos)
				&& isHalf(level.getBlockState(lowerPos), DoubleBlockHalf.LOWER))
				level.setBlock(lowerPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			super.onRemove(state, level, pos, newState, isMoving);
			return;
		}
		ComputerPhysicalHost oldComputer = level.getBlockEntity(pos) instanceof ComputerPhysicalHost computer
			? computer : null;
		if (ComputerResidentLifecycle.onRemove(state, level, pos, newState, isMoving, oldComputer)
			== ComputerResidentLifecycle.RemovalDisposition.CALL_SUPER) {
			if (!isMoving) ComputerResidentLifecycle.runGuardedPairRemoval(level, lowerPos,
				() -> removeUpper(level, lowerPos));
			IBE.onRemove(state, level, pos, newState);
			super.onRemove(state, level, pos, newState, isMoving);
		}
	}

	@Override
	public void onExplosionHit(BlockState state, Level level, BlockPos pos, Explosion explosion,
		BiConsumer<ItemStack, BlockPos> dropConsumer) {
		BlockPos lowerPos = lowerPos(state, pos);
		BlockState lower = level.getBlockState(lowerPos);
		ComputerPhysicalHost computer = level.getBlockEntity(lowerPos) instanceof ComputerPhysicalHost owner
			? owner : null;
		if (level.isClientSide || !isHalf(lower, DoubleBlockHalf.LOWER) || computer == null) {
			super.onExplosionHit(state, level, pos, explosion, dropConsumer);
			return;
		}
		ComputerResidentLifecycle.releaseBeforeExternalDestruction(level, lowerPos, lower, computer, () -> {
			super.onExplosionHit(lower, level, lowerPos, explosion, dropConsumer);
			return !isHalf(level.getBlockState(lowerPos), DoubleBlockHalf.LOWER)
				&& !isHalf(level.getBlockState(lowerPos.above()), DoubleBlockHalf.UPPER)
				&& !(level.getBlockEntity(lowerPos) instanceof ComputerPhysicalHost);
		});
	}

	@Override
	public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		if (!ownsBlockEntity(state)) return List.of();
		BlockEntity blockEntity = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
		if (blockEntity instanceof ComputerPhysicalHost computer
			&& ComputerResidentLifecycle.suppressUnguardedOccupiedDrop(computer)) return List.of();
		return super.getDrops(state, builder);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return ownsBlockEntity(state) ? CBBlockEntityTypes.COMPUTER.get().create(pos, state) : null;
	}

	@Override public RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}
	@Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,
		BlockState state, BlockEntityType<T> type) {
		BlockEntityType<?> expected = CBBlockEntityTypes.COMPUTER.get();
		if (!ownsBlockEntity(state) || level.isClientSide || type != expected) return null;
		return (ignoredLevel, ignoredPos, ignoredState, entity) -> {
			if (entity instanceof ComputerPhysicalHost host) host.tick();
		};
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

	public static BlockPos lowerPos(BlockState state, BlockPos pos) {
		return state.getBlock() instanceof ComputerBlock && state.hasProperty(HALF)
			&& state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
	}

	public static boolean ownsBlockEntity(BlockState state) {
		return state.getBlock() instanceof ComputerBlock && state.hasProperty(HALF)
			&& state.getValue(HALF) == DoubleBlockHalf.LOWER;
	}

	static boolean canPlaceUpper(BlockState state) { return state.canBeReplaced(); }

	public static boolean isCompletePair(BlockState lower, BlockState upper) {
		return isHalf(lower, DoubleBlockHalf.LOWER) && isHalf(upper, DoubleBlockHalf.UPPER)
			&& lower.getBlock() == upper.getBlock() && lower.getValue(FACING) == upper.getValue(FACING);
	}

	public static boolean hasSpaceForUpperHalf(Level level, BlockPos lowerPos) {
		return lowerPos.getY() < level.getMaxBuildHeight() - 1
			&& level.getWorldBorder().isWithinBounds(BlockPos.containing(
				SubLevelCompat.toWorld(level, lowerPos, Vec3.atCenterOf(lowerPos.above()))))
			&& canPlaceUpper(level.getBlockState(lowerPos.above()));
	}

	/**
	 * Runs only from canonical four-argument {@link Level#setBlock}; the three-argument overload
	 * delegates there. The full resident transaction preserves the caller's recursion budget.
	 */
	public static UpperReplacementResult replaceOccupiedUpper(Level level, BlockPos upperPos,
		BlockState replacement, int flags, int recursionLeft) {
		if (level.isClientSide) return UpperReplacementResult.PASSTHROUGH;
		BlockState upper = level.getBlockState(upperPos);
		if (!isHalf(upper, DoubleBlockHalf.UPPER) || replacement.getBlock() == upper.getBlock())
			return UpperReplacementResult.PASSTHROUGH;
		BlockPos lowerPos = upperPos.below();
		BlockState lower = level.getBlockState(lowerPos);
		if (!isHalf(lower, DoubleBlockHalf.LOWER)
			|| ComputerResidentLifecycle.isRemovalActive(level, lowerPos)
			|| !(level.getBlockEntity(lowerPos) instanceof ComputerPhysicalHost computer)
			|| !computer.hasResidentSource()) return UpperReplacementResult.PASSTHROUGH;

		boolean committed = ComputerResidentLifecycle.releaseBeforeExternalDestruction(level, lowerPos,
			lower, computer, () -> replacePairFromUpper(level, lowerPos, upperPos, upper,
				replacement, flags, recursionLeft));
		return committed ? UpperReplacementResult.APPLIED : UpperReplacementResult.REJECTED;
	}

	public enum UpperReplacementResult { PASSTHROUGH, APPLIED, REJECTED }

	private static boolean isHalf(BlockState state, DoubleBlockHalf half) {
		return state.getBlock() instanceof ComputerBlock && state.hasProperty(HALF)
			&& state.getValue(HALF) == half;
	}

	private static boolean isValidStructure(LevelReader level, BlockPos pos, BlockState state) {
		BlockPos lowerPos = lowerPos(state, pos);
		BlockPos upperPos = lowerPos.above();
		if (!CBMultiBlockLifecycle.isLoaded(level, lowerPos)
			|| !CBMultiBlockLifecycle.isLoaded(level, upperPos)) return true;
		BlockState lower = level.getBlockState(lowerPos);
		return isCompletePair(lower, level.getBlockState(upperPos)) && lower.canSurvive(level, lowerPos);
	}

	private void scheduleStructureCheck(LevelAccessor level, BlockPos pos, BlockState state) {
		if (level.isClientSide()) return;
		BlockPos lowerPos = lowerPos(state, pos);
		BlockPos upperPos = lowerPos.above();
		if (CBMultiBlockLifecycle.isLoaded(level, lowerPos)
			&& isHalf(level.getBlockState(lowerPos), DoubleBlockHalf.LOWER)) {
			CBMultiBlockLifecycle.scheduleValidation(level, lowerPos, this);
			return;
		}
		if (CBMultiBlockLifecycle.isLoaded(level, upperPos)
			&& isHalf(level.getBlockState(upperPos), DoubleBlockHalf.UPPER))
			CBMultiBlockLifecycle.scheduleValidation(level, upperPos, this);
	}

	private static void removeUpper(Level level, BlockPos lowerPos) {
		BlockPos upperPos = lowerPos.above();
		if (isHalf(level.getBlockState(upperPos), DoubleBlockHalf.UPPER))
			CBMultiBlockLifecycle.removeSilently(level, upperPos);
	}

	private static boolean replacePairFromUpper(Level level, BlockPos lowerPos, BlockPos upperPos,
		BlockState originalUpper, BlockState replacement, int flags, int recursionLeft) {
		/* Replace the upper first under the lower transaction guard. If lower
		 * cleanup cannot complete, put that exact upper back before the lifecycle
		 * rolls its resident release back and restores the lower owner. */
		boolean upperChanged = level.setBlock(upperPos, replacement, flags, recursionLeft);
		if (!upperChanged) return false;
		if (level.setBlock(lowerPos, Blocks.AIR.defaultBlockState(),
			Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS, recursionLeft)
			&& !isHalf(level.getBlockState(lowerPos), DoubleBlockHalf.LOWER)
			&& !(level.getBlockEntity(lowerPos) instanceof ComputerPhysicalHost)) return true;
		level.setBlock(upperPos, originalUpper,
			Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS, recursionLeft);
		return false;
	}

	private static VoxelShape shapeFor(BlockState state) {
		Direction facing = state.getValue(FACING);
		return state.getValue(HALF) == DoubleBlockHalf.LOWER
			? LOWER_SHAPES[facing.get2DDataValue()] : UPPER_SHAPES[facing.get2DDataValue()];
	}

	private static VoxelShape[] shapesFor(ModelBox[] boxes) {
		VoxelShape[] shapes = new VoxelShape[4];
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			VoxelShape shape = Shapes.empty();
			for (ModelBox box : boxes) shape = Shapes.or(shape, box.rotated(facing).shape());
			shapes[facing.get2DDataValue()] = shape;
		}
		return shapes;
	}

	private record ModelBox(double minX, double minY, double minZ,
		double maxX, double maxY, double maxZ) {
		private ModelBox rotated(Direction facing) {
			return switch (facing) {
				case NORTH -> this;
				case EAST -> new ModelBox(minZ, minY, 16 - maxX, maxZ, maxY, 16 - minX);
				case SOUTH -> new ModelBox(16 - maxX, minY, 16 - maxZ,
					16 - minX, maxY, 16 - minZ);
				case WEST -> new ModelBox(16 - maxZ, minY, minX, 16 - minZ, maxY, maxX);
				default -> throw new IllegalArgumentException("computer facing must be horizontal");
			};
		}
		private VoxelShape shape() { return Block.box(minX, minY, minZ, maxX, maxY, maxZ); }
	}
}
