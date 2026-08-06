package com.nobodiiiii.createbiotech.foundation.block;

import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.kinetics.belt.BeltSlope;

import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Rotation of a belt segment around a horizontal axis, shared by every belt variant in this mod.
 *
 * <p>{@link net.minecraft.world.level.block.Block#rotate} only covers the Y axis, which is all a normal
 * block placement ever needs. Contraptions rotate around X and Z as well, and there the facing and the
 * slope have to change together — a horizontal belt facing along the rotation axis becomes a sideways
 * one, a vertical belt becomes horizontal, and so on. Without this a belt that rides a contraption
 * through a horizontal-axis rotation disassembles into a state combination its own chain walk no longer
 * recognises, and {@code initBelt} then destroys the controller.</p>
 *
 * <p>The math is Create's, from
 * {@code ref/1.21.1/Create/.../content/kinetics/belt/BeltBlock.java#transformInner}, kept verbatim so it
 * stays diffable against upstream. Only the slope property differs between variants, so it is passed in;
 * {@code PART} is untouched here because part flipping is a Y-axis concern handled by each block's
 * {@code rotate}.</p>
 */
public final class CBBeltTransform {

	private CBBeltTransform() {}

	/**
	 * Applies a rotation around a horizontal axis to {@code state}.
	 *
	 * @param slopeProperty the variant's own {@code slope} property
	 */
	public static BlockState transformInner(BlockState state, StructureTransform transform,
		Property<BeltSlope> slopeProperty) {
		boolean halfTurn = transform.rotation == Rotation.CLOCKWISE_180;

		Direction initialDirection = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
		boolean diagonal =
			state.getValue(slopeProperty) == BeltSlope.DOWNWARD || state.getValue(slopeProperty) == BeltSlope.UPWARD;

		if (!diagonal) {
			for (int i = 0; i < transform.rotation.ordinal(); i++) {
				Direction direction = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
				BeltSlope slope = state.getValue(slopeProperty);
				boolean vertical = slope == BeltSlope.VERTICAL;
				boolean horizontal = slope == BeltSlope.HORIZONTAL;
				boolean sideways = slope == BeltSlope.SIDEWAYS;

				Direction newDirection = direction.getOpposite();
				BeltSlope newSlope = BeltSlope.VERTICAL;

				if (vertical) {
					if (direction.getAxis() == transform.rotationAxis) {
						newDirection = direction.getCounterClockWise();
						newSlope = BeltSlope.SIDEWAYS;
					} else {
						newSlope = BeltSlope.HORIZONTAL;
						newDirection = direction;
						if (direction.getAxis() == Axis.Z)
							newDirection = direction.getOpposite();
					}
				}

				if (sideways) {
					newDirection = direction;
					if (direction.getAxis() == transform.rotationAxis)
						newSlope = BeltSlope.HORIZONTAL;
					else
						newDirection = direction.getCounterClockWise();
				}

				if (horizontal) {
					newDirection = direction;
					if (direction.getAxis() == transform.rotationAxis)
						newSlope = BeltSlope.SIDEWAYS;
					else if (direction.getAxis() != Axis.Z)
						newDirection = direction.getOpposite();
				}

				state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, newDirection);
				state = state.setValue(slopeProperty, newSlope);
			}

		} else if (initialDirection.getAxis() != transform.rotationAxis) {
			for (int i = 0; i < transform.rotation.ordinal(); i++) {
				Direction direction = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
				Direction newDirection = direction.getOpposite();
				BeltSlope slope = state.getValue(slopeProperty);
				boolean upward = slope == BeltSlope.UPWARD;
				boolean downward = slope == BeltSlope.DOWNWARD;

				// Rotate diagonal
				if (direction.getAxisDirection() == AxisDirection.POSITIVE ^ downward ^ direction.getAxis() == Axis.Z) {
					state = state.setValue(slopeProperty, upward ? BeltSlope.DOWNWARD : BeltSlope.UPWARD);
				} else {
					state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, newDirection);
				}
			}

		} else if (halfTurn) {
			Direction direction = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
			Direction newDirection = direction.getOpposite();
			BeltSlope slope = state.getValue(slopeProperty);
			boolean vertical = slope == BeltSlope.VERTICAL;

			if (diagonal) {
				state = state.setValue(slopeProperty, slope == BeltSlope.UPWARD ? BeltSlope.DOWNWARD
					: slope == BeltSlope.DOWNWARD ? BeltSlope.UPWARD : slope);
			} else if (vertical) {
				state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, newDirection);
			}
		}

		return state;
	}
}
