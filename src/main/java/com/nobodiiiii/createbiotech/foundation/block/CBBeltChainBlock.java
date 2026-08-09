package com.nobodiiiii.createbiotech.foundation.block;

import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The geometry shared by every multi-block belt in Create: Biotech.
 *
 * <p>This deliberately contains no inventory, rendering or kinetic policy. Those differ between
 * variants; the physical rule that maps one segment to the next does not.</p>
 */
public interface CBBeltChainBlock {

	Property<BeltSlope> createBiotech$slopeProperty();

	Property<BeltPart> createBiotech$partProperty();

	default BlockPos createBiotech$nextSegmentPosition(BlockState state, BlockPos pos, boolean forward) {
		Direction direction = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
		BeltSlope slope = state.getValue(createBiotech$slopeProperty());
		BeltPart part = state.getValue(createBiotech$partProperty());
		int offset = forward ? 1 : -1;

		if (part == BeltPart.END && forward || part == BeltPart.START && !forward)
			return null;
		if (slope == BeltSlope.VERTICAL)
			return pos.above(direction.getAxisDirection() == AxisDirection.POSITIVE ? offset : -offset);
		BlockPos next = pos.relative(direction, offset);
		if (slope != BeltSlope.HORIZONTAL && slope != BeltSlope.SIDEWAYS)
			return next.above(slope == BeltSlope.UPWARD ? offset : -offset);
		return next;
	}
}
