package com.nobodiiiii.createbiotech.content.magmabelt;

import static net.minecraft.world.level.block.Block.box;

import com.nobodiiiii.createbiotech.foundation.block.CBBeltShapeSet;
import com.simibubi.create.AllShapes;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;

import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class MagmaBeltShapes {
	private static final VoxelShaper PARTIAL_CASING =
		VoxelShaper.forHorizontal(box(0, 0, 5, 16, 11, 16), Direction.SOUTH);
	private static final CBBeltShapeSet SHAPES = new CBBeltShapeSet(0, MagmaBeltShapes::getCasingShape);

	private MagmaBeltShapes() {}

	public static VoxelShape getShape(BlockState state) {
		return SHAPES.getShape(state);
	}

	public static VoxelShape getCollisionShape(BlockState state) {
		return SHAPES.getCollisionShape(state);
	}

	private static VoxelShape getCasingShape(BlockState state) {
		if (!state.getValue(MagmaBeltBlock.CASING))
			return Shapes.empty();
		Direction facing = state.getValue(MagmaBeltBlock.HORIZONTAL_FACING);
		BeltPart part = state.getValue(MagmaBeltBlock.PART);
		BeltSlope slope = state.getValue(MagmaBeltBlock.SLOPE);
		if (slope == BeltSlope.VERTICAL || slope == BeltSlope.SIDEWAYS)
			return Shapes.empty();
		if (slope == BeltSlope.HORIZONTAL)
			return AllShapes.CASING_11PX.get(Direction.UP);
		if (part == BeltPart.MIDDLE || part == BeltPart.PULLEY)
			return PARTIAL_CASING.get(slope == BeltSlope.UPWARD ? facing : facing.getOpposite());
		if (part == BeltPart.START)
			return slope == BeltSlope.UPWARD ? AllShapes.CASING_11PX.get(Direction.UP)
				: PARTIAL_CASING.get(facing.getOpposite());
		if (part == BeltPart.END)
			return slope == BeltSlope.DOWNWARD ? AllShapes.CASING_11PX.get(Direction.UP)
				: PARTIAL_CASING.get(facing);
		return Shapes.block();
	}
}
