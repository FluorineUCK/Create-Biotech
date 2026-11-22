package com.nobodiiiii.createbiotech.mixin;

import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.RetiredStockOrders;
import com.simibubi.create.content.logistics.packager.IdentifiedInventory;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.RequestType;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LogisticsManager.class, remap = false)
public abstract class LogisticsManagerPatternRequestMixin {
    /** No player context exists here. Unhandled virtual entries must never reach physical packagers. */
    @Inject(method = "broadcastPackageRequest", at = @At("HEAD"), cancellable = true)
    private static void createBiotech$rejectUnhandledPatterns(UUID network, RequestType type,
        PackageOrderWithCrafts order, IdentifiedInventory ignored, String address, CallbackInfoReturnable<Boolean> cir) {
        if (RetiredStockOrders.containsToken(order)) cir.setReturnValue(false);
    }
}
