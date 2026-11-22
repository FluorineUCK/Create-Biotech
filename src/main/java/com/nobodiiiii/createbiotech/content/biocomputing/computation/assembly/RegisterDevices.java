package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import java.util.Objects;

/** Closed, versioned scalar device primitives. No world callbacks, extra scheduler or implicit protocol.
 * These three operations consume one CPU cycle, have no device wait and commit with the register file.
 * Blocking/variable-size operations require their own metered continuation contract, not this shortcut. */
public final class RegisterDevices {
    private RegisterDevices() {}
    public enum Kind {
        SIO_READ("sio", true), SIO_WRITE("sio", false), XBUS_STATUS("xbus_status", true);
        private final String extension;
        private final boolean result;
        Kind(String extension, boolean result) { this.extension = extension; this.result = result; }
        public String extension() { return extension; }
        public int version() { return 1; }
        public boolean writesRegister() { return result; }
    }
    /** The addressed register is INTEGER, either the input or result according to kind. */
    public record Instruction(Kind kind, int register, String pin) implements RegisterProgram.Instruction {
        public Instruction {
            Objects.requireNonNull(kind); Objects.requireNonNull(pin);
            if (pin.isBlank() || !pin.equals(pin.trim()) || pin.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid device pin");
        }
        // Opcode, extension version, register, length, then the UTF-16 pin name.
        public int words() { return Math.addExact(4, pin.length()); }
    }
}
