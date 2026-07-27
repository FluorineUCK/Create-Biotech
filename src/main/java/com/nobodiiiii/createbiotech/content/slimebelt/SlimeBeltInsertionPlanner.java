package com.nobodiiiii.createbiotech.content.slimebelt;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltLoopGeometry.LoopSection;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltLoopGeometry.MotionFrame;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltLoopGeometry.Track;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Single resolver for every way an item enters a slime belt loop. One call produces an
 * {@link InsertionPlan} that both the occupancy gate and the landing consume, so the
 * blocking probe and the actual landing can never disagree. Absorbs the side
 * normalisation previously on the block entity, the IO-target resolution from the
 * helper, and the chain/HV landing predicates from the inventory.
 */
public final class SlimeBeltInsertionPlanner {

	/**
	 * General-path bias along motion direction. Items land at segment center shifted
	 * backward by this amount, leaving room for forward motion.
	 */
	private static final float INSERT_MOTION_BIAS = 1f / 16f;
	/**
	 * Extra backward shift on the smooth chain-continuation path when the incoming stack
	 * came from an adjacent slime belt behind us; renders items seamlessly across the seam.
	 */
	private static final float INSERT_CHAIN_CONTINUATION_BIAS = .26f;
	/**
	 * Cross-axis side offset when input arrives from a face that is neither the
	 * FRONT/BACK track surface nor the movement direction.
	 */
	private static final float INSERT_OFF_TRACK_SIDE_OFFSET = .675f;

	/**
	 * @param track              track the item logically enters
	 * @param occupancyConnector when set, the occupancy gate additionally requires this
	 *                           whole turn to be empty (HV landings within one spacing of
	 *                           the track exit; stack-independent)
	 * @param landingLoopPosition loop position the item lands at — also the blocking
	 *                           probe, keeping gate and landing consistent
	 * @param sideOffset         cross-axis offset to apply, or null to keep the stack's
	 */
	public record InsertionPlan(Track track, @Nullable LoopSection occupancyConnector, float landingLoopPosition,
		@Nullable Float sideOffset, Direction insertedFrom, int insertedAt) {
	}

	private SlimeBeltInsertionPlanner() {}

	@Nullable
	public static InsertionPlan plan(SlimeBeltBlockEntity controller, MotionFrame frame, int segment, Direction side,
		boolean preferEndpointEntryTrack, boolean cameFromBelt) {
		Track track = SlimeBeltHelper.resolveIOTrack(controller, segment, side, preferEndpointEntryTrack);
		if (track == null)
			return null;
		int beltLength = controller.beltLength;
		Direction movementFacing = controller.getMovementFacing();
		boolean verticalSlope = controller.getBlockState()
			.getValue(SlimeBeltBlock.SLOPE) == BeltSlope.VERTICAL;

		// HORIZONTAL → VERTICAL input lands level with the feeding belt's top surface
		// (see hvSurfaceTrackProgress). When that landing sits within one item spacing of
		// the track exit, gate on the exit turn being clear — the arriving item would
		// otherwise overlap one already wrapping. Stack-independent, mirroring the
		// occupancy queries that cannot know the incoming stack.
		boolean hvIntoVertical = side != null && !side.getAxis().isVertical() && verticalSlope;
		LoopSection occupancyConnector = null;
		if (hvIntoVertical && hvSurfaceTrackProgress(controller, frame, segment, track, side) > beltLength - 1)
			occupancyConnector = exitTurnOf(frame, track);

		boolean isFrontChain = side != null && side == movementFacing && track == Track.FRONT;
		boolean isBackChain = side != null && side == movementFacing.getOpposite() && track == Track.BACK;
		boolean isVerticalIntoHorizontalEntry = isVerticalAxisChainIntoHorizontal(controller, segment, track, side);

		float trackProgress;
		if (isFrontChain) {
			// Smooth FRONT chain-continuation: land at the FRONT entry of this segment,
			// extrapolated backward when the prior belt sits directly behind us so items
			// render seamlessly across the seam.
			float continuationBias = cameFromBelt && hasAdjacentBeltSegmentBehind(controller, segment)
				? INSERT_CHAIN_CONTINUATION_BIAS : 0f;
			trackProgress = frame.movementPositive() ? segment - continuationBias
				: beltLength - (segment + 1f + continuationBias);
		} else if (isBackChain || isVerticalIntoHorizontalEntry) {
			// Chain-continuations onto the BACK track (or from a vertical belt into this
			// horizontal loop's entry) land exactly at the track entry. Extrapolating the
			// BACK track backward would overlap the turn arc, so no continuation bias.
			trackProgress = 0f;
		} else if (hvIntoVertical && cameFromBelt) {
			trackProgress = hvSurfaceTrackProgress(controller, frame, segment, track, side);
		} else {
			trackProgress = generalTrackProgress(frame, beltLength, segment, track);
		}

		float landing = frame.loopPositionOfTrackProgress(track, trackProgress);

		Float sideOffset = resolveSideOffset(controller, side, movementFacing);
		return new InsertionPlan(track, occupancyConnector, landing, sideOffset, side, segment);
	}

	/** Plan for direct track landings: entity capture and slicer restoration. */
	public static InsertionPlan planTrackInsertion(SlimeBeltBlockEntity controller, MotionFrame frame, int segment,
		Track track) {
		float landing = frame.loopPositionOfTrackProgress(track,
			generalTrackProgress(frame, controller.beltLength, segment, track));
		Direction side = SlimeBeltHelper.getRepresentativeSideForTrack(controller, segment, track);
		return new InsertionPlan(track, null, landing, null, side, segment);
	}

	// --- Side normalisation (optional front step; the capability path skips it) ---

	/**
	 * Adjacent belts pass their movement direction to DirectBeltInputBehaviour rather
	 * than their physical source side. Recover the physical entry side where that
	 * ambiguity matters — vertical/sideways loops and vertical↔horizontal handoffs —
	 * while leaving funnel semantics untouched.
	 */
	public static Direction resolvePhysicalSide(SlimeBeltBlockEntity segment, Direction side) {
		Level level = segment.getLevel();
		if (side == null || level == null)
			return side;
		Direction physicalSourceSide = side.getOpposite();
		// Belts win over funnels here: a funnel on the opposite face must not mask the
		// vertical-to-horizontal belt handoff.
		if (hasAdjacentHorizontalVerticalBeltSource(segment, physicalSourceSide))
			return physicalSourceSide;
		if (level.getBlockState(segment.getBlockPos()
			.relative(side))
			.getBlock() instanceof BeltFunnelBlock)
			return side;

		if (side.getAxis().isVertical())
			return side;
		BlockState state = segment.getBlockState();
		if (!state.hasProperty(SlimeBeltBlock.SLOPE))
			return side;
		BeltSlope slope = state.getValue(SlimeBeltBlock.SLOPE);
		if (slope != BeltSlope.SIDEWAYS && slope != BeltSlope.VERTICAL)
			return side;

		Direction frontInputSide = SlimeBeltLoopGeometry.frontInputSide(state);
		if (side != frontInputSide && side != frontInputSide.getOpposite())
			return side;

		SlimeBeltNeighbor neighbor = SlimeBeltNeighbor.at(level, segment.getBlockPos()
			.relative(physicalSourceSide));
		if (neighbor != null && neighbor.isMovingToward(physicalSourceSide.getOpposite()))
			return physicalSourceSide;
		return side;
	}

	/** True when a vertical belt hands off directly into this horizontal loop's endpoint. */
	public static boolean isVerticalHorizontalChainInput(SlimeBeltBlockEntity segment, Direction physicalSide) {
		if (physicalSide == null || !physicalSide.getAxis().isVertical())
			return false;
		if (segment.getBlockState()
			.getValue(SlimeBeltBlock.SLOPE) != BeltSlope.HORIZONTAL)
			return false;
		return hasAdjacentHorizontalVerticalBeltSource(segment, physicalSide);
	}

	/**
	 * A chain-axis input from another slime belt must exit that belt on the track our
	 * target track continues; rejects mismatched loop-to-loop couplings.
	 */
	public static boolean isCompatibleAdjacentChainInput(SlimeBeltBlockEntity segment, Direction side,
		SlimeBeltBlockEntity targetController, Track targetTrack) {
		Level level = segment.getLevel();
		if (side == null || level == null || side.getAxis() != SlimeBeltHelper.getChainBlockAxis(targetController))
			return true;
		BlockEntity sourceBE = level.getBlockEntity(segment.getBlockPos()
			.relative(side.getOpposite()));
		if (!(sourceBE instanceof SlimeBeltBlockEntity sourceSegment))
			return true;
		SlimeBeltBlockEntity sourceController = sourceSegment.getControllerBE();
		if (sourceController == null || sourceController.getController()
			.equals(targetController.getController()))
			return true;
		Track sourceTrack = SlimeBeltHelper.getEndpointOutputTrack(sourceController, sourceSegment.index);
		return sourceTrack != null && sourceTrack == targetTrack;
	}

	private static boolean hasAdjacentHorizontalVerticalBeltSource(SlimeBeltBlockEntity segment,
		Direction physicalSide) {
		BeltSlope targetSlope = segment.getBlockState()
			.getValue(SlimeBeltBlock.SLOPE);
		SlimeBeltNeighbor neighbor = SlimeBeltNeighbor.at(segment.getLevel(), segment.getBlockPos()
			.relative(physicalSide));
		if (neighbor == null)
			return false;
		return isHorizontalVerticalPair(targetSlope, neighbor.slope())
			&& neighbor.isMovingToward(physicalSide.getOpposite());
	}

	private static boolean isHorizontalVerticalPair(BeltSlope first, BeltSlope second) {
		return first == BeltSlope.HORIZONTAL && second == BeltSlope.VERTICAL
			|| first == BeltSlope.VERTICAL && second == BeltSlope.HORIZONTAL;
	}

	// --- Landing math ---

	private static float generalTrackProgress(MotionFrame frame, int beltLength, int segment, Track track) {
		float halfSegment = segment + .5f;
		float motionBias = frame.movementPositive() ? -INSERT_MOTION_BIAS : INSERT_MOTION_BIAS;
		float frontOffset = halfSegment + motionBias;
		if (track == Track.FRONT)
			return frame.movementPositive() ? frontOffset : beltLength - frontOffset;
		return frame.movementPositive() ? beltLength - frontOffset : frontOffset;
	}

	/**
	 * Track progress on a vertical belt that lands level with the feeding belt's surface.
	 * Solves {@code worldY(frontOffset) = sourceBlockY + surfaceHeight} using the vertical
	 * belt's {@code worldY = (chainUp ? f : 1 - f) - VERTICAL_BELT_DROP} — independent of
	 * movement direction, unlike the fixed forward push this replaces. Clamped one entry
	 * offset inside the segment so the item stays addressed to it.
	 */
	private static float hvSurfaceTrackProgress(SlimeBeltBlockEntity controller, MotionFrame frame, int segment,
		Track track, Direction side) {
		float surfaceHeight = sourceSurfaceHeight(controller, segment, side);
		float drop = (float) SlimeBeltLoopGeometry.VERTICAL_BELT_DROP;
		boolean chainUp = controller.getBeltFacing()
			.getAxisDirection()
			.getStep() > 0;
		float frontOffset = chainUp ? segment + surfaceHeight + drop
			: segment + 1f - surfaceHeight - drop;
		frontOffset = Mth.clamp(frontOffset, segment + SlimeBeltLoopGeometry.WRAP_ENTRY_OFFSET,
			segment + 1f - SlimeBeltLoopGeometry.WRAP_ENTRY_OFFSET);
		return frame.trackProgressOfFrontOffset(track, frontOffset);
	}

	/**
	 * Height within the source block of the belt surface feeding us: a slime belt whose
	 * BACK track exits toward this segment hands items off at its underside, everything
	 * else (slime FRONT exits, vanilla and magma belts) at its top surface.
	 */
	private static float sourceSurfaceHeight(SlimeBeltBlockEntity controller, int segment, Direction side) {
		float topSurface = .5f + (float) SlimeBeltLoopGeometry.TRACK_SURFACE_OFFSET;
		if (side == null || controller.getLevel() == null)
			return topSurface;
		BlockEntity sourceBE = controller.getLevel()
			.getBlockEntity(SlimeBeltHelper.getPositionForOffset(controller, segment)
				.relative(side));
		if (sourceBE instanceof SlimeBeltBlockEntity sourceSegment) {
			SlimeBeltBlockEntity sourceController = sourceSegment.getControllerBE();
			Track outputTrack = sourceController == null ? null
				: SlimeBeltHelper.getEndpointOutputTrack(sourceController, sourceSegment.index);
			if (outputTrack == Track.BACK)
				return .5f - (float) SlimeBeltLoopGeometry.TRACK_SURFACE_OFFSET;
		}
		return topSurface;
	}

	private static LoopSection exitTurnOf(MotionFrame frame, Track track) {
		return track == Track.FRONT ? frame.exitTurn() : frame.entryTurn();
	}

	private static boolean isVerticalAxisChainIntoHorizontal(SlimeBeltBlockEntity controller, int segment, Track track,
		Direction side) {
		if (side == null || !side.getAxis().isVertical())
			return false;
		if (controller.getBlockState()
			.getValue(SlimeBeltBlock.SLOPE) != BeltSlope.HORIZONTAL)
			return false;
		Track entryTrack = SlimeBeltHelper.getEndpointEntryTrack(controller, segment);
		return entryTrack == track;
	}

	private static boolean hasAdjacentBeltSegmentBehind(SlimeBeltBlockEntity controller, int segment) {
		Direction movementFacing = controller.getMovementFacing();
		BlockPos segmentPos = SlimeBeltHelper.getPositionForOffset(controller, segment);
		return SlimeBeltHelper.getSegmentBE(controller.getLevel(), segmentPos.relative(movementFacing.getOpposite())) != null;
	}

	@Nullable
	private static Float resolveSideOffset(SlimeBeltBlockEntity controller, Direction side, Direction movementFacing) {
		// Cross-axis side offset: only for sides perpendicular to the chain axis AND not
		// on the FRONT/BACK track face. Chain-axis inserts must not get a sideways push —
		// that produced a "slide in from the side" animation for BACK chain entries.
		if (side == null || side.getAxis().isVertical()
			|| side.getAxis() == SlimeBeltHelper.getChainBlockAxis(controller) || side == movementFacing)
			return null;
		Direction frontInputSide = SlimeBeltLoopGeometry.frontInputSide(controller.getBlockState());
		if (side == frontInputSide || side == frontInputSide.getOpposite())
			return null;
		int axisSign = side.getAxisDirection()
			.getStep();
		if (side.getAxis() == Direction.Axis.X)
			axisSign *= -1;
		return axisSign * INSERT_OFF_TRACK_SIDE_OFFSET;
	}
}
