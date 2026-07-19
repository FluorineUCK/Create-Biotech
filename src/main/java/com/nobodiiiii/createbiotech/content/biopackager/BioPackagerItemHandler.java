package com.nobodiiiii.createbiotech.content.biopackager;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemHandlerHelper;

public class BioPackagerItemHandler implements IItemHandlerModifiable {

	private final BioPackagerBlockEntity blockEntity;

	public BioPackagerItemHandler(BioPackagerBlockEntity blockEntity) {
		this.blockEntity = blockEntity;
	}

	@Override
	public int getSlots() {
		return 1;
	}

	@Override
	public ItemStack getStackInSlot(int slot) {
		return blockEntity.heldBox;
	}

	@Override
	public void setStackInSlot(int slot, ItemStack stack) {
		if (slot != 0)
			return;
		blockEntity.heldBox = stack;
		blockEntity.notifyUpdate();
	}

	@Override
	public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
		if (!isItemValid(slot, stack))
			return stack;
		if (!blockEntity.heldBox.isEmpty() || blockEntity.animationTicks > 0)
			return stack;
		if (simulate)
			return stack.copyWithCount(stack.getCount() - 1);
		ItemStack toInsert = stack.copy();
		toInsert.setCount(1);
		if (!blockEntity.startUnpacking(toInsert))
			return stack;
		return stack.copyWithCount(stack.getCount() - 1);
	}

	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		if (blockEntity.animationTicks != 0)
			return ItemStack.EMPTY;
		ItemStack box = blockEntity.heldBox;
		if (box.isEmpty())
			return ItemStack.EMPTY;
		if (simulate)
			return box.copy();
		setStackInSlot(slot, ItemStack.EMPTY);
		return box;
	}

	@Override
	public int getSlotLimit(int slot) {
		return 1;
	}

	@Override
	public boolean isItemValid(int slot, ItemStack stack) {
		return stack.getItem() instanceof CapturedEntityBoxItem
			&& CapturedEntityBoxItem.hasCapturedEntity(stack);
	}
}
