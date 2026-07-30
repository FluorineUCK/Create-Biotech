package com.nobodiiiii.createbiotech.mixin.compat.sable;

import com.nobodiiiii.createbiotech.content.universaljoint.HalfShaftBlock;
import com.nobodiiiii.createbiotech.content.universaljoint.UniversalJointBlock;
import com.nobodiiiii.createbiotech.content.universaljoint.UniversalJointEndpointBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBConfigs;

import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;

@Mixin({ UniversalJointBlock.class, HalfShaftBlock.class })
public abstract class UniversalJointEndpointBlockSableMixin
	implements BlockSubLevelAssemblyListener, BlockSubLevelLiftProvider {

	@Override
	public Direction sable$getNormal(BlockState state) {
		return state.getValue(UniversalJointBlock.FACING);
	}

	@Override
	public float sable$getParallelDragScalar() {
		return 0;
	}

	@Override
	public float sable$getDirectionlessDragScalar() {
		return CBConfigs.SERVER.universalJoint.endpointAirDrag.get().floatValue();
	}

	@Override
	public float sable$getLiftScalar() {
		return 0;
	}

	@Override
	public void beforeMove(ServerLevel oldLevel, ServerLevel newLevel, BlockState state,
		BlockPos oldPos, BlockPos newPos) {
		if (oldLevel.getBlockEntity(oldPos) instanceof UniversalJointEndpointBlockEntity endpoint)
			endpoint.createBiotech$beforeSubLevelMove(oldLevel, newLevel, oldPos, newPos);
	}

	@Override
	public void afterMove(ServerLevel oldLevel, ServerLevel newLevel, BlockState state,
		BlockPos oldPos, BlockPos newPos) {
		if (newLevel.getBlockEntity(newPos) instanceof UniversalJointEndpointBlockEntity endpoint)
			endpoint.createBiotech$afterSubLevelMove(oldLevel, newLevel, oldPos, newPos);
	}
}
