package com.nobodiiiii.createbiotech.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.DisplayLinkRows;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = DisplayLinkBlockEntity.class, remap = false)
public abstract class BiocomputingDisplayLinkRowsMixin implements DisplayLinkRows.Guarded {
    @WrapOperation(method = "updateGatheredData", at = @At(value = "INVOKE", target =
        "Lcom/simibubi/create/api/behaviour/display/DisplaySource;transferData(Lcom/simibubi/create/content/redstone/displayLink/DisplayLinkContext;Lcom/simibubi/create/api/behaviour/display/DisplayTarget;I)V"))
    private void createBiotech$preserveReservedStartingRow(DisplaySource source, DisplayLinkContext context,
                                                          DisplayTarget target, int row, Operation<Void> original) {
        if (DisplayLinkRows.mayTransfer(context, target, row)) original.call(source, context, target, row);
    }
}
