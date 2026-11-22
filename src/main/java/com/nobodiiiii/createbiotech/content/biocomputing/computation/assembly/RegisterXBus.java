package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import java.util.Objects;

/** Rendezvous extension, not MOV, a queue or a protocol. Admission costs one local CPU cycle.
 * PC and immutable input registers are frozen until the owner atomically publishes the result
 * with its existing consumption/retirement evidence. Waiting costs no additional CPU cycles.
 * Values share immutable representation; every owner still charges its full logical RAM.
 * This primitive performs no encoding, mutable deep copy or serial transmission. */
public final class RegisterXBus {
    private RegisterXBus() {}
    public enum Kind { READ, WRITE, WAIT_READABLE }
    /** -1 means no input/result register. The owner's source contract defines raw/result reads. */
    public record Instruction(Kind kind, String pin, int input, int output) implements RegisterProgram.Instruction {
        public Instruction {
            Objects.requireNonNull(kind); Objects.requireNonNull(pin);
            if (pin.isBlank() || !pin.equals(pin.trim()) || pin.codePoints().anyMatch(Character::isISOControl)
                || input < -1 || output < -1 || (kind == Kind.WRITE) != (input >= 0) || kind == Kind.READ && output < 0)
                throw new IllegalArgumentException("Invalid XBus operands");
        }
        public String extension() { return "xbus"; }
        public int version() { return 1; }
        public int words() { return Math.addExact(5, pin.length()); }
    }
}
