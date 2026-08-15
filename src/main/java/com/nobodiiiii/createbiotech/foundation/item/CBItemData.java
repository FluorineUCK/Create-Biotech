package com.nobodiiiii.createbiotech.foundation.item;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class CBItemData {
	private CBItemData() {
	}

	@Nullable
	public static CompoundTag get(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? null : data.copyTag();
	}

	/**
	 * Returns the immutable component instance stored on the stack. Callers must not
	 * mutate {@link CustomData#getUnsafe()} or any tag reachable through it.
	 */
	@Nullable
	public static CustomData getReadOnlyComponent(ItemStack stack) {
		return stack.get(DataComponents.CUSTOM_DATA);
	}

	/**
	 * Returns a zero-copy view of the stack's custom data. This is intentionally
	 * read-only; use {@link #edit(ItemStack, Consumer)} for writes.
	 */
	@Nullable
	public static CompoundTag getReadOnly(ItemStack stack) {
		CustomData data = getReadOnlyComponent(stack);
		return data == null ? null : data.getUnsafe();
	}

	public static CompoundTag getOrEmpty(ItemStack stack) {
		return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
	}

	public static boolean has(ItemStack stack) {
		return stack.has(DataComponents.CUSTOM_DATA);
	}

	public static void set(ItemStack stack, @Nullable CompoundTag tag) {
		if (tag == null || tag.isEmpty())
			stack.remove(DataComponents.CUSTOM_DATA);
		else
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	public static void edit(ItemStack stack, Consumer<CompoundTag> editor) {
		CompoundTag tag = getOrEmpty(stack);
		editor.accept(tag);
		set(stack, tag);
	}
}
