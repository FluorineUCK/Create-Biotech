package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalKey;

/** Read one bit from the authorized local terminal's current hardware sample. */
public record RegisterKeyDown(int destination, String selector) implements RegisterProgram.Instruction {
    public RegisterKeyDown { selector = TerminalKey.selector(selector); }
    @Override public int words() { return 3; }
}
