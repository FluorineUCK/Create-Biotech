package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlockEntity;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltHelper;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltLoopGeometry.Track;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.content.processing.basin.BasinBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(BasinBlock.class)
public abstract class BasinBlockSlimeBeltOutputMixin {

	@Inject(method = "canOutputTo", at = @At("HEAD"), cancellable = true)
	private static void createBiotech$allowStoppedSlimeBeltOutput(BlockGetter world, BlockPos basinPos,
		Direction direction, CallbackInfoReturnable<Boolean> cir) {
		BlockPos neighbourPos = basinPos.relative(direction);
		BlockPos outputPos = neighbourPos.below();
		BlockEntity blockEntity = world.getBlockEntity(outputPos);
		if (!(blockEntity instanceof SlimeBeltBlockEntity segment))
			return;

		BlockState neighbour = world.getBlockState(neighbourPos);
		if (FunnelBlock.isFunnel(neighbour)) {
			if (FunnelBlock.getFunnelFacing(neighbour) == direction) {
				cir.setReturnValue(false);
				return;
			}
		} else if (!neighbour.getCollisionShape(world, neighbourPos).isEmpty()) {
			cir.setReturnValue(false);
			return;
		}

		SlimeBeltBlockEntity controller = segment.getControllerBE();
		if (controller == null)
			return;
		Track track = SlimeBeltHelper.resolveIOTrack(controller, segment.index, direction);
		if (track != Track.FRONT) {
			cir.setReturnValue(false);
			return;
		}
		if (segment.getSpeed() == 0) {
			cir.setReturnValue(true);
			return;
		}
		DirectBeltInputBehaviour behaviour =
			BlockEntityBehaviour.get(world, outputPos, DirectBeltInputBehaviour.TYPE);
		cir.setReturnValue(behaviour != null && behaviour.canInsertFromSide(direction));
	}
}
