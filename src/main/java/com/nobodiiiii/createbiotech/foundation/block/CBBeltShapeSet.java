package com.nobodiiiii.createbiotech.foundation.block;

import static net.minecraft.world.level.block.Block.box;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import com.simibubi.create.AllShapes;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;

import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Cached collision/selection shapes for belt blocks with optional variant additions. */
public final class CBBeltShapeSet {
	private static final VoxelShape SLOPE_DESC_PART = makeSlopePart(false);
	private static final VoxelShape SLOPE_ASC_PART = makeSlopePart(true);
	private static final VoxelShape SIDEWAYS_FULL_PART = makeSidewaysFull();
	private static final VoxelShape SIDEWAYS_END_PART = makeSidewaysEnding();
	private static final VoxelShape FLAT_FULL_PART = makeFlatFull();
	private static final VoxelShape FLAT_END_PART = makeFlatEnding();
	private static final VoxelShape SOUTH_MASK = box(0, -5, 8, 16, 21, 16);
	private static final VoxelShape NORTH_MASK = box(0, -5, 0, 16, 21, 8);

	private static final VoxelShaper FLAT_FULL = VoxelShaper.forHorizontalAxis(FLAT_FULL_PART, Axis.Z);
	private static final VoxelShaper FLAT_END = VoxelShaper.forHorizontal(
		compose(FLAT_END_PART, FLAT_FULL_PART), Direction.SOUTH);
	private static final VoxelShaper FLAT_START = VoxelShaper.forHorizontal(
		compose(FLAT_FULL_PART, FLAT_END_PART), Direction.SOUTH);
	private static final VoxelShaper SIDE_FULL = VoxelShaper.forHorizontalAxis(SIDEWAYS_FULL_PART, Axis.Z);
	private static final VoxelShaper SIDE_END = VoxelShaper.forHorizontal(
		compose(SIDEWAYS_END_PART, SIDEWAYS_FULL_PART), Direction.SOUTH);
	private static final VoxelShaper SIDE_START = VoxelShaper.forHorizontal(
		compose(SIDEWAYS_FULL_PART, SIDEWAYS_END_PART), Direction.SOUTH);
	private static final VoxelShaper SLOPE_DESC = VoxelShaper.forHorizontal(SLOPE_DESC_PART, Direction.SOUTH);
	private static final VoxelShaper SLOPE_ASC = VoxelShaper.forHorizontal(SLOPE_ASC_PART, Direction.SOUTH);
	private static final VoxelShaper SLOPE_DESC_END = VoxelShaper.forHorizontal(
		compose(FLAT_END_PART, SLOPE_DESC_PART), Direction.SOUTH);
	private static final VoxelShaper SLOPE_DESC_START = VoxelShaper.forHorizontal(
		compose(SLOPE_DESC_PART, FLAT_END_PART), Direction.SOUTH);
	private static final VoxelShaper SLOPE_ASC_END = VoxelShaper.forHorizontal(
		compose(FLAT_END_PART, SLOPE_ASC_PART), Direction.SOUTH);
	private static final VoxelShaper SLOPE_ASC_START = VoxelShaper.forHorizontal(
		compose(SLOPE_ASC_PART, FLAT_END_PART), Direction.SOUTH);

	private final VoxelShaper verticalFull;
	private final VoxelShaper verticalEnd;
	private final VoxelShaper verticalStart;
	private final Function<BlockState, VoxelShape> additionalShape;
	private final Map<BlockState, VoxelShape> shapeCache = new HashMap<>();
	private final Map<BlockState, VoxelShape> collisionCache = new HashMap<>();

	public CBBeltShapeSet(double verticalDrop) {
		this(verticalDrop, null);
	}

	public CBBeltShapeSet(double verticalDrop, Function<BlockState, VoxelShape> additionalShape) {
		verticalFull = makeVertical(FLAT_FULL_PART, verticalDrop);
		verticalEnd = makeVertical(compose(FLAT_END_PART, FLAT_FULL_PART), verticalDrop);
		verticalStart = makeVertical(compose(FLAT_FULL_PART, FLAT_END_PART), verticalDrop);
		this.additionalShape = additionalShape;
	}

	public VoxelShape getShape(BlockState state) {
		return shapeCache.computeIfAbsent(state, key -> {
			VoxelShape beltShape = getBeltShape(key);
			return additionalShape == null ? beltShape : Shapes.or(beltShape, additionalShape.apply(key));
		});
	}

	public VoxelShape getCollisionShape(BlockState state) {
		return collisionCache.computeIfAbsent(state,
			key -> Shapes.joinUnoptimized(AllShapes.BELT_COLLISION_MASK, getShape(key), BooleanOp.AND));
	}

	private VoxelShape getBeltShape(BlockState state) {
		CBBeltChainBlock belt = (CBBeltChainBlock) state.getBlock();
		Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
		Axis axis = facing.getAxis();
		BeltPart part = state.getValue(belt.createBiotech$partProperty());
		BeltSlope slope = state.getValue(belt.createBiotech$slopeProperty());

		if (slope == BeltSlope.VERTICAL) {
			if (part == BeltPart.MIDDLE || part == BeltPart.PULLEY)
				return verticalFull.get(axis);
			return (part == BeltPart.START ? verticalStart : verticalEnd).get(facing);
		}
		if (slope == BeltSlope.HORIZONTAL) {
			if (part == BeltPart.MIDDLE || part == BeltPart.PULLEY)
				return FLAT_FULL.get(axis);
			return (part == BeltPart.START ? FLAT_START : FLAT_END).get(facing);
		}
		if (slope == BeltSlope.SIDEWAYS) {
			if (part == BeltPart.MIDDLE || part == BeltPart.PULLEY)
				return SIDE_FULL.get(axis);
			return (part == BeltPart.START ? SIDE_START : SIDE_END).get(facing);
		}
		if (part == BeltPart.MIDDLE || part == BeltPart.PULLEY)
			return (slope == BeltSlope.DOWNWARD ? SLOPE_DESC : SLOPE_ASC).get(facing);
		if (part == BeltPart.START)
			return (slope == BeltSlope.DOWNWARD ? SLOPE_DESC_START : SLOPE_ASC_START).get(facing);
		if (part == BeltPart.END)
			return (slope == BeltSlope.DOWNWARD ? SLOPE_DESC_END : SLOPE_ASC_END).get(facing);
		return Shapes.empty();
	}

	private static VoxelShape compose(VoxelShape southPart, VoxelShape northPart) {
		return Shapes.or(Shapes.joinUnoptimized(SOUTH_MASK, southPart, BooleanOp.AND),
			Shapes.joinUnoptimized(NORTH_MASK, northPart, BooleanOp.AND));
	}

	private static VoxelShape makeSlopePart(boolean ascending) {
		VoxelShape slice = box(1, 0, 15, 15, 11, 16);
		VoxelShape result = Shapes.empty();
		for (int i = 0; i < 16; i++) {
			int yOffset = ascending ? 10 - i : i - 5;
			result = Shapes.or(result, slice.move(0, yOffset / 16f, -i / 16f));
		}
		return result;
	}

	private static VoxelShape makeFlatEnding() {
		return Shapes.or(box(1, 4, 0, 15, 12, 16), box(1, 3, 1, 15, 13, 15));
	}

	private static VoxelShape makeFlatFull() {
		return box(1, 3, 0, 15, 13, 16);
	}

	private static VoxelShape makeSidewaysEnding() {
		return Shapes.or(box(4, 1, 0, 12, 15, 16), box(3, 1, 1, 13, 15, 15));
	}

	private static VoxelShape makeSidewaysFull() {
		return box(3, 1, 0, 13, 15, 16);
	}

	private static VoxelShaper makeVertical(VoxelShape southShape, double verticalDrop) {
		return VerticalShaper.make(southShape, verticalDrop);
	}

	private static final class VerticalShaper extends VoxelShaper {
		private static VoxelShaper make(VoxelShape southShape, double verticalDrop) {
			return forDirectionsWithRotation(
				rotatedCopy(southShape, new Vec3(-90, 0, 0)).move(0, -verticalDrop, 0),
				Direction.SOUTH, Direction.Plane.HORIZONTAL,
				direction -> new Vec3(
					direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 0 : 180,
					-direction.toYRot(), 0));
		}
	}
}
