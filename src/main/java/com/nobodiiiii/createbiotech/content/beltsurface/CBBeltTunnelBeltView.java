package com.nobodiiiii.createbiotech.content.beltsurface;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;

import net.minecraft.core.Direction;

/** Read-only adapter for the speed and movement-facing queries in Create's brass-tunnel protocol. */
public final class CBBeltTunnelBeltView extends BeltBlockEntity {

	private StandardItemBeltPort delegate;

	public CBBeltTunnelBeltView(StandardItemBeltPort delegate) {
		super(AllBlockEntityTypes.BELT.get(), delegate.createBiotech$getBlockPos(), AllBlocks.BELT.getDefaultState());
		this.delegate = delegate;
	}

	public void setDelegate(StandardItemBeltPort delegate) {
		this.delegate = delegate;
	}

	@Override
	public float getSpeed() {
		return delegate.createBiotech$getSpeed();
	}

	@Override
	public Direction getMovementFacing() {
		return delegate.createBiotech$getMovementFacing();
	}
}
