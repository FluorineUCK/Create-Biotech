package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult;
import java.util.*;
import org.junit.jupiter.api.Test;

class LibraryListDriverTest {
    @Test void packagedDriverUsesAnExplicitEditablePageAndKeepsTheDirectoryBeforeAcknowledging() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("assets/create_biotech/biocomputing/examples/library_list_driver.bcp")) {
            assertNotNull(input, "Directory traversal needs a public driver, not a hidden Library search");
            var document = ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(input.readAllBytes()).strip()), LibraryReadDriverTest.ARCHIVE);
            var limits = new ResourceLimits(192, 49152, 6); document.validate(limits);
            var initial = document.flow().orElseThrow().initial();
            var arguments = (DataValue.RecordValue)initial.field("arguments");
            assertEquals(new DataValue.RecordValue(Map.of("after", DataValue.NullValue.INSTANCE, "count", new DataValue.IntegerValue(4))), arguments.field("page"));
            var commands = (DataValue.ListValue)initial.field("commands"); assertEquals(10, commands.values().size());
            assertEquals(new DataValue.TextValue("list"), ((DataValue.RecordValue)commands.values().get(7)).field("operation"));
            assertEquals(new DataValue.TextValue("page"), ((DataValue.RecordValue)commands.values().get(7)).field("argument"));
            assertEquals(new DataValue.TextValue("list"), ((DataValue.RecordValue)commands.values().get(8)).field("expected"));
            var page = new DataValue.RecordValue(Map.of("volume", new DataValue.TextValue("日志"), "entries", new DataValue.ListValue(List.of()),
                "next", new DataValue.TextValue("last_name"), "version", DataValue.NullValue.INSTANCE));
            var machine = new ComputationMachine(document.archive().compile(limits).select("advance"));
            DataValue readResult = DataValue.NullValue.INSTANCE;
            for (int index : List.of(8, 9)) {
                var response = new DataValue.RecordValue(Map.of("code", new DataValue.TextValue(index == 8 ? "list" : "acknowledged"),
                    "sequence", new DataValue.IntegerValue(3), "value", index == 8 ? page : DataValue.NullValue.INSTANCE));
                machine.begin(new DataValue.RecordValue(Map.of("commands", commands, "index", new DataValue.IntegerValue(index),
                    "read_result", readResult, "received", SerialResult.receive("received", response))));
                for (int n = 0; machine.report().status() == ComputationMachine.Status.RUNNING; n++) { assertTrue(n < 300); machine.tick(); }
                assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
                var result = machine.report().output().orElseThrow(); readResult = result.field("read_result"); assertEquals(page, readResult);
                assertEquals(new DataValue.BooleanValue(index == 9), result.field("done"), "Even with next set, one page completes; no hidden traversal");
            }
            for (var module : document.archive().modules().keySet()) {
                assertTrue(module.id().startsWith("library_list."));
                assertNotNull(new ProgramWorkspace(document).exportSubgraph(module, LibraryReadDriverTest.ARCHIVE));
            }
            assertThrows(ComputationException.class, () -> document.validate(new ResourceLimits(32, 32768, 4)));
        }
    }
}
