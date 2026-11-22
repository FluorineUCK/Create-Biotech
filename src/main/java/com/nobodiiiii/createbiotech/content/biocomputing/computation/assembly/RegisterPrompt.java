package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

/** A terminal question holds only its issuing task; the response is owner state, not a register write by a player. */
public record RegisterPrompt(boolean asks, int register) implements RegisterProgram.Instruction {
    public RegisterPrompt {
        if (register < 0) throw new IllegalArgumentException("Invalid terminal prompt operand");
    }
    @Override public int words() { return 2; }
}
