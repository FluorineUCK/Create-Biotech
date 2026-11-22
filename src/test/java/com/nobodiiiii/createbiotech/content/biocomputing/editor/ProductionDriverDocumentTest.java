package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionDriverDocumentTest {
    @Test void publishedProductionDocumentsAreEditableIsaWithinExisting256UnitHardware() throws Exception {
        for (String name : List.of("production_submit_a", "production_poll_a", "production_c", "redstone_output_b")) {
            try (var stream = getClass().getResourceAsStream("/assets/create_biotech/biocomputing/examples/" + name + ".bcp")) {
                assertNotNull(stream, name + " must be an ordinary public editable document");
                var document = ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(stream.readAllBytes()).strip()),
                    RedstoneDriverDocumentTest.ARCHIVE);
                document.validate(new ResourceLimits(256, 65536, 8));
                assertEquals(FlowProgram.Execution.REGISTER_V1, document.flow().orElseThrow().execution());
                assertEquals(document, new ProgramWorkspace(document).snapshot());
                assertTrue(document.archive().modules().isEmpty());
                assertTrue(document.archive().program().entries().isEmpty());
                System.out.println("PRODUCTION_DRIVER " + name + " " + document.flow().orElseThrow()
                    .program(document.archive()).usage(RedstoneDriverDocumentTest.HARDWARE));
            }
        }
    }
}
