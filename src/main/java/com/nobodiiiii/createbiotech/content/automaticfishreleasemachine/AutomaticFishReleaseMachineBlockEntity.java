package com.nobodiiiii.createbiotech.content.automaticfishreleasemachine;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public class AutomaticFishReleaseMachineBlockEntity extends LargeWaterWheelBlockEntity {

	public AutomaticFishReleaseMachineBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.AUTOMATIC_FISH_RELEASE_MACHINE.get(), pos, state);
	}

	@Override
	protected AABB createRenderBoundingBox() {
		return new AABB(worldPosition).inflate(3);
	}
}
