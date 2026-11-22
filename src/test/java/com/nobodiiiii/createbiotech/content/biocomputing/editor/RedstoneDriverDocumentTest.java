package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import org.junit.jupiter.api.Test;

class RedstoneDriverDocumentTest {
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(10000, 100000, 1000000);
    static final ResourceLimits HARDWARE = new ResourceLimits(256, 65536, 8);
    static ProgramDocument load(String role) throws Exception {
        try (var stream = RedstoneDriverDocumentTest.class.getClassLoader().getResourceAsStream(
                "assets/create_biotech/biocomputing/examples/redstone_output_" + role + ".bcp")) {
            assertNotNull(stream, "The editable redstone driver must be a public program, not Java orchestration");
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(stream.readAllBytes()).strip()), ARCHIVE);
        }
    }
    @Test void publishedDriversUseOnlyIsaAndFitSeparateExistingHardware() throws Exception {
        for (String role : List.of("a", "b", "c")) {
            var document = load(role);
            assertEquals(FlowProgram.Execution.REGISTER_V1, document.flow().orElseThrow().execution());
            assertEquals(document, new ProgramWorkspace(document).snapshot());
            document.validate(HARDWARE);
            assertThrows(ComputationException.class, () -> document.validate(new ResourceLimits(192, 49152, 6)),
                "Do not borrow a neighbor's code capacity");
            assertTrue(document.archive().modules().isEmpty(), "No legacy graph module fallback");
            assertTrue(document.archive().program().entries().isEmpty());
            assertTrue(document.flow().orElseThrow().canvas().detached().stream()
                .map(stack -> stack.steps().getFirst()).filter(FlowProgram.Procedure.class::isInstance)
                .map(FlowProgram.Procedure.class::cast).allMatch(procedure -> procedure.assembly().isPresent()));
            System.out.println("REDSTONE_DRIVER " + role + " " + document.flow().orElseThrow().program(document.archive()).usage(HARDWARE));
        }
    }
}
