package com.nobodiiiii.createbiotech.content.slimebelt;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.magmabelt.MagmaBeltBlock;
import com.nobodiiiii.createbiotech.content.magmabelt.MagmaBeltBlockEntity;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltLoopGeometry.Track;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltSlope;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Uniform view of a belt adjacent to a slime belt — slime, magma or vanilla Create —
 * replacing the {@code instanceof} ladders previously duplicated across the block entity
 * and the inventory's side-transfer scan. Each implementation owns its slope-specific
 * acceptance rules; callers never branch on the concrete belt type.
 */
public interface SlimeBeltNeighbor {

	BeltSlope slope();

	/** True if one of this belt's transport surfaces moves in the given world direction. */
	boolean isMovingToward(Direction face);

	/** True if this neighbour is a segment of the given slime belt loop. */
	boolean sameLoopAs(SlimeBeltBlockEntity controller);

	/**
	 * Insert sides usable for a corner ("side transfer") handoff whose items arrive at
	 * this belt's {@code incomingFace}; empty when the handoff is not accepted.
	 */
	List<Direction> sideTransferInsertSides(Direction incomingFace);

	/**
	 * Side actually passed to this belt's input behaviour for a corner handoff resolved
	 * at {@code cornerSide}. The corner side still selects the contacted track surface.
	 * Once the target is horizontal, however, the insertion itself is an in-line belt
	 * handoff: passing that track's travel direction makes every belt implementation land
	 * at the track entry instead of the segment center. Non-horizontal targets still need
	 * the physical corner side for their height/track placement.
	 */
	default Direction handoffInsertSide(Direction incomingFace, Direction cornerSide) {
		return slope() == BeltSlope.HORIZONTAL ? incomingFace.getOpposite() : cornerSide;
	}

	@Nullable
	static SlimeBeltNeighbor at(Level level, BlockPos pos) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity instanceof SlimeBeltBlockEntity slimeBelt)
			return new AdjacentSlimeBelt(slimeBelt);
		if (blockEntity instanceof MagmaBeltBlockEntity magmaBelt)
			return new AdjacentMagmaBelt(magmaBelt);
		if (blockEntity instanceof BeltBlockEntity belt)
			return new AdjacentVanillaBelt(belt);
		return null;
	}

	/** Corner handoffs connect two perpendicular faces of the target block. */
	static boolean isCornerTransfer(Direction incomingFace, Direction insertSide) {
		return incomingFace.getAxis() != insertSide.getAxis();
	}

	record AdjacentSlimeBelt(SlimeBeltBlockEntity segment) implements SlimeBeltNeighbor {

		@Override
		public BeltSlope slope() {
			return segment.getBlockState()
				.getValue(SlimeBeltBlock.SLOPE);
		}

		@Override
		public boolean isMovingToward(Direction face) {
			if (segment.getSpeed() == 0)
				return false;
			SlimeBeltBlockEntity controller = segment.getControllerBE();
			if (controller == null)
				return false;
			return SlimeBeltHelper.getMovementFacingForTrack(controller, Track.FRONT) == face
				|| SlimeBeltHelper.getMovementFacingForTrack(controller, Track.BACK) == face;
		}

		@Override
		public boolean sameLoopAs(SlimeBeltBlockEntity controller) {
			SlimeBeltBlockEntity ownController = segment.getControllerBE();
			return ownController != null && ownController.getBlockPos()
				.equals(controller.getController());
		}

		@Override
		public List<Direction> sideTransferInsertSides(Direction incomingFace) {
			SlimeBeltBlockEntity controller = segment.getControllerBE();
			if (controller == null)
				return List.of();
			List<Direction> sides = new ArrayList<>();
			for (Direction insertSide : Direction.values()) {
				if (!isCornerTransfer(incomingFace, insertSide))
					continue;
				Track targetTrack = SlimeBeltLoopGeometry.resolveInputTrack(segment.getBlockState(), insertSide);
				if (!SlimeBeltHelper.isTrackClosestToInputSide(controller, segment.index, targetTrack, incomingFace))
					continue;
				if (SlimeBeltHelper.getRepresentativeSideForTrack(controller, segment.index, targetTrack) != insertSide)
					continue;
				if (!canReceive(controller, targetTrack, incomingFace))
					continue;
				sides.add(insertSide);
			}
			return sides;
		}

		private boolean canReceive(SlimeBeltBlockEntity controller, Track targetTrack, Direction incomingFace) {
			BeltSlope slope = slope();
			if (slope != BeltSlope.HORIZONTAL && slope != BeltSlope.VERTICAL)
				return true;
			return SlimeBeltHelper.getMovementFacingForTrack(controller, targetTrack) == incomingFace.getOpposite();
		}
	}

	record AdjacentVanillaBelt(BeltBlockEntity belt) implements SlimeBeltNeighbor {

		@Override
		public BeltSlope slope() {
			return belt.getBlockState()
				.getValue(BeltBlock.SLOPE);
		}

		@Override
		public boolean isMovingToward(Direction face) {
			return belt.getSpeed() != 0 && belt.getMovementFacing() == face;
		}

		@Override
		public boolean sameLoopAs(SlimeBeltBlockEntity controller) {
			return false;
		}

		@Override
		public List<Direction> sideTransferInsertSides(Direction incomingFace) {
			if (!isCornerTransfer(incomingFace, Direction.UP))
				return List.of();
			if (slope() == BeltSlope.HORIZONTAL && belt.getMovementFacing() != incomingFace.getOpposite())
				return List.of();
			return List.of(Direction.UP);
		}
	}

	record AdjacentMagmaBelt(MagmaBeltBlockEntity belt) implements SlimeBeltNeighbor {

		@Override
		public BeltSlope slope() {
			return belt.getBlockState()
				.getValue(MagmaBeltBlock.SLOPE);
		}

		@Override
		public boolean isMovingToward(Direction face) {
			return belt.getSpeed() != 0 && belt.getMovementFacing() == face;
		}

		@Override
		public boolean sameLoopAs(SlimeBeltBlockEntity controller) {
			return false;
		}

		@Override
		public List<Direction> sideTransferInsertSides(Direction incomingFace) {
			if (!isCornerTransfer(incomingFace, Direction.UP))
				return List.of();
			if (slope() == BeltSlope.HORIZONTAL && belt.getMovementFacing() != incomingFace.getOpposite())
				return List.of();
			return List.of(Direction.UP);
		}
	}
}
