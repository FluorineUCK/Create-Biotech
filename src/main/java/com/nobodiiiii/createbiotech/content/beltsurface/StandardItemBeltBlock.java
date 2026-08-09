package com.nobodiiiii.createbiotech.content.beltsurface;

import com.nobodiiiii.createbiotech.foundation.block.CBBeltChainBlock;
import com.simibubi.create.content.kinetics.belt.BeltSlope;

import net.minecraft.world.level.block.state.BlockState;

/** A Biotech belt variant that exposes one Create-compatible item surface on its front/top side. */
public interface StandardItemBeltBlock extends CBBeltChainBlock {

	boolean createBiotech$canTransportItems(BlockState state);

	/** Whether the block can physically support a belt tunnel in its current state. */
	boolean createBiotech$canSupportTunnel(BlockState state);

	default boolean createBiotech$isHorizontalItemBelt(BlockState state) {
		return state.getBlock() == this
			&& state.getValue(createBiotech$slopeProperty()) == BeltSlope.HORIZONTAL
			&& createBiotech$canTransportItems(state);
	}
}
