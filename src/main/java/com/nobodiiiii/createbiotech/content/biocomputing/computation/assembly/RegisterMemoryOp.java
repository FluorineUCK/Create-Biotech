package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

/** ISA core memory operations. Legacy commit pays one preparation instruction per declared cell;
 * PUBLIC_LOAD/STORE/ADD access the current bank directly and never open a transaction. */
public record RegisterMemoryOp(Kind kind, int cell, int register) implements RegisterProgram.Instruction {
    public enum Kind { BEGIN, READ, WRITE, PREPARE, PREPARE_CELL, COMMIT, ROLLBACK, PUBLIC_ADD, PUBLIC_LOAD, PUBLIC_STORE }
    public RegisterMemoryOp {
        java.util.Objects.requireNonNull(kind);
        boolean indexed = kind == Kind.READ || kind == Kind.WRITE || kind == Kind.PREPARE_CELL || kind == Kind.PUBLIC_ADD || kind == Kind.PUBLIC_LOAD || kind == Kind.PUBLIC_STORE;
        boolean operand = kind == Kind.READ || kind == Kind.WRITE || kind == Kind.COMMIT || kind == Kind.PUBLIC_ADD || kind == Kind.PUBLIC_LOAD || kind == Kind.PUBLIC_STORE;
        if ((indexed ? cell < 0 : cell != -1) || (operand ? register < 0 : register != -1))
            throw new IllegalArgumentException("Invalid memory instruction operands");
    }
    public int words() { return 1 + (cell >= 0 ? 1 : 0) + (register >= 0 ? 1 : 0); }
    public boolean publicAccess() { return kind == Kind.PUBLIC_LOAD || kind == Kind.PUBLIC_STORE || kind == Kind.PUBLIC_ADD; }
    public int output() { return kind == Kind.READ || kind == Kind.COMMIT || kind == Kind.PUBLIC_LOAD ? register : -1; }
    public int input() { return kind == Kind.WRITE || kind == Kind.PUBLIC_ADD || kind == Kind.PUBLIC_STORE ? register : -1; }
}
