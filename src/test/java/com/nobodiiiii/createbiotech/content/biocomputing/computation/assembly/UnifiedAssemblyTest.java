package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.nio.file.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedAssemblyTest {
    @Test void onlyCurrentSourceHeaderIsAdmitted() {
        assertEquals(1, new AssemblySource("RET").version());
        assertFalse(AssemblySource.supported(23));
        for (int v = 0; v <= AssemblySource.VERSION + 1; v++) {
            final int version = v;
            if (v != AssemblySource.VERSION)
                assertThrows(IllegalArgumentException.class, () -> new AssemblySource(version, "RET"));
        }
    }

    @Test void everyShippedExampleReadsAndAssemblesWithCurrentRules() throws Exception {
        var limits = new ArchiveLimits(50000, 500000, 2000000);
        // The build also packages examples from docs which do not have a resource-tree twin.
        for (var path : java.util.List.of(Path.of("src/main/resources/assets/create_biotech/biocomputing/examples"),
                Path.of("examples/biocomputing"))) try (var files = Files.walk(path)) {
            var docs = files.filter(p -> p.toString().endsWith(".bcp")).toList();
            assertFalse(docs.isEmpty());
            for (var p : docs) assertDoesNotThrow(() -> {
                var doc = ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(p).strip()), limits);
                if (doc.flow().isEmpty()) return;
                for (var step : doc.flow().get().steps()) {
                    if (step instanceof FlowProgram.Procedure proc && proc.assembly().isPresent())
                        ProcedureAssembler.assemble(proc.assembly().get(), proc.inputs(), proc.outputs(),
                            proc.returns(), 512*1024, Integer.MAX_VALUE, doc.archive().program().cells());
                }
            }, p.toString());
        }
    }
}
