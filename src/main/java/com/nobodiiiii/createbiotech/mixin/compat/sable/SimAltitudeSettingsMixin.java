package com.nobodiiiii.createbiotech.mixin.compat.sable;

import com.nobodiiiii.createbiotech.content.biocomputing.device.settings.SimulatedSettingsAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorBlockEntity", remap = false)
public abstract class SimAltitudeSettingsMixin implements SimulatedSettingsAccess.Altitude {
    @Shadow public float lowSignal;
    @Shadow public float highSignal;
    @Shadow public abstract void notifyUpdate();

    @Override public float biocomputingZeroAltitude() { return lowSignal; }
    @Override public float biocomputingFullAltitude() { return highSignal; }
    @Override public void biocomputingSetAltitude(float zero, float full) {
        lowSignal = zero;
        highSignal = full;
        notifyUpdate(); // Same native recompute used by the manual configuration.
    }
}
