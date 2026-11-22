package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

/** Fixed-size access to the owning Computer's stopwatch, never a host wall-clock query. */
public record RegisterTimer(int destination) implements RegisterProgram.Instruction {
    public static final int TICKS_PER_SECOND = 20;
    public static final RegisterTimer RESET = new RegisterTimer(-1);

    public RegisterTimer {
        if (destination < -1) throw new IllegalArgumentException("Invalid timer destination");
    }

    public boolean resets() { return destination == -1; }
    @Override public int words() { return resets() ? 1 : 2; }
}
