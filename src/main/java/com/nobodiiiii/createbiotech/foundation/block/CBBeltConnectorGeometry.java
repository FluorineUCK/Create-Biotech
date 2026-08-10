package com.nobodiiiii.createbiotech.foundation.block;

import java.util.LinkedList;
import java.util.List;

import com.simibubi.create.content.kinetics.belt.BeltSlope;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;

/** Pure connector geometry shared by belt variants. */
public final class CBBeltConnectorGeometry {

	private CBBeltConnectorGeometry() {}

	public static Direction facingFromTo(BlockPos start, BlockPos end) {
		Axis beltAxis = start.getX() == end.getX() ? Axis.Z : Axis.X;
		BlockPos diff = end.subtract(start);
		AxisDirection direction = diff.getX() == 0 && diff.getZ() == 0
			? (diff.getY() > 0 ? AxisDirection.POSITIVE : AxisDirection.NEGATIVE)
			: (beltAxis.choose(diff.getX(), 0, diff.getZ()) > 0
				? AxisDirection.POSITIVE : AxisDirection.NEGATIVE);
		return Direction.get(direction, beltAxis);
	}

	public static boolean isVertical(BlockPos start, BlockPos end) {
		BlockPos diff = end.subtract(start);
		return diff.getX() == 0 && diff.getZ() == 0 && diff.getY() != 0;
	}

	public static BeltSlope slopeBetween(BlockPos start, BlockPos end) {
		BlockPos diff = end.subtract(start);
		if (diff.getY() == 0)
			return BeltSlope.HORIZONTAL;
		if (diff.getX() == 0 && diff.getZ() == 0)
			return BeltSlope.VERTICAL;
		return diff.getY() > 0 ? BeltSlope.UPWARD : BeltSlope.DOWNWARD;
	}

	public static List<BlockPos> chainBetween(BlockPos start, BlockPos end, BeltSlope slope,
		Direction direction) {
		List<BlockPos> positions = new LinkedList<>();
		int limit = 1000;
		BlockPos current = start;
		do {
			positions.add(current);
			if (slope == BeltSlope.VERTICAL) {
				current = current.above(direction.getAxisDirection() == AxisDirection.POSITIVE ? 1 : -1);
				continue;
			}
			current = current.relative(direction);
			if (slope != BeltSlope.HORIZONTAL)
				current = current.above(slope == BeltSlope.UPWARD ? 1 : -1);
		} while (!current.equals(end) && limit-- > 0);
		positions.add(end);
		return positions;
	}
}
