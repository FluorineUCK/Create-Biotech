package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.nobodiiiii.createbiotech.content.slimebelt.transport.SlimeBeltTunnelInteractionHandler;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.BeltTunnelInteractionHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

@Mixin(BeltTunnelInteractionHandler.class)
public abstract class BeltTunnelInteractionHandlerMixin {

	@WrapOperation(method = "flapTunnelsAndCheckIfStuck", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/belt/behaviour/DirectBeltInputBehaviour;canInsertFromSide(Lnet/minecraft/core/Direction;)Z"))
	private static boolean createBiotech$validateFrontOutput(DirectBeltInputBehaviour behaviour, Direction side,
		Operation<Boolean> original, @Local BlockPos outpos, @Local Level world) {
		if (SlimeBeltTunnelInteractionHandler.getHorizontalSlimeBelt(world, outpos) != null)
			return SlimeBeltTunnelInteractionHandler.canInsertIntoFront(world, outpos, side);
		return original.call(behaviour, side);
	}

	@WrapOperation(method = "flapTunnelsAndCheckIfStuck", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/belt/behaviour/DirectBeltInputBehaviour;handleInsertion(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;Z)Lnet/minecraft/world/item/ItemStack;"))
	private static ItemStack createBiotech$insertIntoFront(DirectBeltInputBehaviour behaviour, ItemStack stack,
		Direction side, boolean simulate, Operation<ItemStack> original, @Local BlockPos outpos, @Local Level world) {
		if (SlimeBeltTunnelInteractionHandler.getHorizontalSlimeBelt(world, outpos) != null)
			return SlimeBeltTunnelInteractionHandler.insertIntoFront(world, outpos, stack, side, simulate);
		return original.call(behaviour, stack, side, simulate);
	}
}
