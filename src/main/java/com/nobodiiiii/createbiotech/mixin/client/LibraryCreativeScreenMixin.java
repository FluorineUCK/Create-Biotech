package com.nobodiiiii.createbiotech.mixin.client;

import com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeScreenSession;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreativeModeInventoryScreen.class)
public abstract class LibraryCreativeScreenMixin implements LibraryCreativeScreenSession.Access {
    @Shadow private Slot destroyItemSlot;
    @Shadow private boolean hasClickedOutside;
    @Unique private final LibraryCreativeScreenSession biocomputing$session = new LibraryCreativeScreenSession();
    @Override public LibraryCreativeScreenSession biocomputing$inventorySession() { return biocomputing$session; }

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void biocomputing$click(Slot slot, int slotId, int button, ClickType type, CallbackInfo ci) {
        if (biocomputing$session.before((CreativeModeInventoryScreen) (Object) this, slot, slotId, button, type,
                destroyItemSlot, hasClickedOutside)) ci.cancel();
    }
    @Inject(method = "slotClicked", at = @At("RETURN"))
    private void biocomputing$catalogue(Slot slot, int slotId, int button, ClickType type, CallbackInfo ci) {
        biocomputing$session.after();
    }
}
