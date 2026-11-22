package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LibraryStatDriverTest {
    @Test void packagedStatDriverIsPublicEditableAndPreservesAbsenceResultsBeforeAcknowledgement() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("assets/create_biotech/biocomputing/examples/library_stat_driver.bcp")) {
            assertNotNull(input, "Creation versions must be obtainable with a public driver, not the old instant service");
            var document = ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(input.readAllBytes()).strip()), LibraryReadDriverTest.ARCHIVE);
            document.validate(new ResourceLimits(192, 49152, 6));
            var initial = document.flow().orElseThrow().initial();
            var commands = (DataValue.ListValue)initial.field("commands");
            assertEquals(new DataValue.TextValue("stat"), ((DataValue.RecordValue)commands.values().get(7)).field("operation"));
            assertEquals(new DataValue.TextValue("stat"), ((DataValue.RecordValue)commands.values().get(8)).field("expected"));
            var data = new DataValue.RecordValue(Map.of("present", new DataValue.BooleanValue(false), "value", DataValue.NullValue.INSTANCE,
                "kind", DataValue.NullValue.INSTANCE, "version", new DataValue.RecordValue(Map.of("revision", new DataValue.IntegerValue(0)))));
            var response = new DataValue.RecordValue(Map.of("code", new DataValue.TextValue("stat"), "sequence", new DataValue.IntegerValue(3), "value", data));
            var machine = new ComputationMachine(document.archive().compile(new ResourceLimits(192, 49152, 6)).select("advance"));
            machine.begin(new DataValue.RecordValue(Map.of("commands", commands, "index", new DataValue.IntegerValue(8),
                "read_result", DataValue.NullValue.INSTANCE, "received", com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.receive("received", response))));
            for (int n = 0; machine.report().status() == ComputationMachine.Status.RUNNING; n++) { assertTrue(n < 300); machine.tick(); }
            assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
            assertEquals(data, machine.report().output().orElseThrow().field("read_result"));
            assertEquals(new DataValue.BooleanValue(false), machine.report().output().orElseThrow().field("done"), "Still must acknowledge the device");
            for (var module : document.archive().modules().keySet()) {
                assertTrue(module.id().startsWith("library_stat."), "Do not mutate a fixed version of the read driver's modules");
                assertNotNull(new ProgramWorkspace(document).exportSubgraph(module, LibraryReadDriverTest.ARCHIVE));
            }
            assertThrows(ComputationException.class, () -> document.validate(new ResourceLimits(32, 32768, 4)));
        }
    }
}
