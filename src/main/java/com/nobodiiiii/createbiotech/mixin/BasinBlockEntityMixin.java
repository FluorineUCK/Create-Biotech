package com.nobodiiiii.createbiotech.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nobodiiiii.createbiotech.content.processing.basin.BasinEntityProcessing;
import com.nobodiiiii.createbiotech.content.processing.basin.CapturedSmallSlimeItem;
import com.simibubi.create.content.processing.basin.BasinBlock;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandler;

@Mixin(value = BasinBlockEntity.class, priority = 1001)
public abstract class BasinBlockEntityMixin {
	@Unique
	private boolean createBiotech$spoutputTargetReserved;

	@Inject(method = "tick()V", at = @At("TAIL"), remap = false)
	private void createBiotech$migrateLegacyCapturedSmallSlimes(CallbackInfo ci) {
		BasinEntityProcessing.migrateLegacyContainedSlimes((BasinBlockEntity) (Object) this);
	}

	@Inject(method = "acceptOutputs(Ljava/util/List;Ljava/util/List;Z)Z",
		at = @At("HEAD"), cancellable = true, remap = false)
	private void createBiotech$acceptCapturedSmallSlimeOutputs(List<ItemStack> outputItems,
		List<FluidStack> outputFluids, boolean simulate, CallbackInfoReturnable<Boolean> cir) {
		int capturedSlimeCount = 0;
		List<ItemStack> otherItems = new ArrayList<>();
		for (ItemStack stack : outputItems) {
			if (BasinEntityProcessing.isCapturedSmallSlimeItem(stack)) {
				capturedSlimeCount += stack.getCount();
				continue;
			}
			otherItems.add(stack);
		}
		if (capturedSlimeCount == 0)
			return;

		BasinBlockEntity basin = (BasinBlockEntity) (Object) this;
		BlockState blockState = basin.getBlockState();
		if (blockState.getBlock() instanceof BasinBlock
			&& blockState.getValue(BasinBlock.FACING) != Direction.DOWN)
			return;

		List<ItemStack> capturedSlimeItems = List.of(new ItemStack(outputItems.stream()
			.filter(BasinEntityProcessing::isCapturedSmallSlimeItem)
			.findFirst()
			.orElse(ItemStack.EMPTY)
			.getItem(), capturedSlimeCount));
		if (!BasinEntityProcessing.acceptsCapturedSmallSlimeOutput(basin, capturedSlimeItems, true)
			|| !basin.acceptOutputs(otherItems, outputFluids, true)) {
			cir.setReturnValue(false);
			return;
		}

		if (!simulate) {
			if (!BasinEntityProcessing.acceptsCapturedSmallSlimeOutput(basin, capturedSlimeItems, false)) {
				cir.setReturnValue(false);
				return;
			}
			if (!basin.acceptOutputs(otherItems, outputFluids, false)) {
				cir.setReturnValue(false);
				return;
			}
			basin.notifyUpdate();
		}

		cir.setReturnValue(true);
	}

	@Inject(method = "tryClearingSpoutputOverflow()V", at = @At("HEAD"), remap = false)
	private void createBiotech$beginSpoutputTransfer(CallbackInfo ci) {
		createBiotech$spoutputTargetReserved = false;
	}

	@WrapOperation(
		method = "tryClearingSpoutputOverflow()V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/neoforged/neoforge/items/ItemHandlerHelper;insertItemStacked(Lnet/neoforged/neoforge/items/IItemHandler;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack createBiotech$materializeAcceptedSmallSlimeSpoutput(IItemHandler target, ItemStack stack,
		boolean simulate, Operation<ItemStack> original) {
		// A direct-belt target accepts one transported stack at a time. A materialized
		// slime does not occupy that item handler, so reserve it virtually for the rest
		// of this pass to preserve the same one-transfer scheduling as ordinary items.
		if (createBiotech$spoutputTargetReserved)
			return stack;
		if (!BasinEntityProcessing.isCapturedSmallSlimeItem(stack) || simulate)
			return original.call(target, stack, simulate);

		ItemStack remainder = original.call(target, stack, true);
		int accepted = stack.getCount() - remainder.getCount();
		if (accepted <= 0)
			return stack;

		BasinBlockEntity basin = (BasinBlockEntity) (Object) this;
		BlockState blockState = basin.getBlockState();
		if (!(blockState.getBlock() instanceof BasinBlock))
			return stack;
		Direction direction = blockState.getValue(BasinBlock.FACING);
		if (!direction.getAxis().isHorizontal())
			return stack;

		Vec3 directionVector = Vec3.atLowerCornerOf(direction.getNormal());
		Vec3 outputPosition = Vec3.atCenterOf(basin.getBlockPos())
			.add(directionVector.scale(.65d))
			.subtract(0, .25d, 0);
		Vec3 outputMotion = directionVector.scale(1 / 16d)
			.add(0, -1 / 16d, 0);
		ItemStack acceptedStack = stack.copyWithCount(accepted);
		if (!CapturedSmallSlimeItem.materializeTransportedStack(
			basin.getLevel(), outputPosition, outputMotion, acceptedStack))
			return stack;

		createBiotech$spoutputTargetReserved = true;
		return remainder;
	}
}
