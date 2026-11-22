package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ScalarOperation;
import java.util.Objects;
import java.util.function.IntFunction;

/** Constant-size comparison of two explicit samples against the same threshold. */
public record RegisterEdge(Direction direction, int destination, int previous, int current, int threshold) {
    public enum Direction { RISING, FALLING }

    public RegisterEdge { Objects.requireNonNull(direction); }


    public void verify(IntFunction<DataType> type) {
        var numeric = type.apply(previous);
        if (type.apply(destination) != Scalar.BOOLEAN || (numeric != Scalar.INTEGER && numeric != Scalar.DECIMAL)
                || type.apply(current) != numeric || type.apply(threshold) != numeric)
            throw new IllegalArgumentException("EDGE requires BOOLEAN output and three samples/threshold of the same numeric type");
    }

    /** Two threshold comparisons, one conjunction and an explicit scratch release. No event source. */
    public java.util.List<RegisterProgram.Instruction> lower(int temp, DataType numeric) {
        var high = numeric == Scalar.INTEGER ? ScalarOperation.GREATER_INTEGER : ScalarOperation.GREATER_DECIMAL;
        var low = numeric == Scalar.INTEGER ? ScalarOperation.LESS_EQUAL_INTEGER : ScalarOperation.LESS_EQUAL_DECIMAL;
        boolean rising = direction == Direction.RISING;
        return java.util.List.of(
            new RegisterProgram.Alu(rising ? low : high, destination, java.util.List.of(previous, threshold)),
            new RegisterProgram.Alu(rising ? high : low, temp, java.util.List.of(current, threshold)),
            new RegisterProgram.Alu(ScalarOperation.AND_BOOLEAN, destination, java.util.List.of(destination, temp)),
            new RegisterProgram.Drop(temp));
    }
}
