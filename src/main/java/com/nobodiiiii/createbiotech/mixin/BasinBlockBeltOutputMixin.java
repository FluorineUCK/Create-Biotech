package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.beltsurface.StandardItemBeltPort;
import com.nobodiiiii.createbiotech.content.beltsurface.StandardItemBeltPortResolver;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.content.processing.basin.BasinBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Extends basin output checks to every standard Biotech item-belt surface, including stopped belts. */
@Mixin(BasinBlock.class)
public abstract class BasinBlockBeltOutputMixin {

	@Inject(method = "canOutputTo", at = @At("HEAD"), cancellable = true)
	private static void createBiotech$allowStandardBeltOutput(BlockGetter world, BlockPos basinPos,
		Direction direction, CallbackInfoReturnable<Boolean> cir) {
		BlockPos neighbourPos = basinPos.relative(direction);
		BlockPos outputPos = neighbourPos.below();
		StandardItemBeltPort port = StandardItemBeltPortResolver.getHorizontalPort(world, outputPos);
		if (port == null)
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
		} else {
			cir.setReturnValue(port.createBiotech$addressesItemPort(direction)
				&& (port.createBiotech$getSpeed() == 0
					|| port.createBiotech$getMovementFacing() != direction.getOpposite()));
			return;
		}

		if (!port.createBiotech$addressesItemPort(direction)) {
			cir.setReturnValue(false);
			return;
		}
		DirectBeltInputBehaviour behaviour =
			BlockEntityBehaviour.get(world, outputPos, DirectBeltInputBehaviour.TYPE);
		cir.setReturnValue(behaviour != null && behaviour.canInsertFromSide(direction));
	}
}
