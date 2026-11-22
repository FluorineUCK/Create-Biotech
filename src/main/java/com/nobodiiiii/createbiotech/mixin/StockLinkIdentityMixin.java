package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockLinkIdentity;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PackagerLinkBlockEntity.class)
public abstract class StockLinkIdentityMixin {
    @Inject(method = "addBehaviours(Ljava/util/List;)V", at = @At("TAIL"), remap = false)
    private void createBiotech$identity(List<BlockEntityBehaviour> behaviours, CallbackInfo ci) {
        behaviours.add(new StockLinkIdentity((PackagerLinkBlockEntity)(Object)this));
    }
}
