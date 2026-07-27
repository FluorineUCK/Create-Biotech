package com.nobodiiiii.createbiotech.content.slimebelt;

import com.simibubi.create.content.kinetics.belt.BeltSlope;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable geometry of a slime belt loop, anchored at its controller segment.
 *
 * <p>The loop coordinate ("loopPosition") is independent of movement direction and runs
 * FRONT track {@code [0, L]} → END_TURN arc {@code (L, L+C)} → BACK track {@code [L+C, 2L+C]}
 * → START_TURN arc {@code (2L+C, 2L+2C)}, where {@code L} is the belt length in segments and
 * {@code C} the turn arc length. Values in {@code [-1, 0)} are a FRONT-track backward
 * extrapolation used for smooth belt-to-belt INSERT visuals; {@link #normalize} passes them
 * through unchanged.
 *
 * <p>Instances are cached on the controller block entity ({@code getLoop()}) and identified
 * by fingerprint ({@link #matches}): the captured blockstate reference plus belt length. Any
 * chain rebuild, slice, rotation, NBT/client sync or structure move yields a new state
 * reference or length and therefore a fresh geometry — no explicit invalidation wiring.
 */
public class SlimeBeltLoopGeometry {

	public enum Track {
		FRONT,
		BACK
	}

	public enum LoopSection {
		FRONT,
		END_TURN,
		BACK,
		START_TURN
	}

	/** Distance from the chain axis to each track surface; defines the loop cross-section. */
	public static final double TRACK_SURFACE_OFFSET = 7d / 16d;
	/** Vertical belts sit slightly below the grid to line up with horizontal neighbours. */
	public static final double VERTICAL_BELT_DROP = 1d / 16d;
	/**
	 * Items never sit exactly on a section boundary — entries into a track or turn land
	 * this far inside it, keeping {@link #section} classification unambiguous.
	 */
	public static final float WRAP_ENTRY_OFFSET = 1 / 512f;

	private static final double EPSILON = 1.0E-6d;
	private static final double TRACK_INPUT_EPSILON = 1.0E-4d;

	private record Turn(Vec3 center, Vec3 start, Vec3 end, Vec3 startRadialUnit, double startRadius,
		double endRadius, Vec3 axis, boolean degenerateAxis, double angle, double arcLength) {
	}

	private final BlockState state;
	private final BlockPos controllerPos;
	private final int beltLength;
	private final BeltSlope slope;
	private final Direction facing;
	private final Vec3 frontNormal;
	private final Vec3 pathAxis;
	private final Vec3 frontTrackOffset;
	private final Turn endTurn;
	private final Turn startTurn;
	private final float connectorLength;
	private final float loopLength;

	public static SlimeBeltLoopGeometry of(BlockState state, BlockPos controllerPos, int beltLength) {
		return new SlimeBeltLoopGeometry(state, controllerPos, beltLength);
	}

	private SlimeBeltLoopGeometry(BlockState state, BlockPos controllerPos, int beltLength) {
		this.state = state;
		this.controllerPos = controllerPos;
		this.beltLength = beltLength;
		this.slope = state.getValue(SlimeBeltBlock.SLOPE);
		this.facing = state.getValue(SlimeBeltBlock.HORIZONTAL_FACING);
		this.frontNormal = computeFrontSurfaceNormal();
		this.pathAxis = computePathAxis();
		this.frontTrackOffset = slope == BeltSlope.VERTICAL || slope == BeltSlope.SIDEWAYS
			? frontNormal.scale(TRACK_SURFACE_OFFSET)
			: new Vec3(0, TRACK_SURFACE_OFFSET, 0);
		this.endTurn = computeTurn(LoopSection.END_TURN);
		this.startTurn = computeTurn(LoopSection.START_TURN);
		this.connectorLength = (float) endTurn.arcLength();
		this.loopLength = beltLength * 2f + connectorLength * 2f;
	}

	public boolean matches(BlockState state, int beltLength) {
		return this.state == state && this.beltLength == beltLength;
	}

	public int beltLength() {
		return beltLength;
	}

	public float loopLength() {
		return loopLength;
	}

	/** Arc length of one turn; both turns are laid out with this length on the loop. */
	public float connectorLength() {
		return connectorLength;
	}

	public BeltSlope slope() {
		return slope;
	}

	public Direction facing() {
		return facing;
	}

	public BlockPos controllerPos() {
		return controllerPos;
	}

	public float normalize(float loopPosition) {
		if (loopLength <= 0)
			return 0;
		// Small negatives pass through unchanged — this preserves the FRONT-track
		// extrapolation used by smooth belt-to-belt INSERT visuals.
		if (loopPosition >= -1f && loopPosition < 0f)
			return loopPosition;
		float normalized = loopPosition % loopLength;
		if (normalized < 0)
			normalized += loopLength;
		return normalized;
	}

	public LoopSection section(float loopPosition) {
		float normalized = normalize(loopPosition);
		if (normalized <= beltLength)
			return LoopSection.FRONT;
		if (normalized < beltLength + connectorLength)
			return LoopSection.END_TURN;
		if (normalized <= beltLength + connectorLength + beltLength)
			return LoopSection.BACK;
		return LoopSection.START_TURN;
	}

	/** Position along the block chain in {@code [0, L]}; both tracks and turns project onto it. */
	public float frontOffset(float loopPosition) {
		float normalized = normalize(loopPosition);
		if (normalized <= beltLength)
			return normalized;
		if (normalized < beltLength + connectorLength)
			return beltLength;
		if (normalized <= beltLength + connectorLength + beltLength)
			return 2 * beltLength + connectorLength - normalized;
		return 0;
	}

	/** Track a loop position belongs to, with turn arcs split at half progress. */
	public Track closestTrack(float loopPosition) {
		float normalized = normalize(loopPosition);
		return switch (section(normalized)) {
			case FRONT -> Track.FRONT;
			case BACK -> Track.BACK;
			case END_TURN -> turnProgress(LoopSection.END_TURN, normalized) < .5f ? Track.FRONT : Track.BACK;
			case START_TURN -> turnProgress(LoopSection.START_TURN, normalized) < .5f ? Track.BACK : Track.FRONT;
		};
	}

	public Vec3 worldPos(float loopPosition) {
		// Smooth chain-INSERT extrapolation: a loopPosition in [-1, 0) represents FRONT
		// track extrapolated backward of FRONT@0 by that amount.
		if (loopPosition >= -1f && loopPosition < 0f)
			return straightTrackVector(Track.FRONT, loopPosition);
		float normalized = normalize(loopPosition);
		LoopSection section = section(normalized);
		if (section == LoopSection.FRONT)
			return straightTrackVector(Track.FRONT, normalized);
		if (section == LoopSection.END_TURN)
			return turnVector(endTurn, turnProgress(LoopSection.END_TURN, normalized));
		if (section == LoopSection.START_TURN)
			return turnVector(startTurn, turnProgress(LoopSection.START_TURN, normalized));
		float progress = normalized - beltLength - connectorLength;
		return straightTrackVector(Track.BACK, beltLength - progress);
	}

	/** Outward surface normal of the loop at the given position. */
	public Vec3 normal(float loopPosition) {
		LoopSection section = section(loopPosition);
		if (section == LoopSection.END_TURN)
			return turnNormal(endTurn, LoopSection.END_TURN, loopPosition);
		if (section == LoopSection.START_TURN)
			return turnNormal(startTurn, LoopSection.START_TURN, loopPosition);
		if ((slope == BeltSlope.UPWARD || slope == BeltSlope.DOWNWARD) && section == LoopSection.BACK) {
			float frontOffset = frontOffset(loopPosition);
			return isSlopeMiddle(frontOffset) ? frontNormal.scale(-1) : new Vec3(0, -1, 0);
		}
		if (section == LoopSection.FRONT)
			return frontNormal;
		return frontNormal.scale(-1);
	}

	public BlockPos blockAt(int offset) {
		if (slope == BeltSlope.VERTICAL) {
			int chainStep = facing.getAxisDirection()
				.getStep();
			return controllerPos.above(offset * chainStep);
		}
		Vec3i vec = facing.getNormal();
		return controllerPos.offset(offset * vec.getX(), Mth.clamp(offset, 0, beltLength - 1) * verticality(),
			offset * vec.getZ());
	}

	public Vec3 trackCenter(int segment, Track track) {
		float segmentCenter = clampSegmentCenter(segment);
		return straightTrackVector(track, segmentCenter);
	}

	public float trackCenterLoopPosition(int segment, Track track) {
		float segmentCenter = clampSegmentCenter(segment);
		if (track == Track.FRONT)
			return segmentCenter;
		return 2 * beltLength + connectorLength - segmentCenter;
	}

	public Vec3 trackNormal(int segment, Track track) {
		return normal(trackCenterLoopPosition(segment, track));
	}

	/** The world face best matching a track's outward normal at the given segment. */
	public Direction representativeSide(int segment, Track track) {
		Direction frontInputSide = frontInputSide(state);
		Direction primary = track == Track.FRONT ? Direction.UP : Direction.DOWN;
		Direction alternate = track == Track.FRONT ? frontInputSide : frontInputSide.getOpposite();
		Vec3 trackNormal = trackNormal(segment, track);
		return alignment(trackNormal, alternate) > alignment(trackNormal, primary) ? alternate : primary;
	}

	public boolean isTrackClosestToInputSide(int segment, Track track, Direction inputSide) {
		if (inputSide == null)
			return true;
		Track other = track == Track.FRONT ? Track.BACK : Track.FRONT;
		double trackAlignment = alignment(trackNormal(segment, track), inputSide);
		double otherAlignment = alignment(trackNormal(segment, other), inputSide);
		return trackAlignment + TRACK_INPUT_EPSILON >= otherAlignment;
	}

	/** Block axis the chain runs along (Y for vertical belts). */
	public Axis chainAxis() {
		if (slope == BeltSlope.VERTICAL)
			return Axis.Y;
		return facing.getAxis();
	}

	/** Unit vector along the chain in facing direction (with slope verticality). */
	public Vec3 pathAxis() {
		return pathAxis;
	}

	/** Outward normal of the FRONT track surface. */
	public Vec3 frontSurfaceNormal() {
		return frontNormal;
	}

	public static Direction frontInputSide(BlockState state) {
		BeltSlope slope = state.getValue(SlimeBeltBlock.SLOPE);
		Direction facing = state.getValue(SlimeBeltBlock.HORIZONTAL_FACING);
		return slope == BeltSlope.VERTICAL ? facing.getOpposite() : facing.getClockWise();
	}

	/** Maps an input face onto the physically closer track surface. */
	public static Track resolveInputTrack(BlockState state, Direction side) {
		if (side == null || side == Direction.UP)
			return Track.FRONT;
		if (side == Direction.DOWN)
			return Track.BACK;

		Direction frontInputSide = frontInputSide(state);
		if (side == frontInputSide)
			return Track.FRONT;
		if (side == frontInputSide.getOpposite())
			return Track.BACK;

		return Track.FRONT;
	}

	private float clampSegmentCenter(int segment) {
		return Mth.clamp(segment + .5f, .5f, Math.max(.5f, beltLength - .5f));
	}

	private int verticality() {
		return slope == BeltSlope.DOWNWARD ? -1 : slope == BeltSlope.UPWARD ? 1 : 0;
	}

	private boolean isSlopeMiddle(float frontOffset) {
		return Mth.clamp(frontOffset, .5f, beltLength - .5f) == frontOffset;
	}

	private Vec3 computeFrontSurfaceNormal() {
		if (slope == BeltSlope.VERTICAL)
			return Vec3.atLowerCornerOf(facing.getOpposite()
				.getNormal());
		if (slope == BeltSlope.SIDEWAYS)
			return Vec3.atLowerCornerOf(facing.getClockWise()
				.getNormal());
		if (slope == BeltSlope.HORIZONTAL)
			return new Vec3(0, 1, 0);

		int verticality = slope == BeltSlope.DOWNWARD ? -1 : 1;
		Vec3 travel = Vec3.atLowerCornerOf(facing.getNormal())
			.add(0, verticality, 0)
			.normalize();
		Vec3 across = Vec3.atLowerCornerOf(facing.getClockWise()
			.getNormal());
		return across.cross(travel)
			.normalize();
	}

	private Vec3 computePathAxis() {
		if (slope == BeltSlope.VERTICAL)
			return new Vec3(0, facing.getAxisDirection()
				.getStep(), 0);
		if (slope == BeltSlope.SIDEWAYS)
			return Vec3.atLowerCornerOf(facing.getNormal());
		return Vec3.atLowerCornerOf(facing.getNormal())
			.add(0, verticality(), 0)
			.normalize();
	}

	private Vec3 straightBaseVector(float frontOffset) {
		if (slope == BeltSlope.VERTICAL) {
			int chainStep = facing.getAxisDirection()
				.getStep();
			double y = chainStep > 0 ? frontOffset : 1d - frontOffset;
			return Vec3.atLowerCornerOf(controllerPos)
				.add(.5d, y - VERTICAL_BELT_DROP, .5d);
		}
		float verticalMovement = frontOffset < .5f ? 0
			: verticality() * (Math.min(frontOffset, beltLength - .5f) - .5f);
		Vec3 horizontalMovement = Vec3.atLowerCornerOf(facing.getNormal())
			.scale(frontOffset - .5f);
		return VecHelper.getCenterOf(controllerPos)
			.add(horizontalMovement)
			.add(0, verticalMovement, 0);
	}

	private Vec3 straightTrackVector(Track track, float frontOffset) {
		Vec3 offset = track == Track.FRONT ? frontTrackOffset : frontTrackOffset.scale(-1);
		return straightBaseVector(frontOffset).add(offset);
	}

	private Turn computeTurn(LoopSection section) {
		boolean end = section == LoopSection.END_TURN;
		Vec3 center = VecHelper.getCenterOf(end ? blockAt(beltLength - 1) : controllerPos);
		Vec3 start = straightTrackVector(end ? Track.FRONT : Track.BACK, end ? beltLength : 0);
		Vec3 endVector = straightTrackVector(end ? Track.BACK : Track.FRONT, end ? beltLength : 0);
		Vec3 startRadial = start.subtract(center);
		double startRadius = startRadial.length();
		double endRadius = endVector.subtract(center)
			.length();
		Vec3 axis = slope == BeltSlope.SIDEWAYS ? new Vec3(0, 1, 0)
			: Vec3.atLowerCornerOf(facing.getClockWise()
				.getNormal());
		boolean degenerateAxis = axis.lengthSqr() < EPSILON;

		double angle = 0;
		if (startRadius >= EPSILON && endRadius >= EPSILON && !degenerateAxis) {
			Vec3 startNormal = startRadial.scale(1 / startRadius);
			Vec3 endNormal = endVector.subtract(center)
				.scale(1 / endRadius);
			double sin = axis.dot(startNormal.cross(endNormal));
			double cos = Mth.clamp(startNormal.dot(endNormal), -1d, 1d);
			angle = Math.atan2(sin, cos);
		}

		double arcLength = (startRadius + endRadius) / 2d * Math.abs(angle);
		Vec3 startRadialUnit = startRadius < EPSILON ? Vec3.ZERO : startRadial.scale(1 / startRadius);
		return new Turn(center, start, endVector, startRadialUnit, startRadius, endRadius, axis, degenerateAxis,
			angle, arcLength);
	}

	/** Progress along a turn in loop order, unclamped; callers clamp where the original math did. */
	private float turnProgress(LoopSection section, float normalizedLoopPosition) {
		if (connectorLength <= 0)
			return 1;
		if (section == LoopSection.END_TURN)
			return (normalizedLoopPosition - beltLength) / connectorLength;
		return (normalizedLoopPosition - (beltLength + connectorLength + beltLength)) / connectorLength;
	}

	private Vec3 turnVector(Turn turn, float progressRaw) {
		if (turn.startRadius() < EPSILON)
			return turn.start();
		if (turn.degenerateAxis())
			return turn.start()
				.lerp(turn.end(), progressRaw);
		double progress = Mth.clamp(progressRaw, 0, 1);
		double angle = turn.angle() * progress;
		double radius = Mth.lerp(progress, turn.startRadius(), turn.endRadius());
		Vec3 rotated = rotateAroundAxis(turn.startRadialUnit(), turn.axis(), angle).scale(radius);
		return turn.center()
			.add(rotated);
	}

	private Vec3 turnNormal(Turn turn, LoopSection section, float loopPosition) {
		Vec3 normal = turnVector(turn, turnProgress(section, normalize(loopPosition))).subtract(turn.center());
		if (normal.lengthSqr() < EPSILON)
			return pathAxis;
		return normal.normalize();
	}

	private static Vec3 rotateAroundAxis(Vec3 vector, Vec3 axis, double angle) {
		Vec3 normalizedAxis = axis.normalize();
		double sin = Math.sin(angle);
		double cos = Math.cos(angle);
		Vec3 cross = normalizedAxis.cross(vector);
		double dot = normalizedAxis.dot(vector);
		return vector.scale(cos)
			.add(cross.scale(sin))
			.add(normalizedAxis.scale(dot * (1 - cos)));
	}

	private static double alignment(Vec3 normal, Direction side) {
		return normal.dot(Vec3.atLowerCornerOf(side.getNormal()));
	}

	public MotionFrame motion(boolean movementPositive) {
		return new MotionFrame(movementPositive);
	}

	/**
	 * Direction-aware view of the loop. The motion coordinate {@code m ∈ [0, loopLength)}
	 * increases in the direction items travel, with {@code m = 0} at the FRONT track's
	 * motion entry: {@code m = movementPositive ? loopPos : mod(beltLength - loopPos,
	 * loopLength)}. In m space the layout is always FRONT run {@code [0, L]} → exit turn
	 * {@code (L, L+C)} → BACK run {@code [L+C, 2L+C]} → entry turn {@code (2L+C, T)}; the
	 * exit turn is END_TURN when moving positive, START_TURN otherwise.
	 *
	 * <p>The per-track/turn conversions are kept in per-branch form (the exact algebraic
	 * images of the bijection) so float results match the historical formulas bit-for-bit.
	 */
	public class MotionFrame {

		private final boolean movementPositive;

		private MotionFrame(boolean movementPositive) {
			this.movementPositive = movementPositive;
		}

		public boolean movementPositive() {
			return movementPositive;
		}

		public SlimeBeltLoopGeometry geometry() {
			return SlimeBeltLoopGeometry.this;
		}

		/** The turn items enter after leaving the FRONT track. */
		public LoopSection exitTurn() {
			return movementPositive ? LoopSection.END_TURN : LoopSection.START_TURN;
		}

		/** The turn items enter after leaving the BACK track. */
		public LoopSection entryTurn() {
			return movementPositive ? LoopSection.START_TURN : LoopSection.END_TURN;
		}

		public float toMotion(float loopPosition) {
			// Positive keeps the [-1, 0) FRONT extrapolation as negative m ("before the
			// FRONT entry"); the negative-direction map folds it past the FRONT exit,
			// matching the historical trackProgress treatment of such positions.
			if (movementPositive)
				return normalize(loopPosition);
			return modLoop(beltLength - normalize(loopPosition));
		}

		public float toLoop(float motionPosition) {
			if (movementPositive)
				return normalize(motionPosition);
			return modLoop(beltLength - modLoop(motionPosition));
		}

		/** Distance traveled along the given track in motion direction. */
		public float trackProgress(Track track, float loopPosition) {
			float normalized = normalize(loopPosition);
			float backTrackStart = beltLength + connectorLength;
			float backTrackEnd = backTrackStart + beltLength;
			if (movementPositive)
				return track == Track.FRONT ? normalized : normalized - backTrackStart;
			return track == Track.FRONT ? beltLength - normalized : backTrackEnd - normalized;
		}

		/**
		 * Inverse of {@link #trackProgress}. Progress in [-1, 0) on the positive FRONT
		 * track passes through unclamped — the chain-INSERT extrapolation before the
		 * entry seam.
		 */
		public float loopPositionOfTrackProgress(Track track, float progress) {
			if (track == Track.FRONT && movementPositive && progress >= -1f && progress < 0f)
				return progress;
			float clamped = Mth.clamp(progress, 0, beltLength);
			float backTrackStart = beltLength + connectorLength;
			float backTrackEnd = backTrackStart + beltLength;
			float loopPos = movementPositive ? (track == Track.FRONT ? clamped : backTrackStart + clamped)
				: (track == Track.FRONT ? beltLength - clamped : backTrackEnd - clamped);
			return normalize(loopPos);
		}

		public float frontOffsetOfTrackProgress(Track track, float progress) {
			float clamped = Mth.clamp(progress, 0, beltLength);
			return movementPositive ? (track == Track.FRONT ? clamped : beltLength - clamped)
				: (track == Track.FRONT ? beltLength - clamped : clamped);
		}

		public float trackProgressOfFrontOffset(Track track, float frontOffset) {
			float clamped = Mth.clamp(frontOffset, 0, beltLength);
			return movementPositive ? (track == Track.FRONT ? clamped : beltLength - clamped)
				: (track == Track.FRONT ? beltLength - clamped : clamped);
		}

		/** Progress through a turn from its motion entry, in [0, C]. */
		public float turnProgressInMotion(LoopSection turn, float loopPosition) {
			float normalized = normalize(loopPosition);
			float turnStart = turnStart(turn);
			if (movementPositive)
				return normalized - turnStart;
			if (turn == LoopSection.END_TURN)
				return turnStart + connectorLength - normalized;
			return loopLength - normalized;
		}

		public float loopPositionOfTurnProgress(LoopSection turn, float progress) {
			float clamped = Mth.clamp(progress, 0, connectorLength);
			float turnStart = turnStart(turn);
			if (movementPositive)
				return normalize(turnStart + clamped);
			if (turn == LoopSection.END_TURN)
				return normalize(turnStart + connectorLength - clamped);
			return normalize(loopLength - clamped);
		}

		/** Loop position where the given turn begins in loop order. */
		private float turnStart(LoopSection turn) {
			return turn == LoopSection.END_TURN ? beltLength : beltLength + connectorLength + beltLength;
		}

		/** Plain modulo into [0, loopLength) without the [-1, 0) extrapolation passthrough. */
		private float modLoop(float value) {
			if (loopLength <= 0)
				return 0;
			float normalized = value % loopLength;
			if (normalized < 0)
				normalized += loopLength;
			return normalized;
		}
	}
}
