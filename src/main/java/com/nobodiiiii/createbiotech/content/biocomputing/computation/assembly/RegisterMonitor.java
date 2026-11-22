package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

/** One bounded change to this Computer's presentation; no register or cell value is copied. */
public record RegisterMonitor(String cell, boolean visible, boolean privateLocal) implements RegisterProgram.Instruction {
    public RegisterMonitor(String cell, boolean visible) { this(cell, visible, false); }
    public RegisterMonitor {
        if (cell == null || cell.isBlank()) throw new IllegalArgumentException("Monitor requires a named cell");
    }
    @Override public int words() { return 2; }
}
