package com.nobodiiiii.createbiotech.content.biocomputing.network.carrier;

import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.CarrierConverterState;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.CarrierHardwareConfig;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CarrierConverterStateTest {
    private static final CarrierHardwareConfig.Limits LIMITS = new CarrierHardwareConfig.Limits(16, 16, 3, 2, 3);
    private static final CarrierClock.Output INPUT = CarrierClock.Output.valid(new CarrierFrequency(100, 3));

    @Test void nbtResumesOnlyActiveLockTicksAndIndependentFansDoNotAdvanceItTwice() {
        var original = new CarrierConverterState(true);
        assertTrue(original.advance(10, INPUT, LIMITS));
        assertEquals(CarrierClock.State.ACQUIRING, original.output().state());
        assertFalse(original.advance(10, INPUT, LIMITS), "A second observer does not make a hardware tick");

        var resumed = new CarrierConverterState(true);
        resumed.read(original.write());
        resumed.advance(510000, INPUT, LIMITS);
        assertEquals(CarrierClock.State.ACQUIRING, resumed.output().state(), "Unloaded time is not lock time");
        resumed.advance(510001, INPUT, LIMITS);
        assertEquals(Optional.of(new CarrierFrequency(200, 3)), resumed.output().frequency());
        resumed.advance(510002, CarrierClock.Output.unavailable(CarrierClock.State.UNAVAILABLE), LIMITS);
        assertTrue(resumed.output().frequency().isEmpty());
        resumed.advance(510003, INPUT, LIMITS);
        assertEquals(CarrierClock.State.ACQUIRING, resumed.output().state(), "A fresh reference takes time to acquire");
    }
}
