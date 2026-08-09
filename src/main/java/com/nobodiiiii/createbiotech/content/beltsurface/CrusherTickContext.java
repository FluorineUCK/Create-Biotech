package com.nobodiiiii.createbiotech.content.beltsurface;

import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Geometry adapter used by {@link CrusherInteractionCore}. */
public interface CrusherTickContext {
	Level level();
	int beltLength();
	boolean movingTowardHigherSegments();
	Direction movementFacing();
	float currentSegmentPosition(TransportedItemStack item);
	BlockPos crusherPosFor(int segment);
	void lockItemAtEntry(TransportedItemStack item, float entryOnSegmentAxis);
	void notifyUpdate();
}
