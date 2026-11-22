package com.nobodiiiii.createbiotech.mixin.compat.sable;

import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.SimulatedSettingsAccess;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.redstone.redstone_inductor.RedstoneInductorBlockEntity", remap = false)
public abstract class SimInductorSettingsMixin implements SimulatedSettingsAccess.Inductor {
    @Shadow protected ScrollValueBehaviour inputDelay;
    @Shadow public abstract void updateSignal();

    @Override public ScrollValueBehaviour biocomputingInputDelay() { return inputDelay; }
    @Override public void biocomputingRefreshSignal() { updateSignal(); }
}
