package com.nobodiiiii.createbiotech.mixin;

import java.util.Queue;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nobodiiiii.createbiotech.foundation.block.CBBeltChain;
import com.simibubi.create.content.contraptions.Contraption;
import com.tterrag.registrate.util.entry.BlockEntry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(Contraption.class)
public abstract class ContraptionMixin {

	@WrapOperation(method = "moveBlock", at = @At(value = "INVOKE",
		target = "Lcom/tterrag/registrate/util/entry/BlockEntry;has(Lnet/minecraft/world/level/block/state/BlockState;)Z",
		ordinal = 0))
	private boolean createBiotech$recognizeBelt(BlockEntry<?> entry, BlockState state,
		Operation<Boolean> original) {
		return original.call(entry, state) || CBBeltChain.isBiotechBelt(state);
	}

	@Inject(method = "moveBelt", at = @At("HEAD"), cancellable = true)
	private void createBiotech$collectBeltChain(BlockPos pos, Queue<BlockPos> frontier,
		Set<BlockPos> visited, BlockState state, CallbackInfo ci) {
		if (!CBBeltChain.isBiotechBelt(state))
			return;
		CBBeltChain.addConnectedSegments(state, pos, frontier, visited);
		ci.cancel();
	}
}
