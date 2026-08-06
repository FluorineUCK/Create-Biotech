package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlock;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.logistics.tunnel.BeltTunnelBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(BeltTunnelBlock.class)
public abstract class BeltTunnelBlockMixin {

	@Inject(method = "canSurvive(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z",
		at = @At("HEAD"), cancellable = true)
	private void createBiotech$canSurvive(BlockState state, LevelReader world, BlockPos pos,
		CallbackInfoReturnable<Boolean> cir) {
		if (isHorizontalSlimeBelt(world.getBlockState(pos.below())))
			cir.setReturnValue(true);
	}

	@Inject(method = "isValidPositionForPlacement(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z",
		at = @At("HEAD"), cancellable = true)
	private void createBiotech$isValidPositionForPlacement(BlockState state, LevelReader world, BlockPos pos,
		CallbackInfoReturnable<Boolean> cir) {
		if (isHorizontalSlimeBelt(world.getBlockState(pos.below())))
			cir.setReturnValue(true);
	}

	@Inject(method = "getTunnelState", at = @At("RETURN"), cancellable = true, remap = false)
	private void createBiotech$getTunnelState(BlockGetter world, BlockPos pos,
		CallbackInfoReturnable<BlockState> cir) {
		BlockState belt = world.getBlockState(pos.below());
		if (isHorizontalSlimeBelt(belt))
			cir.setReturnValue(cir.getReturnValue()
				.setValue(BeltTunnelBlock.HORIZONTAL_AXIS,
					belt.getValue(com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlock.HORIZONTAL_FACING).getAxis()));
	}

	@Inject(method = "hasValidOutput", at = @At("HEAD"), cancellable = true, remap = false)
	private void createBiotech$hasValidOutput(BlockGetter world, BlockPos pos, Direction side,
		CallbackInfoReturnable<Boolean> cir) {
		BlockState neighbour = world.getBlockState(pos.relative(side));
		if (isHorizontalSlimeBelt(neighbour)
			&& neighbour.getValue(com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlock.HORIZONTAL_FACING)
				.getAxis() == side.getAxis())
			cir.setReturnValue(true);
	}

	private static boolean isHorizontalSlimeBelt(BlockState state) {
		return state.is(CBBlocks.SLIME_BELT.get())
			&& state.getValue(SlimeBeltBlock.SLOPE)
				== BeltSlope.HORIZONTAL;
	}
}
