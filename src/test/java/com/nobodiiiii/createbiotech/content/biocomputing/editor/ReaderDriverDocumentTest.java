package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ReaderDriverDocumentTest {
    public static final ArchiveLimits ARCHIVE = new ArchiveLimits(10000, 100000, 1000000);
    public static final ResourceLimits HARDWARE = new ResourceLimits(256, 65536, 8);
    public static ProgramDocument load(String role) throws Exception {
        String id = role.equals("b") ? "redstone_output_b" : "reader_sample_" + role;
        try (var stream = ReaderDriverDocumentTest.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/" + id + ".bcp")) {
            assertNotNull(stream, "Sampling must be a public editable program, not a hidden reader opcode");
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(stream.readAllBytes()).strip()), ARCHIVE);
        }
    }
    @Test void samplingDocumentsUseOnlyIsaAndExistingIndependentHardware() throws Exception {
        for (String role : List.of("a", "b", "c")) {
            var document = load(role); document.validate(HARDWARE);
            assertEquals(FlowProgram.Execution.REGISTER_V1, document.flow().orElseThrow().execution());
            assertEquals(document, new ProgramWorkspace(document).snapshot());
            assertTrue(document.archive().modules().isEmpty()); assertTrue(document.archive().program().entries().isEmpty());
            System.out.println("READER_DRIVER " + role + " " + document.flow().orElseThrow().program(document.archive()).usage(HARDWARE));
        }
    }
}
