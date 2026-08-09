package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.nobodiiiii.createbiotech.content.beltsurface.CBBeltTunnelBeltView;
import com.nobodiiiii.createbiotech.content.beltsurface.StandardItemBeltPort;
import com.nobodiiiii.createbiotech.content.beltsurface.StandardItemBeltPortResolver;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.logistics.tunnel.BrassTunnelBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.BlockHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;

@Mixin(BrassTunnelBlockEntity.class)
public abstract class BrassTunnelBlockEntityMixin {

	@Unique
	private CBBeltTunnelBeltView createBiotech$beltView;

	@WrapOperation(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/belt/BeltHelper;getSegmentBE(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;)Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;"))
	private BeltBlockEntity createBiotech$getBeltBelowForTick(LevelAccessor world, BlockPos pos,
		Operation<BeltBlockEntity> original) {
		BeltBlockEntity belt = original.call(world, pos);
		return belt != null ? belt : getBiotechBeltView(world, pos);
	}

	@WrapOperation(method = "addValidOutputsOf", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/belt/BeltHelper;getSegmentBE(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;)Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;"))
	private BeltBlockEntity createBiotech$getBeltBelowForOutputs(LevelAccessor world, BlockPos pos,
		Operation<BeltBlockEntity> original) {
		BeltBlockEntity belt = original.call(world, pos);
		return belt != null ? belt : getBiotechBeltView(world, pos);
	}

	@Inject(method = "insertIntoTunnel", at = @At("HEAD"), cancellable = true)
	private void createBiotech$insertFromSlimeBelt(BrassTunnelBlockEntity tunnel, Direction side, ItemStack stack,
		boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
		Level level = tunnel.getLevel();
		if (level == null)
			return;
		BlockPos outputPos = tunnel.getBlockPos().below().relative(side);
		StandardItemBeltPort below =
			StandardItemBeltPortResolver.getHorizontalPort(level, tunnel.getBlockPos().below());
		StandardItemBeltPort beltOutput = StandardItemBeltPortResolver.getHorizontalPort(level, outputPos);
		if (below == null && beltOutput == null)
			return;

		if (stack.isEmpty()) {
			cir.setReturnValue(stack);
			return;
		}
		if (!tunnel.testFlapFilter(side, stack)) {
			cir.setReturnValue(null);
			return;
		}

		if (beltOutput != null) {
			if (!beltOutput.createBiotech$canInsertIntoItemPort(side)) {
				cir.setReturnValue(null);
				return;
			}
			ItemStack result = beltOutput.createBiotech$insertIntoItemPort(stack, side, simulate);
			if (result.isEmpty() && !simulate)
				tunnel.flap(side, false);
			cir.setReturnValue(result);
			return;
		}

		DirectBeltInputBehaviour sideOutput =
			BlockEntityBehaviour.get(level, outputPos, DirectBeltInputBehaviour.TYPE);
		if (sideOutput != null) {
			if (!sideOutput.canInsertFromSide(side)) {
				cir.setReturnValue(null);
				return;
			}
			ItemStack result = sideOutput.handleInsertion(stack, side, simulate);
			if (result.isEmpty() && !simulate)
				tunnel.flap(side, false);
			cir.setReturnValue(result);
			return;
		}

		if (below != null && side == below.createBiotech$getMovementFacing()
			&& !BlockHelper.hasBlockSolidSide(level.getBlockState(outputPos), level, outputPos, side.getOpposite())) {
			if (!simulate)
				eject(level, tunnel, below, side, stack);
			cir.setReturnValue(ItemStack.EMPTY);
			return;
		}

		cir.setReturnValue(null);
	}

	@WrapOperation(method = "addValidOutputsOf", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/belt/behaviour/DirectBeltInputBehaviour;canInsertFromSide(Lnet/minecraft/core/Direction;)Z"))
	private boolean createBiotech$validateFrontOutput(DirectBeltInputBehaviour behaviour, Direction side,
		Operation<Boolean> original, @Local BlockPos offset) {
		Level level = ((BrassTunnelBlockEntity) (Object) this).getLevel();
		StandardItemBeltPort port = level == null ? null
			: StandardItemBeltPortResolver.getHorizontalPort(level, offset);
		if (port != null)
			return port.createBiotech$canInsertIntoItemPort(side);
		return original.call(behaviour, side);
	}

	private BeltBlockEntity getBiotechBeltView(LevelAccessor world, BlockPos pos) {
		StandardItemBeltPort port = StandardItemBeltPortResolver.getHorizontalPort(world, pos);
		if (port == null)
			return null;
		if (createBiotech$beltView == null)
			createBiotech$beltView = new CBBeltTunnelBeltView(port);
		else
			createBiotech$beltView.setDelegate(port);
		return createBiotech$beltView;
	}

	private static void eject(Level level, BrassTunnelBlockEntity tunnel, StandardItemBeltPort belt,
		Direction side, ItemStack stack) {
		tunnel.flap(side, true);
		float beltMovementSpeed = belt.createBiotech$getDirectionAwareSpeed();
		float movementSpeed = Math.max(Math.abs(beltMovementSpeed), 1 / 8f);
		Vec3 outPos = belt.createBiotech$getEjectionPosition();
		Vec3 outMotion = Vec3.atLowerCornerOf(side.getNormal()).scale(movementSpeed).add(0, 1 / 8f, 0);
		ItemEntity entity = new ItemEntity(level, outPos.x, outPos.y + 6 / 16f, outPos.z, stack);
		entity.setDeltaMovement(outMotion);
		entity.setDefaultPickUpDelay();
		entity.hurtMarked = true;
		level.addFreshEntity(entity);
	}

}
