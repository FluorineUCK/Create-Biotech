package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.NativeLibraryOwnersTest;
import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeClusterSerialDocumentTest {
    @org.junit.jupiter.api.BeforeAll static void author() throws Exception {
        if (!"1".equals(System.getenv("GENERATE_CLUSTER_SERIAL"))) return;
        for (String role : List.of("caller", "gateway", "worker")) {
            var document = NativeClusterSerialDocuments.build(role);
            var limits = NativeLibraryOwnersTest.limits("cleric", 5);
            document.validate(limits.hardware());
            System.out.println(role + " code=" + document.flow().orElseThrow().program(document.archive()).usage(limits.hardware()).programUnits());
            String encoded = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document, limits.archive())) + "\n";
            for (String root : List.of("src/main/resources/assets/create_biotech/biocomputing/examples/", "examples/biocomputing/"))
                java.nio.file.Files.writeString(java.nio.file.Path.of(root + "cluster_serial_" + role + ".bcp"), encoded);
        }
    }
    static InputStream resource(net.minecraft.resources.ResourceLocation id) {
        return NativeClusterSerialDocumentTest.class.getResourceAsStream("/assets/" + id.getNamespace() + "/" + id.getPath());
    }

    @Test void playersCanImportEditableSerialClusterRolesWithinIndependentResidentLimits() {
        var catalog = ProgramExampleCatalog.load(NativeClusterSerialDocumentTest::resource);
        for (String role : List.of("caller", "gateway", "worker")) {
            var example = catalog.stream().filter(e -> e.id().equals("cluster_serial_" + role)).findFirst();
            assertTrue(example.isPresent(), "Missing public ISA example: " + role);
            var limits = NativeLibraryOwnersTest.limits("cleric", 5);
            var document = ProgramExampleCatalog.preview(example.orElseThrow(), NativeClusterSerialDocumentTest::resource, limits.archive());
            var body = document.flow().orElseThrow();
            assertEquals(NativeClusterSerialDocuments.build(role), document);
            assertEquals(Map.of("caller", 241L, "gateway", 254L, "worker", 176L).get(role).longValue(), body.program(document.archive()).usage(limits.hardware()).programUnits());
            assertEquals("REGISTER_V1", body.execution().name());
            assertFalse(body.canvas().detached().isEmpty(), "Implementation must remain editable in Scratch");
            assertEquals(document, new ProgramWorkspace(document).snapshot());
            assertEquals(document, ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, limits.archive()), limits.archive()));
            var owner = new ComputerBlockHost(() -> {});
            owner.installProgram(PreparedProgram.prepare(document, limits), ComputerRuntime.MemoryPolicy.RESET, limits, true);
            assertTrue(owner.flowSnapshot().orElseThrow().state().paused(), "Preview and installation never start communication");
            assertTrue(owner.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
        }
    }

    @Test void lowerTierCannotBorrowClusterCapacityOrReplaceItsExistingProgram() {
        for (String role : List.of("caller", "gateway", "worker")) {
            var lower = NativeLibraryOwnersTest.limits("cleric", role.equals("worker") ? 3 : 4);
            var owner = new ComputerBlockHost(() -> {});
            var empty = new ProgramWorkspace();
            empty.setFlow(Optional.of(FlowDocumentBody.fromProgram(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(), List.of()).withAssembly(), new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.RecordValue(Map.of()))));
            owner.installProgram(PreparedProgram.prepare(empty.snapshot(), lower), ComputerRuntime.MemoryPolicy.RESET, lower, true);
            var before = owner.write();
            assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.class, () ->
                owner.installProgram(PreparedProgram.prepare(NativeClusterSerialDocuments.build(role), lower), ComputerRuntime.MemoryPolicy.RESET, lower, true));
            assertEquals(before, owner.write());
        }
    }
}
