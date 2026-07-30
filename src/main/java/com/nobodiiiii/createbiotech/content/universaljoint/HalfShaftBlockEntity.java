package com.nobodiiiii.createbiotech.content.universaljoint;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class HalfShaftBlockEntity extends UniversalJointEndpointBlockEntity {

	public HalfShaftBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.HALF_SHAFT.get(), pos, state);
	}
}
