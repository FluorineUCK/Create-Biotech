package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The complete application is editable resources on independent, unchanged hardware. */
public class ReaderOutputDocumentTest {
    public static ProgramDocument load(String role) throws Exception {
        String file = role.equals("b") ? "redstone_output_b" : "reader_output_" + role;
        try (var stream = ReaderOutputDocumentTest.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/" + file + ".bcp")) {
            assertNotNull(stream, "Publish each application stage as an editable document: " + role);
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(stream.readAllBytes()).strip()), ReaderDriverDocumentTest.ARCHIVE);
        }
    }
    @Test void publicStagesFitIndependentExistingHardwareAndDoNotInstallHiddenExecutors() throws Exception {
        for (String role : List.of("a", "b", "c", "d", "e")) {
            var document = load(role); document.validate(ReaderDriverDocumentTest.HARDWARE);
            assertEquals(FlowProgram.Execution.REGISTER_V1, document.flow().orElseThrow().execution());
            assertEquals(document, new ProgramWorkspace(document).snapshot());
            assertTrue(document.archive().modules().isEmpty()); assertTrue(document.archive().program().entries().isEmpty());
            assertTrue(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowXBus
                .image(document.flow().orElseThrow().program(document.archive())).instructionFamilies()
                .contains(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.InstructionFamily.ISA_1A),
                "Each stage currently uses structured processing; do not exempt the transport example");
        }
    }
}
