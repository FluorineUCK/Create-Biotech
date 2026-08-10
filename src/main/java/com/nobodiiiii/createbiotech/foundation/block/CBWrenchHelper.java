package com.nobodiiiii.createbiotech.foundation.block;

import com.simibubi.create.AllItems;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.Tags;

public final class CBWrenchHelper {

	private CBWrenchHelper() {}

	public static boolean isWrench(ItemStack stack) {
		return AllItems.WRENCH.isIn(stack)
			|| stack.is(Tags.Items.TOOLS_WRENCH);
	}
}
