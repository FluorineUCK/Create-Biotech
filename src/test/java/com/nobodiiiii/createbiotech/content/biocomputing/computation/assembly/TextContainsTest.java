package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TextContainsTest {
    @Test void aRestoredSearchCannotClaimAMatchedPrefixThatDiffersFromItsOperands() {
        var text = DataType.Scalar.TEXT;
        var bool = DataType.Scalar.BOOLEAN;
        var source = "REG result BOOLEAN\nTEXT.CONTAINS.CI result text needle\nRET";
        var inputs = Map.<String, DataType>of("text", text, "needle", text);
        var outputs = Map.<String, DataType>of("result", bool);
        var returns = Map.of("result", "result");
        assertThrows(IllegalArgumentException.class,
            () -> ProcedureAssembler.assemble(new AssemblySource(14, source), inputs, outputs, returns, 4096, 64));
        var image = ProcedureAssembler.assemble(new AssemblySource(source), inputs, outputs, returns, 4096, 64)
            .program().forTask("local", 1);
        var op = assertInstanceOf(RegisterProgram.TextContains.class, image.code().getFirst().instruction());
        assertEquals(4, op.words());
        assertEquals(InstructionFamily.ISA_1A, op.family());
        var start = image.begin(new DataValue.RecordValue(Map.of(
            "text", new DataValue.TextValue("😀"), "needle", new DataValue.TextValue("😎"))));
        var forged = new RegisterState(start.image(), start.pc(), start.registers(), true, Optional.empty(), start.calls(),
            start.transaction(), true, Optional.of(RegisterConstruction.savedTextContains(0, 2, 2, 1)));
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,
            assertThrows(ComputationException.class, () -> image.validate(forged, 2, false)).code());
    }
}
