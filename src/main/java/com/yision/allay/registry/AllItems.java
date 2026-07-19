package com.yision.allay.registry;

import com.nobodiiiii.createbiotech.registry.CBItems;
import com.yision.allay.item.allaycourier.AllayCourierItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class AllItems {
	public static final ItemRef<AllayCourierItem> ALLAY_COURIER = new ItemRef<>(CBItems.ALLAY_COURIER);

	private AllItems() {}

	public record ItemRef<T extends Item>(DeferredHolder<Item, T> object) {
		public T get() {
			return object.get();
		}

		public ItemStack asStack() {
			return new ItemStack(get());
		}
	}
}
