package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualListFilter;
import com.simibubi.create.content.logistics.filter.FilterMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FilterMenu.class, remap = false)
public abstract class BiocomputingListFilterMenuMixin {
    @Inject(method = "saveData(Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"))
    private void createBiotech$reconcile(ItemStack stack, CallbackInfo ci) {
        VirtualListFilter.reconcile(stack);
    }
}
