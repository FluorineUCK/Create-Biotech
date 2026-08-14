package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.Objects;

import net.minecraft.world.item.ItemStack;

public record StackKey(ItemStack stack) {
	public StackKey {
		Objects.requireNonNull(stack, "stack");
		if (stack.isEmpty())
			throw new IllegalArgumentException("StackKey cannot be empty");
		stack = stack.copyWithCount(1);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof StackKey key
			&& ItemStack.isSameItemSameComponents(stack, key.stack);
	}

	@Override
	public int hashCode() {
		return ItemStack.hashItemAndComponents(stack);
	}

	@Override
	public ItemStack stack() {
		return stack.copy();
	}
}
