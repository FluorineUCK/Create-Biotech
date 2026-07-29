package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.processing.basin.BasinEntityProcessing;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinInventory;
import com.simibubi.create.foundation.item.SmartInventory;

import net.minecraft.world.item.ItemStack;

/**
 * Keeps captured-slime transfer rules on Create's native basin inventory.
 *
 * <p>Do not wrap {@code BasinBlockEntity.itemCapability}: doing so adds another
 * {@code IItemHandler} dispatch to every item insertion in every Create basin,
 * including worlds that never use captured slimes.</p>
 */
@Mixin(value = BasinInventory.class, priority = 1001)
public abstract class BasinInventoryMixin extends SmartInventory {

	protected BasinInventoryMixin(int slots, BasinBlockEntity basin) {
		super(slots, basin, 64, true);
	}

	@Inject(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
		at = @At("HEAD"), cancellable = true)
	private void createBiotech$rejectExternalCapturedSlimeInsertion(int slot, ItemStack stack, boolean simulate,
		CallbackInfoReturnable<ItemStack> cir) {
		if (BasinEntityProcessing.isCapturedSmallSlimeItem(stack)
			&& !BasinEntityProcessing.canMoveCapturedSmallSlimeItems())
			cir.setReturnValue(stack);
	}

	@Inject(method = "extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;",
		at = @At("HEAD"), cancellable = true)
	private void createBiotech$rejectExternalCapturedSlimeExtraction(int slot, int amount, boolean simulate,
		CallbackInfoReturnable<ItemStack> cir) {
		if (BasinEntityProcessing.isCapturedSmallSlimeItem(getStackInSlot(slot))
			&& !BasinEntityProcessing.canMoveCapturedSmallSlimeItems())
			cir.setReturnValue(ItemStack.EMPTY);
	}

	@Override
	public boolean isItemValid(int slot, ItemStack stack) {
		if (BasinEntityProcessing.isCapturedSmallSlimeItem(stack)
			&& !BasinEntityProcessing.canMoveCapturedSmallSlimeItems())
			return false;
		return super.isItemValid(slot, stack);
	}

	@Override
	public void setStackInSlot(int slot, ItemStack stack) {
		ItemStack current = getStackInSlot(slot);
		if ((BasinEntityProcessing.isCapturedSmallSlimeItem(current)
			|| BasinEntityProcessing.isCapturedSmallSlimeItem(stack))
			&& !BasinEntityProcessing.canMoveCapturedSmallSlimeItems())
			return;
		super.setStackInSlot(slot, stack);
	}
}
