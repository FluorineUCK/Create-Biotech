package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockKeeperApplicationBinding;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StockTickerBlockEntity.class)
public abstract class StockTickerApplicationMixin {
    @Inject(method = "addBehaviours(Ljava/util/List;)V", at = @At("TAIL"), remap = false)
    private void createBiotech$addFrontendBinding(List<BlockEntityBehaviour> behaviours, CallbackInfo ci) {
        behaviours.add(new StockKeeperApplicationBinding((StockTickerBlockEntity)(Object)this));
    }
}
