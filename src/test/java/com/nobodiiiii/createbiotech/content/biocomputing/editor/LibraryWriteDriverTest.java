package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LibraryWriteDriverTest {
    @Test void packagedWriteDriverUsesTheQueriedVersionAndReadsBackBeforeFinishing() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("assets/create_biotech/biocomputing/examples/library_write_driver.bcp")) {
            assertNotNull(input, "Players need an editable write driver, not a privileged Java sequence");
            var document = ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(input.readAllBytes()).strip()), LibraryReadDriverTest.ARCHIVE);
            document.validate(new ResourceLimits(256, 65536, 8));
            var initial = document.flow().orElseThrow().initial();
            var commands = (DataValue.ListValue)initial.field("commands");
            assertEquals(new DataValue.TextValue("stat"), ((DataValue.RecordValue)commands.values().get(7)).field("operation"));
            assertEquals(new DataValue.TextValue("write"), ((DataValue.RecordValue)commands.values().get(10)).field("operation"));
            assertEquals(new DataValue.TextValue("read"), ((DataValue.RecordValue)commands.values().get(13)).field("operation"));
            var version = new DataValue.RecordValue(Map.of("medium", new DataValue.TextValue(UUID.randomUUID().toString()),
                "entry", new DataValue.TextValue("reading"), "revision", new DataValue.IntegerValue(17)));
            var values = new LinkedHashMap<>(initial.fields()); values.put("index", new DataValue.IntegerValue(10));
            values.put("read_result", new DataValue.RecordValue(Map.of("version", version)));
            values.put("request", new DataValue.TextValue("test:3"));
            var compiled = document.archive().compile(new ResourceLimits(256, 65536, 8)).select("prepare");
            var inputs = new LinkedHashMap<String, DataValue>(); document.archive().program().entry("prepare").graph().inputs().keySet().forEach(name -> inputs.put(name, values.get(name)));
            var machine = new ComputationMachine(compiled); machine.begin(new DataValue.RecordValue(inputs));
            for (int n = 0; machine.report().status() == ComputationMachine.Status.RUNNING; n++) { assertTrue(n < 300); machine.tick(); }
            assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
            var packet = (DataValue.RecordValue)machine.report().output().orElseThrow().field("packet");
            assertEquals(version, ((DataValue.RecordValue)packet.field("data")).field("expected"));
            for (var module : document.archive().modules().keySet()) assertTrue(module.id().startsWith("library_write."));
        }
    }
}
