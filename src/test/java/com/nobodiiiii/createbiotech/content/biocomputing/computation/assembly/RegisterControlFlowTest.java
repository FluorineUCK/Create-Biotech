package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RegisterControlFlowTest {
    @Test void backEdgeCannotJustifyReadingAnUninitializedRegisterOnTheFirstIteration() {
        assertThrows(IllegalArgumentException.class, () -> image(List.of(
            new RegisterProgram.Move(1, 0), new RegisterProgram.Constant(0, 0), new RegisterProgram.Jump(0)),
            List.of(DataType.Scalar.INTEGER, DataType.Scalar.INTEGER), List.of(new DataValue.IntegerValue(7))));
    }

    @Test void laterIterationRegistersNeedEnoughCyclesToHaveReturnedFromTheirWriter() {
        var program = image(List.of(new RegisterProgram.Constant(0, 0), new RegisterProgram.JumpFalse(0, 4),
            new RegisterProgram.Constant(1, 1), new RegisterProgram.Jump(1)),
            List.of(DataType.Scalar.BOOLEAN, DataType.Scalar.INTEGER), List.of(new DataValue.BooleanValue(true), new DataValue.IntegerValue(7)));
        var state = program.begin(new DataValue.RecordValue(Map.of()));
        for (int i = 0; i < 4; i++) state = program.step(state);
        assertEquals(1, state.pc()); var warm = state;
        assertDoesNotThrow(() -> program.validate(warm, 4, false));
        assertDoesNotThrow(() -> program.validate(warm, 400, false));
        assertThrows(ComputationException.class, () -> program.validate(warm, 1, false));
        assertThrows(ComputationException.class, () -> program.validate(warm, 3, false));
        var coldPrefix = program.begin(new DataValue.RecordValue(Map.of()));
        assertThrows(ComputationException.class, () -> program.validate(coldPrefix, 1, false), "A loop does not relax the acyclic prefix");
    }

    @Test void aSelfLoopIsMeteredButItsUnreachableSuffixIsNotAValidContinuation() {
        var program = image(List.of(new RegisterProgram.Jump(0), new RegisterProgram.Jump(2)), List.of(), List.of());
        var state = program.begin(new DataValue.RecordValue(Map.of()));
        assertEquals(state, program.step(state));
        assertDoesNotThrow(() -> program.validate(state, 17, false));
        for (int pc : List.of(1, 2)) assertThrows(ComputationException.class,
            () -> program.validate(new RegisterState(state.image(), pc, Map.of()), 17, false));
        assertThrows(IllegalArgumentException.class, () -> image(List.of(new RegisterProgram.Jump(-1)), List.of(), List.of()));
    }

    private static RegisterProgram image(List<RegisterProgram.Instruction> instructions, List<DataType> types, List<DataValue> constants) {
        var code = new ArrayList<RegisterProgram.Code>();
        for (int pc = 0; pc < instructions.size(); pc++) code.add(new RegisterProgram.Code(instructions.get(pc), pc, "source/" + pc));
        return new RegisterProgram(code, types, constants, Map.of(), Map.of(), instructions.size());
    }
}
