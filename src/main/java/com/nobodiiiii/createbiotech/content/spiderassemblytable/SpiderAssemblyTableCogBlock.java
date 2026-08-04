package com.nobodiiiii.createbiotech.content.spiderassemblytable;

import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.AllShapes;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class SpiderAssemblyTableCogBlock extends HorizontalKineticBlock
	implements IBE<SpiderAssemblyTableCogBlockEntity>, ICogWheel {

	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

	public SpiderAssemblyTableCogBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
	}

	@Override
	public Direction.Axis getRotationAxis(BlockState state) {
		return state.getValue(FACING).getAxis();
	}

	@Override
	public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
		return face.getAxis() == getRotationAxis(state);
	}

	@Override
	public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
		super.onPlace(state, level, pos, oldState, isMoving);
		// Catches cogs that arrive without a table behind them, such as from /setblock.
		SpiderAssemblyTableBlock.scheduleStructureCheck(level, getMainPos(pos, state), pos);
	}

	@Override
	public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level,
		BlockPos pos, BlockPos neighborPos) {
		// Deferred to the scheduled tick so the check never runs on the client and does
		// not read across the chunk border the table may sit behind.
		SpiderAssemblyTableBlock.scheduleStructureCheck(level, getMainPos(pos, state), pos);
		return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
	}

	@Override
	public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		BlockPos mainPos = getMainPos(pos, state);
		if (SpiderAssemblyTableBlock.isValidStructure(level, mainPos, pos))
			return;
		SpiderAssemblyTableBlock.removeStructure(level, mainPos, pos);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
		BlockHitResult hit) {
		return InteractionResult.PASS;
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		// Survival breaks let the scheduled teardown destroy the table so its loot table
		// decides what drops. Creative has to take the table out itself, without drops.
		if (!level.isClientSide && player.isCreative()) {
			BlockPos mainPos = getMainPos(pos, state);
			// Reciprocal check: a table that happens to face this way but belongs to a
			// different cog is a machine of its own and must be left alone.
			if (SpiderAssemblyTableBlock.isValidStructure(level, mainPos, pos))
				CBMultiBlockLifecycle.removeAnchorInCreative(level, mainPos, player);
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		if (state.getBlock() != newState.getBlock())
			SpiderAssemblyTableBlock.scheduleStructureCheck(level, getMainPos(pos, state), pos);
		super.onRemove(state, level, pos, newState, isMoving);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return AllShapes.SMALL_GEAR.get(getRotationAxis(state));
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	public MutableComponent getName() {
		return Component.translatable("block.create_biotech.spider_assembly_table");
	}

	@Override
	public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos,
		Player player) {
		return new ItemStack(CBItems.SPIDER_ASSEMBLY_TABLE.get());
	}

	@Override
	public Class<SpiderAssemblyTableCogBlockEntity> getBlockEntityClass() {
		return SpiderAssemblyTableCogBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends SpiderAssemblyTableCogBlockEntity> getBlockEntityType() {
		return CBBlockEntityTypes.SPIDER_ASSEMBLY_TABLE_COG.get();
	}

	public static boolean isValidCogFor(LevelReader level, BlockPos pos, BlockState mainState) {
		BlockState cogState = level.getBlockState(pos);
		if (!(cogState.getBlock() instanceof SpiderAssemblyTableCogBlock cog))
			return false;
		if (cogState.getValue(FACING) != mainState.getValue(SpiderAssemblyTableBlock.FACING))
			return false;
		return CogWheelBlock.isValidCogwheelPosition(false, level, pos, cog.getRotationAxis(cogState));
	}

	static BlockPos getMainPos(BlockPos pos, BlockState state) {
		return pos.relative(state.getValue(FACING));
	}
}
