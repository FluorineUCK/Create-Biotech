package com.nobodiiiii.createbiotech.content.slimebelt;

import com.nobodiiiii.createbiotech.foundation.block.CBBeltShapeSet;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class SlimeBeltShapes {
	private static final CBBeltShapeSet SHAPES =
		new CBBeltShapeSet(SlimeBeltLoopGeometry.VERTICAL_BELT_DROP);

	private SlimeBeltShapes() {}

	public static VoxelShape getShape(BlockState state) {
		return SHAPES.getShape(state);
	}

	public static VoxelShape getCollisionShape(BlockState state) {
		return SHAPES.getCollisionShape(state);
	}
}
