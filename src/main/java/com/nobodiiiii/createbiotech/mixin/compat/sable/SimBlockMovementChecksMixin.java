package com.nobodiiiii.createbiotech.mixin.compat.sable;

import java.util.Queue;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nobodiiiii.createbiotech.foundation.block.CBBeltChain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.index.SimBlockMovementChecks")
public abstract class SimBlockMovementChecksMixin {

	@Inject(method = "addAdditionalBlocks", at = @At("RETURN"))
	private static void createBiotech$collectBeltChain(BlockState state, Level world, BlockPos pos,
		Queue<BlockPos> frontier, Set<BlockPos> visited, CallbackInfo ci) {
		CBBeltChain.addConnectedSegments(state, pos, frontier, visited);
	}
}
