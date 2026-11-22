package com.nobodiiiii.createbiotech.content.biocomputing.network.carrier;

import java.util.Objects;
import java.util.Optional;

/** Scalar clock hardware. Only acquisition takes time; the clock line carries no pulses or phase. */
public final class CarrierClock {
    private CarrierClock() {}

    public enum State { LOCKED, ACQUIRING, LOST_LOCK, OUT_OF_RANGE, UNAVAILABLE, WRONG_FACE, PATH_LIMIT, LOOP }

    public record Output(State state, Optional<CarrierFrequency> frequency) {
        public Output {
            Objects.requireNonNull(state);
            Objects.requireNonNull(frequency);
            if ((state == State.LOCKED) != frequency.isPresent())
                throw new IllegalArgumentException("Only a locked clock has a valid output");
        }

        public static Output valid(CarrierFrequency frequency) { return new Output(State.LOCKED, Optional.of(frequency)); }
        public static Output unavailable(State state) { return new Output(state, Optional.empty()); }
    }

    public record Oscillator(CarrierFrequency frequency) {
        public Oscillator { Objects.requireNonNull(frequency); }
        public Optional<CarrierFrequency> output() { return Optional.of(frequency); }
    }

    /** A divider has no timing state and never turns a missing reference into frequency zero. */
    public record Divider(int factor) {
        public Divider {
            if (factor < 1) throw new IllegalArgumentException("Invalid divider factor");
        }

        public Output output(Optional<CarrierFrequency> reference) {
            if (reference.isEmpty()) return Output.unavailable(State.LOST_LOCK);
            return reference.orElseThrow().scaled(1, factor).map(Output::valid)
                .orElseGet(() -> Output.unavailable(State.OUT_OF_RANGE));
        }
    }

    /** The only temporal state to persist; no world timestamps, phase or pending pulses. */
    public record Capture(int factor, int requiredTicks, Optional<CarrierFrequency> reference, int activeTicks) {
        public Capture {
            Objects.requireNonNull(reference);
            if (factor < 1 || requiredTicks < 1 || activeTicks < 0 || activeTicks > requiredTicks
                || activeTicks > 0 && reference.isEmpty())
                throw new IllegalArgumentException("Invalid carrier lock snapshot");
        }
    }

    public static final class Multiplier {
        private final int factor;
        private final int acquisitionTicks;
        private Optional<CarrierFrequency> reference = Optional.empty();
        private Optional<CarrierFrequency> frequency = Optional.empty();
        private int acquiredTicks;

        public Multiplier(int factor, int acquisitionTicks) {
            if (factor < 1 || acquisitionTicks < 1) throw new IllegalArgumentException("Invalid multiplier specification");
            this.factor = factor;
            this.acquisitionTicks = acquisitionTicks;
        }

        /** Call once per active hardware tick. A host calls reset on physical reference replacement. */
        public Output tick(Optional<CarrierFrequency> input) {
            if (!reference.equals(input)) {
                reference = input;
                frequency = input.flatMap(signal -> signal.scaled(factor, 1));
                acquiredTicks = 0;
            }
            if (reference.isEmpty()) return Output.unavailable(State.LOST_LOCK);
            if (frequency.isEmpty()) return Output.unavailable(State.OUT_OF_RANGE);
            if (acquiredTicks < acquisitionTicks) acquiredTicks++;
            return acquiredTicks < acquisitionTicks ? Output.unavailable(State.ACQUIRING)
                : Output.valid(frequency.orElseThrow());
        }

        public Capture capture() {
            return new Capture(factor, acquisitionTicks, reference, acquiredTicks);
        }

        public void restore(Capture capture) {
            if (capture.factor() != factor || capture.requiredTicks() != acquisitionTicks)
                throw new IllegalArgumentException("Carrier hardware settings changed");
            var restored = capture.reference().flatMap(signal -> signal.scaled(factor, 1));
            if (capture.activeTicks() > 0 && restored.isEmpty())
                throw new IllegalArgumentException("Lock progress without a valid carrier");
            reference = capture.reference();
            frequency = restored;
            acquiredTicks = capture.activeTicks();
        }

        public void reset() {
            reference = Optional.empty();
            frequency = Optional.empty();
            acquiredTicks = 0;
        }
    }
}
