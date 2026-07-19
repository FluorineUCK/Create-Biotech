package com.yision.allay.item.allaycourier;

import com.mojang.serialization.Codec;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

public record AllayCourierCargo(ItemStack packageStack) {
	public static final Codec<AllayCourierCargo> CODEC =
		ItemStack.OPTIONAL_CODEC.xmap(AllayCourierCargo::new, AllayCourierCargo::packageCopy);

	public static final StreamCodec<RegistryFriendlyByteBuf, AllayCourierCargo> STREAM_CODEC =
		ItemStack.OPTIONAL_STREAM_CODEC.map(AllayCourierCargo::new, AllayCourierCargo::packageCopy);

	public AllayCourierCargo {
		packageStack = sanitize(packageStack);
	}

	public boolean isValid() {
		return PackageItem.isPackage(packageStack);
	}

	public ItemStack packageCopy() {
		return packageStack.copy();
	}

	private static ItemStack sanitize(ItemStack stack) {
		if (!PackageItem.isPackage(stack)) {
			return ItemStack.EMPTY;
		}

		ItemStack copy = stack.copy();
		copy.setCount(1);
		return copy;
	}
}
