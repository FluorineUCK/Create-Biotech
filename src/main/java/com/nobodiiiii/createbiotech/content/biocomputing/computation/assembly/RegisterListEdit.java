package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import java.util.function.IntFunction;

/** A typed positional list copy; all bounds are zero-based, and a slice end is exclusive. */
public record RegisterListEdit(ListOperation operation, int to, List<Integer> operands)
        implements RegisterProgram.Instruction {
    public RegisterListEdit {
        Objects.requireNonNull(operation);
        operands = List.copyOf(operands);
        if (operation == ListOperation.APPEND || operands.size() != operation.inputs().size())
            throw new IllegalArgumentException("Invalid indexed list instruction operands");
    }

    @Override public int words() { return 2 + operands.size(); }

    void verify(IntFunction<DataType> register) {
        if (!(register.apply(to) instanceof DataType.ListType target)
            || !(register.apply(operands.getFirst()) instanceof DataType.ListType source)
            || !RegisterAssignment.permits(target.element(), source.element())
            || register.apply(operands.get(1)) != DataType.Scalar.INTEGER
            || operation == ListOperation.SLICE && register.apply(operands.get(2)) != DataType.Scalar.INTEGER
            || operation.inputs().containsKey("value") && !RegisterAssignment.permits(target.element(), register.apply(operands.get(2))))
            throw new IllegalArgumentException("LIST." + operation.name() + " list/index/element type");
    }

    ListOperation.Selection project(RegisterState state, String location) {
        var r = state.registers();
        return operation.project((DataValue.ListValue)r.get(operands.getFirst()),
            ((DataValue.IntegerValue)r.get(operands.get(1))).value(),
            operation == ListOperation.SLICE ? ((DataValue.IntegerValue)r.get(operands.get(2))).value() : 0,
            operation.inputs().containsKey("value") ? r.get(operands.get(2)) : null, location);
    }
}
