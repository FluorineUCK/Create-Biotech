package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.RetiredStockOrders;
import net.minecraft.network.chat.Component;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderRequestPacket;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PackageOrderRequestPacket.class, remap = false)
public abstract class StockKeeperPatternOrderMixin {
    @Shadow @Final private PackageOrderWithCrafts order;
    @Inject(method = "applySettings(Lnet/minecraft/server/level/ServerPlayer;Lcom/simibubi/create/content/logistics/stockTicker/StockTickerBlockEntity;)V",
        at = @At("HEAD"), cancellable = true)
    private void createBiotech$rejectRetiredToken(ServerPlayer player, StockTickerBlockEntity ticker, CallbackInfo ci) {
        if (!RetiredStockOrders.containsToken(order)) return;
        player.displayClientMessage(Component.translatable("create_biotech.stock_application.code.retired_pattern"), false);
        ci.cancel();
    }
}
