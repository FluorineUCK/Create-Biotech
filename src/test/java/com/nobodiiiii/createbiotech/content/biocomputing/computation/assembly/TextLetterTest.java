package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TextLetterTest {
    @Test void authoredLetterRequiresItsVersionAndCannotRestorePastTheRequestedPoint() {
        var types = Map.<String, DataType>of("text", DataType.Scalar.TEXT, "index", DataType.Scalar.INTEGER);
        var output = Map.<String, DataType>of("result", DataType.Scalar.TEXT);
        var returns = Map.of("result", "result");
        var source = "REG result TEXT\nTEXT.LETTER result text index\nRET";
        assertThrows(IllegalArgumentException.class,
            () -> ProcedureAssembler.assemble(new AssemblySource(13, source), types, output, returns, 4096, 64));
        var image = ProcedureAssembler.assemble(new AssemblySource(source), types, output, returns, 4096, 64)
            .program().forTask("local", 1);
        var op = assertInstanceOf(RegisterProgram.TextLetter.class, image.code().getFirst().instruction());
        assertEquals(4, op.words());
        assertEquals(InstructionFamily.ISA_1A, op.family());
        var start = image.begin(new DataValue.RecordValue(Map.of("text", new DataValue.TextValue("😀B"),
            "index", new DataValue.IntegerValue(1))));
        var forged = new RegisterState(start.image(), start.pc(), start.registers(), true,
            Optional.of(new RegisterState.Scan(2, 1)), start.calls(), start.transaction());
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,
            assertThrows(ComputationException.class, () -> image.validate(forged, 1, false)).code());
    }
}
