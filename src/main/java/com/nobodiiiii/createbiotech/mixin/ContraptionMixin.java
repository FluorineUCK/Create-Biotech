package com.nobodiiiii.createbiotech.mixin;

import java.util.Queue;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.sugar.Local;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlock;
import com.simibubi.create.content.contraptions.Contraption;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(Contraption.class)
public abstract class ContraptionMixin {

	@Inject(method = "moveBlock", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
		ordinal = 0),
		cancellable = false)
	private void createBiotech$collectSlimeBeltChain(Level world, @Nullable Direction forcedDirection,
		Queue<BlockPos> frontier, Set<BlockPos> visited, CallbackInfoReturnable<Boolean> cir,
		@Local BlockPos pos, @Local BlockState state) {
		if (!state.is(com.nobodiiiii.createbiotech.registry.CBBlocks.SLIME_BELT.get()))
			return;
		BlockPos nextPos = SlimeBeltBlock.nextSegmentPosition(state, pos, true);
		BlockPos prevPos = SlimeBeltBlock.nextSegmentPosition(state, pos, false);
		if (nextPos != null && !visited.contains(nextPos))
			frontier.add(nextPos);
		if (prevPos != null && !visited.contains(prevPos))
			frontier.add(prevPos);
	}
}
