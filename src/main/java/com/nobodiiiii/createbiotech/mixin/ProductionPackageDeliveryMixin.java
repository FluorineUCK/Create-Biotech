package com.nobodiiiii.createbiotech.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.create.CreateProductionReceipts;
import com.simibubi.create.api.packager.unpacking.UnpackingHandler;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import java.util.List;
import net.minecraft.core.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Independent new-backend hook. Observing may never turn an already applied native effect into failure. */
@Mixin(PackagerBlockEntity.class)
public abstract class ProductionPackageDeliveryMixin {
    @WrapOperation(method = "unwrapBox", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/api/packager/unpacking/UnpackingHandler;unpack(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Ljava/util/List;Lcom/simibubi/create/content/logistics/stockTicker/PackageOrderWithCrafts;Z)Z"), remap = false)
    private boolean createBiotech$productionDelivery(UnpackingHandler handler, Level level, BlockPos target, BlockState targetState,
            Direction facing, List<ItemStack> items, PackageOrderWithCrafts context, boolean simulate, Operation<Boolean> original,
            @Local(argsOnly = true) ItemStack box) {
        Runnable receipt = null;
        if (!simulate) try { receipt = CreateProductionReceipts.prepare((PackagerBlockEntity)(Object)this, box); }
        catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(ProductionPackageDeliveryMixin.class).error("Could not prepare native production evidence", failure); }
        boolean accepted = original.call(handler, level, target, targetState, facing, items, context, simulate);
        if (accepted && receipt != null) try { receipt.run(); }
        catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(ProductionPackageDeliveryMixin.class).error("Could not publish accepted native production evidence", failure); }
        return accepted;
    }
}
