package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Exact, readable exports of every editable procedure, including the generic B transport. */
class ProductionAssemblyExportTest {
    @Test void exportsMatchCompletePublishedProcedures() throws Exception {
        var names = new LinkedHashMap<String, String>();
        names.put("production_submit_a", "production-submit-a.asm");
        names.put("production_poll_a", "production-poll-a.asm");
        names.put("redstone_output_b", "production-transport-b.asm");
        names.put("production_c", "production-matcher-c.asm");
        for (var entry : names.entrySet()) {
            ProgramDocument document;
            try (var stream = getClass().getResourceAsStream("/assets/create_biotech/biocomputing/examples/" + entry.getKey() + ".bcp")) {
                assertNotNull(stream);
                document = ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(stream.readAllBytes()).strip()), RedstoneDriverDocumentTest.ARCHIVE);
            }
            var text = new StringBuilder("# Complete editable procedures from " + entry.getKey() + ".bcp\n"
                + "# request is a per-transport correlation; job is the stable production order identity.\n"
                + "# Procedure inputs/outputs and invocation wiring remain in the accompanying .bcp.\n");
            int count = 0;
            for (var step : document.flow().orElseThrow().program(document.archive()).steps()) {
                if (step instanceof FlowProgram.Procedure procedure) {
                    text.append("\n# Procedure ").append(procedure.sourceId()).append("\n");
                    text.append(procedure.assembly().orElseThrow().text());
                    if (text.charAt(text.length() - 1) != '\n') text.append('\n');
                    count++;
                }
            }
            assertTrue(count > 0);
            var path = Path.of("examples/biocomputing", entry.getValue());
            if ("1".equals(System.getenv("GENERATE_PRODUCTION_ASSEMBLY"))) Files.writeString(path, text);
            assertEquals(text.toString(), Files.readString(path), path.toString());
        }
    }
}
