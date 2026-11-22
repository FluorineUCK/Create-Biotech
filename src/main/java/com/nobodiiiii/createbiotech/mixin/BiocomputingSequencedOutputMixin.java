package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.SequencedMotion;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clear late restored output before native attachment or mechanical tick work. */
@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class BiocomputingSequencedOutputMixin {
    @Inject(method = {"initialize", "tick"}, at = @At("HEAD"))
    private void createBiotech$retireStaleSequencedOutput(CallbackInfo ci) {
        SequencedMotion.retireStaleOutput((KineticBlockEntity)(Object)this);
    }
}
