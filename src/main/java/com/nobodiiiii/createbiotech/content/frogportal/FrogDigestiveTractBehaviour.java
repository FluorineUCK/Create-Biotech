package com.nobodiiiii.createbiotech.content.frogportal;

import com.nobodiiiii.createbiotech.registry.CBParticleTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class FrogDigestiveTractBehaviour {

	static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
	private static final VoxelShape X_AXIS_AABB = Block.box(0.0, 0.0, 6.0, 16.0, 16.0, 10.0);
	private static final VoxelShape Z_AXIS_AABB = Block.box(6.0, 0.0, 0.0, 10.0, 16.0, 16.0);

	private FrogDigestiveTractBehaviour() {}

	static VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return state.getValue(AXIS) == Direction.Axis.Z ? Z_AXIS_AABB : X_AXIS_AABB;
	}

	static BlockState getStateForPlacement(BlockState defaultState, BlockPlaceContext context) {
		Direction.Axis facingAxis = context.getHorizontalDirection().getAxis();
		Direction.Axis portalAxis = facingAxis == Direction.Axis.Z ? Direction.Axis.X : Direction.Axis.Z;
		return defaultState.setValue(AXIS, portalAxis);
	}

	static void entityInside(Portal portal, BlockState state, Level level, BlockPos pos, Entity entity) {
		if (entity.canUsePortal(false))
			entity.setAsInsidePortal(portal, pos);
	}

	static int getPortalTransitionTime(ServerLevel level, Entity entity) {
		return 0;
	}

	static Portal.Transition getLocalTransition() {
		return Portal.Transition.NONE;
	}

	public static DimensionTransition transitionTo(ServerLevel level, Entity entity, Vec3 pos) {
		return transitionTo(level, entity, pos, entity.getDeltaMovement());
	}

	public static DimensionTransition transitionTo(ServerLevel level, Entity entity, Vec3 pos, Vec3 deltaMovement) {
		BlockPos ticketPos = BlockPos.containing(pos);
		DimensionTransition.PostDimensionTransition postTransition =
			DimensionTransition.PLAY_PORTAL_SOUND.then(transitionedEntity -> transitionedEntity.placePortalTicket(ticketPos));
		return new DimensionTransition(level, pos, deltaMovement, entity.getYRot(), entity.getXRot(),
			postTransition);
	}

	static BlockState rotate(BlockState state, Rotation rot) {
		return switch (rot) {
			case COUNTERCLOCKWISE_90, CLOCKWISE_90 -> state.setValue(AXIS,
				state.getValue(AXIS) == Direction.Axis.Z ? Direction.Axis.X : Direction.Axis.Z);
			default -> state;
		};
	}

	static void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (random.nextInt(100) == 0) {
			level.playLocalSound((double) pos.getX() + 0.5, (double) pos.getY() + 0.5,
				(double) pos.getZ() + 0.5, SoundEvents.SLIME_BLOCK_HIT, SoundSource.BLOCKS, 0.5f,
				random.nextFloat() * 0.4f + 0.8f, false);
		}

		for (int i = 0; i < 4; i++) {
			double x = (double) pos.getX() + random.nextDouble();
			double y = (double) pos.getY() + random.nextDouble();
			double z = (double) pos.getZ() + random.nextDouble();
			double xSpeed = ((double) random.nextFloat() - 0.5) * 0.5;
			double ySpeed = ((double) random.nextFloat() - 0.5) * 0.5;
			double zSpeed = ((double) random.nextFloat() - 0.5) * 0.5;
			int direction = random.nextInt(2) * 2 - 1;
			if (state.getValue(AXIS) == Direction.Axis.X) {
				z = (double) pos.getZ() + 0.5 + 0.25 * (double) direction;
				zSpeed = (double) (random.nextFloat() * 2.0f * (float) direction);
			} else {
				x = (double) pos.getX() + 0.5 + 0.25 * (double) direction;
				xSpeed = (double) (random.nextFloat() * 2.0f * (float) direction);
			}

			level.addParticle(CBParticleTypes.FROG_PORTAL.get(), x, y, z, xSpeed, ySpeed, zSpeed);
		}
	}
}
