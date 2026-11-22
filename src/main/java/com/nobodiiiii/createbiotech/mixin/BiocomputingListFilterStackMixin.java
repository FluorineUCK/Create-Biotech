package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualListFilter;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FilterItemStack.ListFilterItemStack.class, remap = false)
public abstract class BiocomputingListFilterStackMixin implements VirtualListFilter.Wrapped {
    @Shadow public List<FilterItemStack> containedItems;
    @Unique private VirtualListFilter.Compiled createBiotech$virtualEntries;

    @Override
    public VirtualListFilter.Compiled biocomputingVirtualEntries() { return createBiotech$virtualEntries; }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void createBiotech$compile(ItemStack stack, CallbackInfo ci) {
        createBiotech$virtualEntries = VirtualListFilter.compile(stack, containedItems);
    }

    @Inject(method = "test(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Z)Z", at = @At("HEAD"), cancellable = true)
    private void createBiotech$testItem(Level level, ItemStack stack, boolean matchNbt, CallbackInfoReturnable<Boolean> cir) {
        if (createBiotech$virtualEntries != null) createBiotech$virtualEntries.test(stack).ifPresent(cir::setReturnValue);
    }

    @Inject(method = "test(Lnet/minecraft/world/level/Level;Lnet/neoforged/neoforge/fluids/FluidStack;Z)Z", at = @At("HEAD"), cancellable = true)
    private void createBiotech$testFluid(Level level, FluidStack stack, boolean matchNbt, CallbackInfoReturnable<Boolean> cir) {
        if (createBiotech$virtualEntries != null) createBiotech$virtualEntries.test(stack).ifPresent(cir::setReturnValue);
    }
}
