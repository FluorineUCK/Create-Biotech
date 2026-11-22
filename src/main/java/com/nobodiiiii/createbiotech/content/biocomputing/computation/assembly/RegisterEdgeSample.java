package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import java.util.ArrayList;
import java.util.List;

/** Compares against a register in the current frame, then copies the current sample into history.
 * An unwritten history register arms the comparison without producing an edge. */
public record RegisterEdgeSample(RegisterEdge comparison) {
    public RegisterEdgeSample {
        java.util.Objects.requireNonNull(comparison);
        if (comparison.previous() == comparison.current() || comparison.previous() == comparison.threshold())
            throw new IllegalArgumentException("EDGE.SAMPLE history must be distinct from its operands");
    }
    public List<RegisterProgram.Instruction> lower(int temp, DataType numeric) {
        var steps = new ArrayList<RegisterProgram.Instruction>();
        steps.add(new RegisterProgram.DefaultNumber(comparison.previous(), comparison.current()));
        steps.addAll(comparison.lower(temp, numeric));
        steps.add(new RegisterProgram.Move(comparison.previous(), comparison.current()));
        return List.copyOf(steps);
    }
}
