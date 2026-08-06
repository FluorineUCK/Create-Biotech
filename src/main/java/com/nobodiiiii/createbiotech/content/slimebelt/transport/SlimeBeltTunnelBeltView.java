package com.nobodiiiii.createbiotech.content.slimebelt.transport;

import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlockEntity;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;

import net.minecraft.core.Direction;

/** Read-only belt view for the speed and direction queries in Create's tunnel protocol. */
public final class SlimeBeltTunnelBeltView extends BeltBlockEntity {

	private SlimeBeltBlockEntity delegate;

	public SlimeBeltTunnelBeltView(SlimeBeltBlockEntity delegate) {
		super(AllBlockEntityTypes.BELT.get(), delegate.getBlockPos(), AllBlocks.BELT.getDefaultState());
		this.delegate = delegate;
	}

	public void setDelegate(SlimeBeltBlockEntity delegate) {
		this.delegate = delegate;
	}

	@Override
	public float getSpeed() {
		return delegate.getSpeed();
	}

	@Override
	public Direction getMovementFacing() {
		return delegate.getMovementFacing();
	}
}
