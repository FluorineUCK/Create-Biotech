package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
class TextReporterAdmissionTest {
    @Test void sourceTextReportersInstallThroughThePublicAssembler() {
        var letter=ProcedureAssembler.assemble(new AssemblySource("REG result TEXT\nTEXT.LETTER result text index\nRET"),
            Map.of("text",DataType.Scalar.TEXT,"index",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.TEXT),Map.of("result","result"),4096,64);
        assertEquals(InstructionFamily.ISA_1A,letter.program().code().getFirst().instruction().family());
        var contains=ProcedureAssembler.assemble(new AssemblySource("REG result BOOLEAN\nTEXT.CONTAINS.CI result text needle\nRET"),
            Map.of("text",DataType.Scalar.TEXT,"needle",DataType.Scalar.TEXT),Map.of("result",DataType.Scalar.BOOLEAN),Map.of("result","result"),4096,64);
        assertEquals(InstructionFamily.ISA_1A,contains.program().code().getFirst().instruction().family());
    }
}
