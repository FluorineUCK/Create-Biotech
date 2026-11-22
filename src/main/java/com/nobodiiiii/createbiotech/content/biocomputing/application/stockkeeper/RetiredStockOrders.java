package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** A retired virtual catalog entry is not physical stock, regardless of its components. */
public final class RetiredStockOrders {
    public static final String CODE = "retired_pattern";
    private static final ResourceLocation DISPLAY_ID = ResourceLocation.parse("create_biotech:pattern_display");

    private RetiredStockOrders() {}

    public static boolean containsToken(PackageOrderWithCrafts order) {
        return order.stacks().stream().anyMatch(entry -> isToken(entry.stack))
            || order.orderedCrafts().stream().anyMatch(craft ->
                craft.pattern().stacks().stream().anyMatch(entry -> isToken(entry.stack)));
    }

    private static boolean isToken(ItemStack stack) {
        return !stack.isEmpty() && DISPLAY_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    public static String rejectionCode(IllegalArgumentException cause) {
        return CODE.equals(cause.getMessage()) ? CODE : "invalid_native_order";
    }
}
