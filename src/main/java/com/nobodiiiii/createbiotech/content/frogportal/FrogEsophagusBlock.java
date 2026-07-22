package com.nobodiiiii.createbiotech.content.frogportal;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Frog Esophagus (青蛙食道) — the indestructible return portal placed inside every Frog Stomach
 * room. Lingering inside it sends an entity back to where it last entered this room from (recorded
 * in {@link FrogStomachSavedData}). Not obtainable as an item; generated with the room.
 */
public class FrogEsophagusBlock extends Block implements EntityBlock, Portal {

	public FrogEsophagusBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FrogPortalBehaviour.AXIS, Direction.Axis.X));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new FrogEsophagusBlockEntity(pos, state);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return FrogPortalBehaviour.getShape(state, level, pos, context);
	}

	@Nullable
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return FrogPortalBehaviour.getStateForPlacement(defaultBlockState(), context);
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
		FrogPortalBehaviour.entityInside(this, state, level, pos, entity);
	}

	@Override
	public int getPortalTransitionTime(ServerLevel level, Entity entity) {
		return FrogPortalBehaviour.getPortalTransitionTime(level, entity);
	}

	@Nullable
	@Override
	public DimensionTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos pos) {
		long spaceIndex = getSpaceIndex(level, pos);
		FrogStomachSavedData.Location loc = spaceIndex >= 0
			? FrogStomachSavedData.get(level.getServer()).getReturn(entity.getUUID(), spaceIndex)
			: null;

		ServerLevel dest = null;
		Vec3 target;
		if (loc != null)
			dest = level.getServer().getLevel(loc.dimension());
		if (dest != null) {
			target = Vec3.atBottomCenterOf(loc.pos().above());
		} else {
			dest = level.getServer().overworld();
			target = Vec3.atBottomCenterOf(dest.getSharedSpawnPos());
		}

		return FrogPortalBehaviour.transitionTo(dest, entity, target);
	}

	private long getSpaceIndex(Level level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof FrogEsophagusBlockEntity esophagus)
			return esophagus.getSpaceIndex();
		return FrogStomachSpace.spaceIndexFromEsophagusPos(pos);
	}

	@Override
	public Transition getLocalTransition() {
		return FrogPortalBehaviour.getLocalTransition();
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		FrogPortalBehaviour.animateTick(state, level, pos, random);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return FrogPortalBehaviour.rotate(state, rotation);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FrogPortalBehaviour.AXIS);
	}
}
