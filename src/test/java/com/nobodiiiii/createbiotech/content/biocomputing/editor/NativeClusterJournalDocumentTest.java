package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.NativeLibraryOwnersTest;
import org.junit.jupiter.api.Test;

class NativeClusterJournalDocumentTest {
    @org.junit.jupiter.api.BeforeAll static void author() throws Exception {
        if (!"1".equals(System.getenv("GENERATE_CLUSTER_JOURNAL"))) return;
        for (String role : java.util.List.of("worker", "preparer", "coordinator", "adapter")) {
            var document = NativeClusterJournalDocuments.build(role);
            var limits = NativeLibraryOwnersTest.limits("cleric", 5);
            document.validate(limits.hardware());
            String encoded = java.util.Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, limits.archive())) + "\n";
            for (String root : java.util.List.of("src/main/resources/assets/create_biotech/biocomputing/examples/", "examples/biocomputing/"))
                java.nio.file.Files.writeString(java.nio.file.Path.of(root + "cluster_journal_" + role + ".bcp"), encoded);
        }
    }
    @Test void authoredImagesFitUnchangedResidentCapacity() {
        for (String role : java.util.List.of("worker", "preparer", "coordinator", "adapter")) {
            var document = NativeClusterJournalDocuments.build(role);
            var limits = NativeLibraryOwnersTest.limits("cleric", 5);
            var probe = new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(10000, limits.hardware().memoryUnits(), limits.hardware().workPerSlice());
            long words = document.flow().orElseThrow().program(document.archive()).usage(probe).programUnits();
            System.out.println(role + " code=" + words);
            assertTrue(words <= limits.hardware().programUnits(), role + " exceeds its own resident capacity: " + words);
            document.validate(limits.hardware());
        }
    }
    @Test void publicJournalRolesAreEditableAndFitIndependentTwoPinHardware() {
        var catalog = ProgramExampleCatalog.load(NativeClusterSerialDocumentTest::resource);
        for (String role : java.util.List.of("worker", "preparer", "coordinator", "adapter")) {
            var entry = catalog.stream().filter(e -> e.id().equals("cluster_journal_" + role)).findFirst();
            assertTrue(entry.isPresent(), "Missing public journal role: " + role);
            var limits = NativeLibraryOwnersTest.limits("cleric", 5);
            var document = ProgramExampleCatalog.preview(entry.orElseThrow(), NativeClusterSerialDocumentTest::resource, limits.archive());
            assertEquals(NativeClusterJournalDocuments.build(role), document);
            document.validate(limits.hardware());
            assertEquals("REGISTER_V1", document.flow().orElseThrow().execution().name());
            assertEquals(document, new ProgramWorkspace(document).snapshot());
            assertEquals(document, ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, limits.archive()), limits.archive()));
            assertFalse(document.flow().orElseThrow().canvas().detached().isEmpty());
            assertEquals(java.util.Map.of("worker", 196L, "preparer", 207L, "coordinator", 255L, "adapter", 255L).get(role).longValue(),
                document.flow().orElseThrow().program(document.archive()).usage(limits.hardware()).programUnits());
            var owner = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
            owner.installProgram(com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(document, limits),
                com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime.MemoryPolicy.RESET, limits, true);
            assertTrue(owner.flowSnapshot().orElseThrow().state().paused());
            assertTrue(owner.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
            var lower = NativeLibraryOwnersTest.limits("cleric", 4);
            assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.class, () ->
                com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(document, lower));
        }
    }
}
