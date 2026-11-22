package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

/** Native settings of optional Simulated components, without linking the main module to Simulated. */
public interface SimulatedSettingsAccess {
    interface Inductor {
        com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour biocomputingInputDelay();
        void biocomputingRefreshSignal();
    }

    interface Altitude {
        float biocomputingZeroAltitude();
        float biocomputingFullAltitude();
        void biocomputingSetAltitude(float zero, float full);
    }

    interface Receiver {
        int biocomputingMinimumDistance();
        int biocomputingMaximumDistance();
        int biocomputingDistanceLimit();
        void biocomputingSetDistances(int minimum, int maximum);
    }
}
