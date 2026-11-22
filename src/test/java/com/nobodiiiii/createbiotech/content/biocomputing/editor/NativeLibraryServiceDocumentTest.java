package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeLibraryServiceDocumentTest {
    @ParameterizedTest @ValueSource(strings = {"b", "c", "d"})
    void publicServicesPreserveEditableSourceAndFitTheirIndependentHardware(String role) throws Exception {
        var expected = NativeLibraryServiceDocuments.build(role);
        var limits = NativeLibraryOwnersTest.limits("cleric", 5);
        var resource = Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/library_service_" + role + ".bcp");
        var twin = Path.of("examples/biocomputing/library_service_" + role + ".bcp");
        if ("1".equals(System.getenv("GENERATE_LIBRARY_SERVICES"))) {
            String encoded = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(expected, limits.archive())) + "\n";
            Files.writeString(resource, encoded); Files.writeString(twin, encoded);
        }
        assertTrue(Files.isRegularFile(resource), "The service must be a public document, not only a test helper");
        assertEquals(Files.readString(resource), Files.readString(twin));
        var published = ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(resource).strip()), limits.archive());
        assertEquals(expected, published);
        assertEquals(published, new ProgramWorkspace(published).snapshot());
        published.validate(limits.hardware());
        var body = published.flow().orElseThrow();
        assertFalse(body.canvas().detached().isEmpty(), "The service implementation remains accessible in Scratch");
        assertEquals(FlowProcedureTools.definitions(expected.flow().orElseThrow()), FlowProcedureTools.definitions(body));
        assertEquals(published, ProgramDocumentWire.decode(ProgramDocumentWire.encode(published, limits.archive()), limits.archive()));
        var owner = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
        var prepared = com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(published, limits);
        owner.installProgram(prepared, com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime.MemoryPolicy.RESET, limits, true);
        var installed = owner.flowSnapshot().orElseThrow();
        assertTrue(installed.state().paused(), "Import/install must not start the service");
        assertTrue(installed.tasks().contexts().isEmpty());
        assertEquals(Map.of("b", 256, "c", 191, "d", 255).get(role).intValue(), body.program(published.archive()).usage(limits.hardware()).programUnits());
    }
}
