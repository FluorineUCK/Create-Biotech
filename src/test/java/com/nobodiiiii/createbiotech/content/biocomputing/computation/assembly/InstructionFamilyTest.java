package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class InstructionFamilyTest {
    @Test void structuredInstructionsRequireIsa1aEvenWhenTheActualValuesWouldBeSmall() {
        List<RegisterProgram.Instruction> operations = List.of(
            new RegisterProgram.ValueEquals(2, 0, 1), new RegisterProgram.ValueCheck(1, 0),
            new RegisterProgram.Field(1, 0, "value"), new RegisterProgram.HasField(2, 0, 1),
            new RegisterProgram.Index(1, 0, 0), new RegisterProgram.DynamicIndex(2, 0, 1),
            new RegisterProgram.ListLength(1, 0), new RegisterProgram.MakeList(1, List.of(0)),
            new RegisterProgram.MakeRecord(1, List.of(new RegisterProgram.NamedOperand("value", 0))),
            new RegisterProgram.TextLength(1, 0));
        for (var operation : operations) assertEquals(InstructionFamily.ISA_1A, operation.family(), operation.toString());
    }

    @Test void numericEqualityAndTagChecksRemainBaseInstructions() {
        for (var op : List.of(ScalarOperation.EQUAL_INTEGER, ScalarOperation.EQUAL_DECIMAL,
                ScalarOperation.ADD_INTEGER, ScalarOperation.IS_NULL, ScalarOperation.IS_ERROR,
                ScalarOperation.REQUIRE_VALUE)) {
            assertEquals(InstructionFamily.ISA_1, new RegisterProgram.Alu(op, 2, List.of(0, 1)).family());
        }
        assertEquals(InstructionFamily.ISA_1, new RegisterProgram.Move(1, 0).family());
        assertEquals(InstructionFamily.ISA_1, new RegisterProgram.Sleep(0).family());
        assertEquals(InstructionFamily.ISA_1, new RegisterMemoryOp(RegisterMemoryOp.Kind.PUBLIC_LOAD, 0, 0).family());
        assertEquals(InstructionFamily.ISA_1, new RegisterProgram.Call(0).family());
        assertEquals(InstructionFamily.ISA_1, new RegisterProgram.TaskClaim(new ModuleDefinition.Key("job", 1), 0, 1).family());
    }

    @Test void deviceExtensionsAreNotReclassifiedAsStructuredProcessing() {
        assertEquals(InstructionFamily.XBUS, new RegisterXBus.Instruction(RegisterXBus.Kind.READ, "x0", -1, 0).family());
        assertEquals(InstructionFamily.XBUS, new RegisterDevices.Instruction(RegisterDevices.Kind.XBUS_STATUS, 0, "x0").family());
        assertEquals(InstructionFamily.SIMPLE_IO, new RegisterDevices.Instruction(RegisterDevices.Kind.SIO_READ, 0, "p0").family());
        assertEquals(InstructionFamily.SERIAL, new RegisterSerial.Instruction(RegisterSerial.Kind.SEND, "s0", 0, 1, false).family());
        assertEquals(InstructionFamily.SERIAL, new RegisterSerial.Instruction(RegisterSerial.Kind.RECEIVE, "s0", -1, 0, false, true).family());
    }

    @Test void requirementsComeFromTheWholeVerifiedImageAndDoNotChangeExecutionOrCosts() {
        var source = new AssemblySource("""
            REG equal BOOLEAN
            REG numeric BOOLEAN
            EQUAL_INTEGER numeric left right
            VALUE.EQUALS equal left right
            """);
        var image = ProcedureAssembler.assemble(source, Map.of("left", DataType.Scalar.INTEGER, "right", DataType.Scalar.INTEGER),
            Map.of("result", DataType.Scalar.BOOLEAN), Map.of("result", "equal"), 4096, 128).program();
        var values = new DataValue.RecordValue(Map.of("left", new DataValue.IntegerValue(1), "right", new DataValue.IntegerValue(1)));
        var taskImage = image.forTask("local", 1);
        var state = taskImage.begin(values);
        String identity = state.image(); long words = image.codeWords();
        assertEquals(Set.of(InstructionFamily.ISA_1, InstructionFamily.ISA_1A), image.instructionFamilies());
        assertThrows(UnsupportedOperationException.class, () -> image.instructionFamilies().clear());
        assertEquals(identity, taskImage.begin(values).image());
        assertEquals(words, image.codeWords());
        assertEquals(12, words);
        assertEquals(image.instructionFamilies(), image.forTask("local", 1).instructionFamilies());
    }
}
