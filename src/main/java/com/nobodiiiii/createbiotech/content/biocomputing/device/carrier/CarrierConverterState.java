package com.nobodiiiii.createbiotech.content.biocomputing.device.carrier;

import com.nobodiiiii.createbiotech.content.biocomputing.hardware.CarrierHardwareConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierClock;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierFrequency;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Persistent settings and bounded capture time for one physical converter. */
public final class CarrierConverterState {
    private final boolean multiply;
    private int factor;
    private CarrierClock.Capture capture;
    private CarrierClock.Multiplier pll;
    private long observedTick = Long.MIN_VALUE;
    private CarrierClock.Output input = CarrierClock.Output.unavailable(CarrierClock.State.UNAVAILABLE);
    private CarrierClock.Output output = input;
    private Tag invalid;

    public CarrierConverterState(boolean multiply) { this.multiply = multiply; }
    boolean valid() { return invalid == null; }
    public CarrierClock.Output input() { return input; }
    public CarrierClock.Output output() { return output; }
    public int factor(CarrierHardwareConfig.Limits limits) { return factor == 0 ? limits.initialFactor(multiply) : factor; }

    public boolean advance(long tick, CarrierClock.Output supplied, CarrierHardwareConfig.Limits limits) {
        if (observedTick == tick) return false;
        observedTick = tick;
        input = Objects.requireNonNull(supplied);
        int previous = factor;
        var previousCapture = capture;
        if (invalid != null) {
            output = CarrierClock.Output.unavailable(CarrierClock.State.UNAVAILABLE);
            return false;
        }
        factor = factor(limits);
        if (factor > limits.maxFactor()) {
            capture = null;
            pll = null;
            output = CarrierClock.Output.unavailable(CarrierClock.State.OUT_OF_RANGE);
        } else if (multiply) {
            if (pll == null || pll.capture().requiredTicks() != limits.lockTicks()) {
                pll = new CarrierClock.Multiplier(factor, limits.lockTicks());
                if (capture != null && capture.requiredTicks() == limits.lockTicks()) pll.restore(capture);
            }
            output = pll.tick(input.frequency());
            capture = pll.capture();
        } else {
            output = new CarrierClock.Divider(factor).output(input.frequency());
        }
        // When the upstream line fails, retain its physical diagnostic for the downstream port.
        if (input.frequency().isEmpty() && output.state() == CarrierClock.State.LOST_LOCK) output = input;
        return previous != factor || !Objects.equals(previousCapture, capture);
    }

    public void setFactor(int selected, CarrierHardwareConfig.Limits limits) {
        if (selected < 1 || selected > limits.maxFactor()) throw new IllegalArgumentException("carrier_factor_out_of_range");
        if (invalid == null && factor == selected) return;
        invalid = null;
        factor = selected;
        invalidateReference();
    }

    public void invalidateReference() {
        capture = null;
        pll = null;
        observedTick = Long.MIN_VALUE;
        input = CarrierClock.Output.unavailable(CarrierClock.State.UNAVAILABLE);
        output = input;
    }

    public Tag write() {
        if (invalid != null) return invalid.copy();
        if (factor == 0) return null;
        var tag = new CompoundTag();
        tag.putInt("Version", 1);
        tag.putInt("Factor", factor);
        if (capture != null && capture.activeTicks() > 0) {
            var reference = capture.reference().orElseThrow();
            var acquisition = new CompoundTag();
            acquisition.putInt("Numerator", reference.numerator());
            acquisition.putInt("Denominator", reference.denominator());
            acquisition.putInt("RequiredTicks", capture.requiredTicks());
            acquisition.putInt("ActiveTicks", capture.activeTicks());
            tag.put("Capture", acquisition);
        }
        return tag;
    }

    public void read(Tag saved) {
        invalidateReference();
        invalid = null;
        factor = 0;
        if (saved == null) return;
        try {
            if (!(saved instanceof CompoundTag tag) || !tag.contains("Version", Tag.TAG_INT)
                || tag.getInt("Version") != 1 || !tag.contains("Factor", Tag.TAG_INT) || tag.getInt("Factor") < 1
                || !tag.getAllKeys().equals(tag.contains("Capture") ? Set.of("Version", "Factor", "Capture") : Set.of("Version", "Factor")))
                throw new IllegalArgumentException("Invalid carrier hardware save");
            int savedFactor = tag.getInt("Factor");
            if (tag.contains("Capture")) {
                if (!multiply || !tag.contains("Capture", Tag.TAG_COMPOUND))
                    throw new IllegalArgumentException("Unexpected carrier capture");
                var lock = tag.getCompound("Capture");
                var keys = Set.of("Numerator", "Denominator", "RequiredTicks", "ActiveTicks");
                if (!lock.getAllKeys().equals(keys)) throw new IllegalArgumentException("Unknown capture format");
                for (String key : keys)
                    if (!lock.contains(key, Tag.TAG_INT)) throw new IllegalArgumentException("Incomplete carrier capture");
                var reference = new CarrierFrequency(lock.getInt("Numerator"), lock.getInt("Denominator"));
                var restored = new CarrierClock.Capture(savedFactor, lock.getInt("RequiredTicks"),
                    Optional.of(reference), lock.getInt("ActiveTicks"));
                var validator = new CarrierClock.Multiplier(savedFactor, restored.requiredTicks());
                validator.restore(restored);
                capture = restored;
            }
            factor = savedFactor;
        } catch (IllegalArgumentException malformed) {
            invalid = saved.copy();
            invalidateReference();
        }
    }
}
