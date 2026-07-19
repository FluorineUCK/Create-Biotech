package com.nobodiiiii.createbiotech.content.ghasthotairballoon;

import com.mojang.serialization.MapCodec;
import java.util.List;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
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
import net.minecraft.world.phys.AABB;

public class GhastHotAirBalloonAssemblyStationBlock extends BaseEntityBlock {
	public static final MapCodec<GhastHotAirBalloonAssemblyStationBlock> CODEC =
		simpleCodec(GhastHotAirBalloonAssemblyStationBlock::new);

	public static final DirectionProperty HORIZONTAL_FACING = BlockStateProperties.HORIZONTAL_FACING;
	public GhastHotAirBalloonAssemblyStationBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(HORIZONTAL_FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends GhastHotAirBalloonAssemblyStationBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HORIZONTAL_FACING);
		super.createBlockStateDefinition(builder);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(HORIZONTAL_FACING, context.getHorizontalDirection().getClockWise());
	}

	@Override
	public BlockState rotate(BlockState state, Rotation rot) {
		return state.setValue(HORIZONTAL_FACING, rot.rotate(state.getValue(HORIZONTAL_FACING)));
	}

	@Override
	public BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(HORIZONTAL_FACING)));
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
		return 0;
	}

	@Override
	public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
		return true;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new GhastHotAirBalloonAssemblyStationBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		return createTickerHelper(type, CBBlockEntityTypes.GHAST_HOT_AIR_BALLOON_ASSEMBLY_STATION.get(),
			GhastHotAirBalloonAssemblyStationBlockEntity::tick);
	}

	@Override
	public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
		super.onPlace(state, level, pos, oldState, isMoving);
		updatePoweredState(level, pos);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide)
			return;
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity instanceof GhastHotAirBalloonAssemblyStationBlockEntity station)
			station.setAdvancementOwner(placer);
	}

	@Override
	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
		BlockPos fromPos, boolean isMoving) {
		super.neighborChanged(state, level, pos, neighborBlock, fromPos, isMoving);
		updatePoweredState(level, pos);
	}

	private static void updatePoweredState(Level level, BlockPos pos) {
		if (level.isClientSide)
			return;
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof GhastHotAirBalloonAssemblyStationBlockEntity station)
			station.onNeighborSignalChanged(level.hasNeighborSignal(pos));
	}

	public static boolean isSeatOccupied(Level world, BlockPos stationPos) {
		return !world.getEntitiesOfClass(GhastHotAirBalloonSeatEntity.class, new AABB(stationPos.above()))
			.isEmpty();
	}

	public static boolean canBePickedUp(Entity passenger, boolean allowContraptionPickup) {
		if (!(passenger instanceof Ghast ghast))
			return false;
		if (!ghast.isAlive())
			return false;
		if (ghast.isPassenger())
			return false;
		if (ghast.isVehicle()) {
			if (!allowContraptionPickup)
				return false;
			boolean hasContraption = false;
			for (Entity p : ghast.getPassengers()) {
				if (p instanceof GhastHotAirBalloonEntity gc && gc.isAlive()) {
					hasContraption = true;
				} else {
					return false;
				}
			}
			return hasContraption;
		}
		return true;
	}

	public static void sitDown(Level world, BlockPos stationPos, BlockState state, Ghast ghast) {
		if (world.isClientSide)
			return;
		List<GhastHotAirBalloonEntity> contraptions = new java.util.ArrayList<>();
		for (Entity p : ghast.getPassengers()) {
			if (p instanceof GhastHotAirBalloonEntity gc && gc.isAlive())
				contraptions.add(gc);
		}
		BlockPos seatPos = stationPos.above();
		float yaw = getFacingYaw(state);
		ghast.moveTo(seatPos.getX() + 0.5, seatPos.getY(), seatPos.getZ() + 0.5, yaw, 0f);
		ghast.setYBodyRot(yaw);
		ghast.setYHeadRot(yaw);
		ghast.setNoAi(true);
		CapturedEntityBoxHelper.markAiDisabledByMod(ghast);
		ghast.setPersistenceRequired();
		ghast.setDeltaMovement(0, 0, 0);
		GhastHotAirBalloonSeatEntity seat = new GhastHotAirBalloonSeatEntity(world, stationPos);
		if (!world.addFreshEntity(seat))
			return;
		ghast.startRiding(seat, true);

		if (contraptions.isEmpty())
			return;
		if (!(world.getBlockEntity(stationPos) instanceof GhastHotAirBalloonAssemblyStationBlockEntity station))
			return;
		for (GhastHotAirBalloonEntity gc : contraptions)
			station.dockContraption(gc, yaw);
	}

	private static float getFacingYaw(BlockState state) {
		return state.getValue(HORIZONTAL_FACING)
			.getCounterClockWise()
			.toYRot();
	}

	public static List<Ghast> findGhastsToSeat(Level world, BlockPos stationPos) {
		return world.getEntitiesOfClass(Ghast.class, new AABB(stationPos.above()));
	}
}
