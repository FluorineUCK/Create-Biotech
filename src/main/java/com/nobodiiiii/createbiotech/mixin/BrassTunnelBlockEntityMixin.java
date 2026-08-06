package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlockEntity;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltHelper;
import com.nobodiiiii.createbiotech.content.slimebelt.transport.SlimeBeltTunnelBeltView;
import com.nobodiiiii.createbiotech.content.slimebelt.transport.SlimeBeltTunnelInteractionHandler;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
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
	private SlimeBeltTunnelBeltView createBiotech$beltView;

	@Redirect(method = "tick", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/belt/BeltHelper;getSegmentBE(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;)Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;"))
	private BeltBlockEntity createBiotech$getBeltBelowForTick(LevelAccessor world, BlockPos pos) {
		return getBeltView(world, pos);
	}

	@Redirect(method = "addValidOutputsOf", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/kinetics/belt/BeltHelper;getSegmentBE(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;)Lcom/simibubi/create/content/kinetics/belt/BeltBlockEntity;"))
	private BeltBlockEntity createBiotech$getBeltBelowForOutputs(LevelAccessor world, BlockPos pos) {
		return getBeltView(world, pos);
	}

	@Inject(method = "insertIntoTunnel", at = @At("HEAD"), cancellable = true)
	private void createBiotech$insertFromSlimeBelt(BrassTunnelBlockEntity tunnel, Direction side, ItemStack stack,
		boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
		Level level = tunnel.getLevel();
		if (level == null)
			return;
		BlockPos outputPos = tunnel.getBlockPos().below().relative(side);
		SlimeBeltBlockEntity below = SlimeBeltTunnelInteractionHandler.getHorizontalSlimeBelt(level,
			tunnel.getBlockPos().below());
		boolean slimeOutput = SlimeBeltTunnelInteractionHandler.getHorizontalSlimeBelt(level, outputPos) != null;
		if (below == null && !slimeOutput)
			return;

		if (stack.isEmpty()) {
			cir.setReturnValue(stack);
			return;
		}
		if (!tunnel.testFlapFilter(side, stack)) {
			cir.setReturnValue(null);
			return;
		}

		if (slimeOutput) {
			if (!SlimeBeltTunnelInteractionHandler.canInsertIntoFront(level, outputPos, side)) {
				cir.setReturnValue(null);
				return;
			}
			ItemStack result =
				SlimeBeltTunnelInteractionHandler.insertIntoFront(level, outputPos, stack, side, simulate);
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

		if (side == below.getMovementFacing()
			&& !BlockHelper.hasBlockSolidSide(level.getBlockState(outputPos), level, outputPos, side.getOpposite())) {
			SlimeBeltBlockEntity controller = below.getControllerBE();
			if (controller == null) {
				cir.setReturnValue(null);
				return;
			}
			if (!simulate)
				eject(level, tunnel, below, controller, side, stack);
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
		if (level != null && SlimeBeltTunnelInteractionHandler.getHorizontalSlimeBelt(level, offset) != null)
			return SlimeBeltTunnelInteractionHandler.canInsertIntoFront(level, offset, side);
		return original.call(behaviour, side);
	}

	private BeltBlockEntity getBeltView(LevelAccessor world, BlockPos pos) {
		BeltBlockEntity vanillaBelt = BeltHelper.getSegmentBE(world, pos);
		if (vanillaBelt != null)
			return vanillaBelt;
		SlimeBeltBlockEntity slimeBelt = SlimeBeltTunnelInteractionHandler.getHorizontalSlimeBelt(world, pos);
		if (slimeBelt == null)
			return null;
		if (createBiotech$beltView == null)
			createBiotech$beltView = new SlimeBeltTunnelBeltView(slimeBelt);
		else
			createBiotech$beltView.setDelegate(slimeBelt);
		return createBiotech$beltView;
	}

	private static void eject(Level level, BrassTunnelBlockEntity tunnel, SlimeBeltBlockEntity segment,
		SlimeBeltBlockEntity controller, Direction side, ItemStack stack) {
		tunnel.flap(side, true);
		float beltMovementSpeed = segment.getDirectionAwareBeltMovementSpeed();
		float movementSpeed = Math.max(Math.abs(beltMovementSpeed), 1 / 8f);
		int additionalOffset = beltMovementSpeed > 0 ? 1 : 0;
		Vec3 outPos = SlimeBeltHelper.getVectorForOffset(controller, segment.index + additionalOffset);
		Vec3 outMotion = Vec3.atLowerCornerOf(side.getNormal()).scale(movementSpeed).add(0, 1 / 8f, 0);
		ItemEntity entity = new ItemEntity(level, outPos.x, outPos.y + 6 / 16f, outPos.z, stack);
		entity.setDeltaMovement(outMotion);
		entity.setDefaultPickUpDelay();
		entity.hurtMarked = true;
		level.addFreshEntity(entity);
	}

}
