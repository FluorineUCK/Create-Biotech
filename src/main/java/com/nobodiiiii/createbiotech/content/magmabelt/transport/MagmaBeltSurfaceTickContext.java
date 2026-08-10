package com.nobodiiiii.createbiotech.content.magmabelt.transport;

import com.nobodiiiii.createbiotech.content.beltsurface.FunnelTickContext;
import com.nobodiiiii.createbiotech.content.beltsurface.CrusherTickContext;
import com.nobodiiiii.createbiotech.content.magmabelt.MagmaBeltHelper;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.VersionedInventoryTrackerBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Adapts the magma belt's standard top surface to the shared funnel algorithm. */
public record MagmaBeltSurfaceTickContext(MagmaBeltInventory inventory)
	implements FunnelTickContext, CrusherTickContext {

	@Override
	public Level level() {
		return inventory.belt.getLevel();
	}

	@Override
	public int beltLength() {
		return inventory.belt.beltLength;
	}

	@Override
	public boolean movingTowardHigherSegments() {
		return inventory.beltMovementPositive;
	}

	@Override
	public Direction movementFacing() {
		return inventory.belt.getMovementFacing();
	}

	@Override
	public VersionedInventoryTrackerBehaviour invVersionTracker() {
		return inventory.belt.invVersionTracker;
	}

	@Override
	public float currentSegmentPosition(TransportedItemStack item) {
		return item.beltPosition;
	}

	@Override
	public BlockPos funnelPosFor(int segment) {
		return MagmaBeltHelper.getPositionForOffset(inventory.belt, segment).above();
	}

	@Override
	public BlockPos crusherPosFor(int segment) {
		return funnelPosFor(segment);
	}

	@Override
	public Direction worldizeFunnelFacing(Direction localFacing) {
		return localFacing;
	}

	@Override
	public void lockItemAtEntry(TransportedItemStack item, float entryOnSegmentAxis) {
		item.beltPosition = entryOnSegmentAxis;
	}

	@Override
	public void notifyUpdate() {
		inventory.belt.notifyUpdate();
	}
}
