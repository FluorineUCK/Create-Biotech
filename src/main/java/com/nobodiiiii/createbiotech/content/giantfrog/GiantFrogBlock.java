package com.nobodiiiii.createbiotech.content.giantfrog;

import java.util.List;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBDataComponents;
import com.nobodiiiii.createbiotech.registry.CBItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class GiantFrogBlock extends BaseEntityBlock {
	public static final MapCodec<GiantFrogBlock> CODEC = simpleCodec(GiantFrogBlock::new);
	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
	public static final IntegerProperty X_OFFSET = IntegerProperty.create("x", 0, 2);
	public static final IntegerProperty Y_OFFSET = IntegerProperty.create("y", 0, 1);
	public static final IntegerProperty Z_OFFSET = IntegerProperty.create("z", 0, 2);

	public static final float FROG_SCALE = 4.0f;
	public static final double FROG_ENTITY_WIDTH = 0.5d;
	public static final double FROG_ENTITY_HEIGHT = 0.5d;
	public static final double BODY_WIDTH = FROG_ENTITY_WIDTH * FROG_SCALE;
	public static final double BODY_HEIGHT = FROG_ENTITY_HEIGHT * FROG_SCALE;

	private static final int OCCUPIED_WIDTH = 3;
	private static final int OCCUPIED_HEIGHT = 2;
	private static final int CENTER_OFFSET = OCCUPIED_WIDTH / 2;
	private static final double PIXELS_PER_BLOCK = 16.0d;

	private static final double BODY_MIN = 0.5d - BODY_WIDTH / 2.0d;
	private static final double BODY_MAX = 0.5d + BODY_WIDTH / 2.0d;
	private static final VoxelShape[] BODY_SHAPES = makeBodyShapes();
	private static final AABB BODY_BOUNDS = new AABB(BODY_MIN, 0.0d, BODY_MIN, BODY_MAX, BODY_HEIGHT, BODY_MAX);
	private static final ThreadLocal<Boolean> PLACING_STRUCTURE = ThreadLocal.withInitial(() -> false);
	private static final ThreadLocal<Boolean> REMOVING_STRUCTURE = ThreadLocal.withInitial(() -> false);

	public GiantFrogBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState()
			.setValue(FACING, Direction.NORTH)
			.setValue(X_OFFSET, CENTER_OFFSET)
			.setValue(Y_OFFSET, 0)
			.setValue(Z_OFFSET, CENTER_OFFSET));
	}

	@Override
	protected MapCodec<? extends GiantFrogBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, X_OFFSET, Y_OFFSET, Z_OFFSET);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockPos pos = context.getClickedPos();
		if (!canPlaceAt(context.getLevel(), pos, context))
			return null;
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide)
			return;
		Long index = stack.get(CBDataComponents.FROG_STOMACH_SPACE.get());
		if (index != null && level.getBlockEntity(pos) instanceof GiantFrogBlockEntity frog)
			frog.setSpaceIndex(index);

		BlockState partState = defaultBlockState().setValue(FACING, state.getValue(FACING));
		PLACING_STRUCTURE.set(true);
		try {
			forEachOccupiedOffset((x, y, z) -> {
				if (x == CENTER_OFFSET && y == 0 && z == CENTER_OFFSET)
					return;
				level.setBlock(pos.offset(x - CENTER_OFFSET, y, z - CENTER_OFFSET),
					partState.setValue(X_OFFSET, x).setValue(Y_OFFSET, y).setValue(Z_OFFSET, z),
					Block.UPDATE_ALL);
			});
		} finally {
			PLACING_STRUCTURE.set(false);
		}
	}

	@Override
	public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level,
		BlockPos pos, BlockPos neighborPos) {
		if (!PLACING_STRUCTURE.get() && !REMOVING_STRUCTURE.get() && !isValidStructure(level, pos, state))
			return Blocks.AIR.defaultBlockState();
		return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
	}

	@Override
	public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		return isMain(state) || PLACING_STRUCTURE.get() || isValidStructure(level, pos, state);
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (!level.isClientSide && !REMOVING_STRUCTURE.get() && !isMain(state))
			removeStructure(level, pos, state, !player.isCreative());
		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		if (state.getBlock() == newState.getBlock())
			return;

		if (!REMOVING_STRUCTURE.get())
			removeStructure(level, pos, state, !isMain(state) && !isMoving);

		super.onRemove(state, level, pos, newState, isMoving);
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
		return getBodyShape(state);
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return getBodyShape(state);
	}

	@Override
	public PushReaction getPistonPushReaction(BlockState state) {
		return PushReaction.BLOCK;
	}

	@Override
	protected boolean isPathfindable(BlockState state, PathComputationType type) {
		return false;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return isMain(state) ? new GiantFrogBlockEntity(pos, state) : null;
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		if (!isMain(state))
			return null;
		return createTickerHelper(type, CBBlockEntityTypes.GIANT_FROG.get(), GiantFrogBlockEntity::tick);
	}

	@Override
	public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		if (!isMain(state))
			return List.of();
		List<ItemStack> drops = super.getDrops(state, builder);
		if (builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof GiantFrogBlockEntity frog)
			drops.forEach(stack -> addSpaceIndex(stack, frog));
		return drops;
	}

	@Override
	public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos,
		Player player) {
		return createDropStack(level, getMainPos(pos, state));
	}

	public static AABB getBodyBounds(BlockPos pos) {
		return BODY_BOUNDS.move(pos);
	}

	private static boolean canPlaceAt(Level level, BlockPos mainPos, BlockPlaceContext context) {
		if (mainPos.getY() + OCCUPIED_HEIGHT > level.getMaxBuildHeight())
			return false;

		for (int y = 0; y < OCCUPIED_HEIGHT; y++) {
			for (int x = 0; x < OCCUPIED_WIDTH; x++) {
				for (int z = 0; z < OCCUPIED_WIDTH; z++) {
					BlockPos partPos = mainPos.offset(x - CENTER_OFFSET, y, z - CENTER_OFFSET);
					if (!level.getBlockState(partPos).canBeReplaced(context))
						return false;
				}
			}
		}
		return true;
	}

	private static boolean isValidStructure(BlockGetter level, BlockPos pos, BlockState state) {
		BlockPos mainPos = getMainPos(pos, state);
		Direction facing = state.getValue(FACING);

		for (int y = 0; y < OCCUPIED_HEIGHT; y++) {
			for (int x = 0; x < OCCUPIED_WIDTH; x++) {
				for (int z = 0; z < OCCUPIED_WIDTH; z++) {
					BlockState partState = level.getBlockState(mainPos.offset(x - CENTER_OFFSET, y, z - CENTER_OFFSET));
					if (!(partState.getBlock() instanceof GiantFrogBlock))
						return false;
					if (partState.getValue(FACING) != facing
						|| partState.getValue(X_OFFSET) != x
						|| partState.getValue(Y_OFFSET) != y
						|| partState.getValue(Z_OFFSET) != z)
						return false;
				}
			}
		}
		return true;
	}

	private static void removeStructure(Level level, BlockPos pos, BlockState state, boolean dropItem) {
		BlockPos mainPos = getMainPos(pos, state);
		if (!level.isClientSide && dropItem)
			Block.popResource(level, mainPos, createDropStack(level, mainPos));

		REMOVING_STRUCTURE.set(true);
		try {
			forEachOccupiedOffset((x, y, z) -> {
				BlockPos partPos = mainPos.offset(x - CENTER_OFFSET, y, z - CENTER_OFFSET);
				BlockState partState = level.getBlockState(partPos);
				if (partState.getBlock() instanceof GiantFrogBlock && getMainPos(partPos, partState).equals(mainPos))
					level.setBlock(partPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL_IMMEDIATE);
			});
		} finally {
			REMOVING_STRUCTURE.set(false);
		}
	}

	private static boolean isMain(BlockState state) {
		return state.getValue(X_OFFSET) == CENTER_OFFSET && state.getValue(Y_OFFSET) == 0
			&& state.getValue(Z_OFFSET) == CENTER_OFFSET;
	}

	private static ItemStack createDropStack(BlockGetter level, BlockPos mainPos) {
		ItemStack stack = new ItemStack(CBItems.GIANT_FROG.get());
		if (level.getBlockEntity(mainPos) instanceof GiantFrogBlockEntity frog)
			addSpaceIndex(stack, frog);
		return stack;
	}

	private static void addSpaceIndex(ItemStack stack, GiantFrogBlockEntity frog) {
		if (frog.hasSpace() && stack.is(CBItems.GIANT_FROG.get()))
			stack.set(CBDataComponents.FROG_STOMACH_SPACE.get(), frog.getSpaceIndex());
	}

	private static BlockPos getMainPos(BlockPos pos, BlockState state) {
		return pos.offset(CENTER_OFFSET - state.getValue(X_OFFSET), -state.getValue(Y_OFFSET),
			CENTER_OFFSET - state.getValue(Z_OFFSET));
	}

	private static VoxelShape getBodyShape(BlockState state) {
		return BODY_SHAPES[shapeIndex(state.getValue(X_OFFSET), state.getValue(Y_OFFSET), state.getValue(Z_OFFSET))];
	}

	private static VoxelShape[] makeBodyShapes() {
		VoxelShape[] shapes = new VoxelShape[OCCUPIED_WIDTH * OCCUPIED_HEIGHT * OCCUPIED_WIDTH];
		forEachOccupiedOffset((x, y, z) -> shapes[shapeIndex(x, y, z)] = createBodyShape(x, y, z));
		return shapes;
	}

	private static VoxelShape createBodyShape(int x, int y, int z) {
		double partX = x - CENTER_OFFSET;
		double partZ = z - CENTER_OFFSET;
		double minX = clampPixel(BODY_MIN - partX);
		double maxX = clampPixel(BODY_MAX - partX);
		double minY = clampPixel(-y);
		double maxY = clampPixel(BODY_HEIGHT - y);
		double minZ = clampPixel(BODY_MIN - partZ);
		double maxZ = clampPixel(BODY_MAX - partZ);
		if (minX >= maxX || minY >= maxY || minZ >= maxZ)
			return Shapes.empty();
		return Block.box(minX, minY, minZ, maxX, maxY, maxZ);
	}

	private static double clampPixel(double blocks) {
		return Math.max(0.0d, Math.min(PIXELS_PER_BLOCK, blocks * PIXELS_PER_BLOCK));
	}

	private static int shapeIndex(int x, int y, int z) {
		return (y * OCCUPIED_WIDTH + z) * OCCUPIED_WIDTH + x;
	}

	private static void forEachOccupiedOffset(OffsetConsumer consumer) {
		for (int y = 0; y < OCCUPIED_HEIGHT; y++)
			for (int x = 0; x < OCCUPIED_WIDTH; x++)
				for (int z = 0; z < OCCUPIED_WIDTH; z++)
					consumer.accept(x, y, z);
	}

	@FunctionalInterface
	private interface OffsetConsumer {
		void accept(int x, int y, int z);
	}
}
