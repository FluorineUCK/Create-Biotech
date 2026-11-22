package com.nobodiiiii.createbiotech.mixin.client;

import com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeScreenSession;
import net.minecraft.client.gui.screens.inventory.CreativeInventoryListener;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreativeInventoryListener.class)
public abstract class LibraryCreativeListenerMixin {
    @Inject(method = "slotChanged", at = @At("HEAD"), cancellable = true)
    private void biocomputing$noLossyEcho(AbstractContainerMenu menu, int slot, ItemStack stack, CallbackInfo ci) {
        if (LibraryCreativeScreenSession.suppressLegacyEcho()) ci.cancel();
    }
}
