package com.nobodiiiii.createbiotech.content.biocomputing.network.carrier;

import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CarrierClockTest {
    @Test void independentReferencesThatStartOnDifferentTicksReachTheSameExactCarrier() {
        var sourceA = new CarrierClock.Oscillator(CarrierFrequency.whole(100));
        var sourceB = new CarrierClock.Oscillator(CarrierFrequency.whole(100));
        var dividerA = new CarrierClock.Divider(3);
        var dividerB = new CarrierClock.Divider(3);
        var multiplierA = new CarrierClock.Multiplier(2, 2);
        var multiplierB = new CarrierClock.Multiplier(2, 2);

        var referenceA = dividerA.output(sourceA.output());
        assertEquals(Optional.of(new CarrierFrequency(100, 3)), referenceA.frequency());
        assertEquals(CarrierClock.State.ACQUIRING, multiplierA.tick(referenceA.frequency()).state());
        var lockedA = multiplierA.tick(referenceA.frequency());
        assertEquals(CarrierClock.State.LOCKED, lockedA.state());
        assertEquals(Optional.of(new CarrierFrequency(200, 3)), lockedA.frequency());

        // Starting a second, independent reference later does not prevent tuning to A.
        var referenceB = dividerB.output(sourceB.output());
        assertTrue(multiplierB.tick(referenceB.frequency()).frequency().isEmpty());
        var lockedB = multiplierB.tick(referenceB.frequency());
        assertEquals(lockedA, lockedB);
    }

    @Test void savedAcquisitionDoesNotCatchUpWithoutActiveTicksAndLosingReferenceResetsIt() {
        var reference = Optional.of(new CarrierFrequency(100, 3));
        var original = new CarrierClock.Multiplier(2, 3);
        assertEquals(CarrierClock.State.ACQUIRING, original.tick(reference).state());

        var saved = original.capture();
        var resumed = new CarrierClock.Multiplier(2, 3);
        resumed.restore(saved);
        assertEquals(CarrierClock.State.ACQUIRING, resumed.tick(reference).state(),
            "Accumulated one additional active tick, not all elapsed world time");
        assertEquals(Optional.of(new CarrierFrequency(200, 3)), resumed.tick(reference).frequency());

        var lost = resumed.tick(Optional.empty());
        assertEquals(CarrierClock.State.LOST_LOCK, lost.state());
        assertTrue(lost.frequency().isEmpty());
        assertEquals(CarrierClock.State.ACQUIRING, resumed.tick(reference).state(), "Source restoration needs a new lock");
        assertThrows(IllegalArgumentException.class, () -> new CarrierClock.Multiplier(3, 3).restore(saved),
            "Capture belongs to the same device settings");
    }

    @Test void unsupportedRatiosDoNotRoundOrSubstituteFrequencyZero() {
        var maximum = Optional.of(CarrierFrequency.whole(Integer.MAX_VALUE));
        var tooHigh = new CarrierClock.Multiplier(2, 1).tick(maximum);
        var tooFine = new CarrierClock.Divider(2).output(Optional.of(new CarrierFrequency(1, Integer.MAX_VALUE)));
        for (var invalid : new CarrierClock.Output[]{tooHigh, tooFine}) {
            assertEquals(CarrierClock.State.OUT_OF_RANGE, invalid.state());
            assertTrue(invalid.frequency().isEmpty());
        }
        assertEquals(Optional.of(CarrierFrequency.whole(0)),
            new CarrierClock.Divider(2).output(Optional.of(CarrierFrequency.whole(0))).frequency(),
            "Zero was a valid explicit preset; it is not missing input");
    }
}
