package com.nobodiiiii.createbiotech.client.render;

import org.joml.Quaternionf;

import com.nobodiiiii.createbiotech.foundation.block.CBBeltChainBlock;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import dev.engine_room.flywheel.lib.transform.TransformStack;

/** Immutable orientation and scroll-sign calculation shared by BER and Flywheel belt paths. */
public record CBBeltRenderState(BeltSlope slope, BeltPart part, Direction facing) {

	public static CBBeltRenderState of(BlockState state) {
		CBBeltChainBlock belt = (CBBeltChainBlock) state.getBlock();
		return new CBBeltRenderState(state.getValue(belt.createBiotech$slopeProperty()),
			state.getValue(belt.createBiotech$partProperty()),
			state.getValue(BlockStateProperties.HORIZONTAL_FACING));
	}

	public boolean diagonal() {
		return slope.isDiagonal();
	}

	public boolean start() {
		return part == BeltPart.START;
	}

	public boolean end() {
		return part == BeltPart.END;
	}

	public Direction pulleyOrientation() {
		return slope == BeltSlope.SIDEWAYS ? Direction.UP : facing.getClockWise();
	}

	public float flywheelSpeed(float rawSpeed) {
		boolean diagonal = diagonal();
		boolean sideways = slope == BeltSlope.SIDEWAYS;
		boolean vertical = slope == BeltSlope.VERTICAL;
		boolean upward = slope == BeltSlope.UPWARD;
		boolean alongX = facing.getAxis() == Direction.Axis.X;
		boolean alongZ = facing.getAxis() == Direction.Axis.Z;
		if (facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE ^ upward
			^ (alongX && !diagonal || alongZ && diagonal))
			rawSpeed = -rawSpeed;
		if (sideways && (facing == Direction.SOUTH || facing == Direction.WEST)
			|| vertical && facing == Direction.EAST)
			rawSpeed = -rawSpeed;
		return rawSpeed;
	}

	public Quaternionf flywheelRotation() {
		boolean diagonal = diagonal();
		boolean sideways = slope == BeltSlope.SIDEWAYS;
		boolean vertical = slope == BeltSlope.VERTICAL;
		boolean upward = slope == BeltSlope.UPWARD;
		boolean downward = slope == BeltSlope.DOWNWARD;
		boolean alongX = facing.getAxis() == Direction.Axis.X;
		boolean alongZ = facing.getAxis() == Direction.Axis.Z;
		float rotX = (!diagonal && slope != BeltSlope.HORIZONTAL ? 90 : 0) + (downward ? 180 : 0)
			+ (sideways ? 90 : 0) + (vertical && alongZ ? 180 : 0);
		float rotY = facing.toYRot() + (diagonal != alongX && !downward ? 180 : 0)
			+ (sideways && alongZ ? 180 : 0) + (vertical && alongX ? 90 : 0);
		float rotZ = (sideways ? 90 : 0) + (vertical && alongX ? 90 : 0);
		return new Quaternionf().rotationXYZ(rotX * Mth.DEG_TO_RAD, rotY * Mth.DEG_TO_RAD,
			rotZ * Mth.DEG_TO_RAD);
	}

	public float surfaceYOffset(float verticalOffset) {
		return slope == BeltSlope.VERTICAL ? verticalOffset : 0;
	}

	public void transformBufferedModel(TransformStack<?> transform) {
		boolean upward = slope == BeltSlope.UPWARD;
		boolean sideways = slope == BeltSlope.SIDEWAYS;
		transform.center()
			.rotateYDegrees(net.createmod.catnip.math.AngleHelper.horizontalAngle(facing)
				+ (upward ? 180 : 0) + (sideways ? 270 : 0))
			.rotateZDegrees(sideways ? 90 : 0)
			.rotateXDegrees(!diagonal() && slope != BeltSlope.HORIZONTAL ? 90 : 0)
			.uncenter();
	}

	public boolean bufferedStart() {
		return shouldSwapBufferedEnds() ? end() : start();
	}

	public boolean bufferedEnd() {
		return shouldSwapBufferedEnds() ? start() : end();
	}

	public double bufferedScroll(float rawSpeed, float renderTick, boolean bottom) {
		boolean downward = slope == BeltSlope.DOWNWARD;
		boolean sideways = slope == BeltSlope.SIDEWAYS;
		boolean alongX = facing.getAxis() == Direction.Axis.X;
		if (diagonal() && (downward ^ alongX) || !sideways && !diagonal() && alongX
			|| sideways && facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE)
			rawSpeed = -rawSpeed;
		return rawSpeed * renderTick * facing.getAxisDirection().getStep() / (31.5 * 16)
			+ (bottom ? .5 : 0);
	}

	private boolean shouldSwapBufferedEnds() {
		return slope == BeltSlope.DOWNWARD
			|| slope == BeltSlope.VERTICAL && facing.getAxisDirection() == Direction.AxisDirection.POSITIVE;
	}
}
