package com.nobodiiiii.createbiotech.mixin.compat.sable;

import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.SimulatedSettingsAccess;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.redstone.modulating_receiver.ModulatingLinkedReceiverBlockEntity", remap = false)
public abstract class SimReceiverSettingsMixin implements SimulatedSettingsAccess.Receiver {
    @Shadow public int minRange;
    @Shadow public int maxRange;
    @Shadow public static int RANGE_LIMIT;

    @Override public int biocomputingMinimumDistance() { return minRange; }
    @Override public int biocomputingMaximumDistance() { return maxRange; }
    @Override public int biocomputingDistanceLimit() { return RANGE_LIMIT; }
    @Override public void biocomputingSetDistances(int minimum, int maximum) {
        minRange = minimum;
        maxRange = maximum;
        ((SmartBlockEntity)(Object)this).notifyUpdate(); // The native receiver updates reception on its own cadence.
    }
}
