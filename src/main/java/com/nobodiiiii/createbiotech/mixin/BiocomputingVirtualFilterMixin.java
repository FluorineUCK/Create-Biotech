package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterTarget;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only supported Create filtering behaviours acquire rule state; native filter stacks are untouched. */
@Mixin(value = FilteringBehaviour.class, remap = false)
public abstract class BiocomputingVirtualFilterMixin implements VirtualFilterTarget.Host {
    @Unique private VirtualFilterTarget createBiotech$virtualFilter;
    @Unique private boolean createBiotech$filterChecked;

    @Override
    public VirtualFilterTarget biocomputingVirtualFilter() {
        if (!createBiotech$filterChecked) {
            createBiotech$virtualFilter = VirtualFilterTarget.create((FilteringBehaviour)(Object)this);
            createBiotech$filterChecked = true;
        }
        return createBiotech$virtualFilter;
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void createBiotech$read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (!clientPacket && biocomputingVirtualFilter() != null) createBiotech$virtualFilter.read(tag);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void createBiotech$write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (!clientPacket && biocomputingVirtualFilter() != null) createBiotech$virtualFilter.write(tag);
    }

    /** Create calls this for schematics; never export a live virtual slot through its safe NBT path. */
    public void writeSafe(CompoundTag tag, HolderLookup.Provider registries) {
        ((FilteringBehaviour)(Object)this).write(tag, registries, false);
        tag.remove(VirtualFilterTarget.ARCHIVE_KEY);
    }

    @Inject(method = "setFilter(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("RETURN"))
    private void createBiotech$nativeChanged(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && biocomputingVirtualFilter() != null) createBiotech$virtualFilter.nativeChanged();
    }

    @Inject(method = "test(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void createBiotech$test(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (biocomputingVirtualFilter() != null) createBiotech$virtualFilter.test(stack).ifPresent(cir::setReturnValue);
    }

    @Inject(method = "test(Lnet/neoforged/neoforge/fluids/FluidStack;)Z", at = @At("HEAD"), cancellable = true)
    private void createBiotech$test(FluidStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (biocomputingVirtualFilter() != null) createBiotech$virtualFilter.test(stack).ifPresent(cir::setReturnValue);
    }
}
