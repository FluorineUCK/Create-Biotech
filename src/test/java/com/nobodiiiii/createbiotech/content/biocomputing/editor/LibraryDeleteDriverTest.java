package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult;
import java.util.*;
import org.junit.jupiter.api.Test;

class LibraryDeleteDriverTest {
    static final ResourceLimits HARDWARE = new ResourceLimits(256, 65536, 8);
    static ProgramDocument load() throws Exception {
        try (var input = LibraryDeleteDriverTest.class.getClassLoader().getResourceAsStream(
                "assets/create_biotech/biocomputing/examples/library_delete_driver.bcp")) {
            assertNotNull(input, "Players need an editable slow delete driver, not an immediate privileged delete");
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(input.readAllBytes()).strip()), LibraryReadDriverTest.ARCHIVE);
        }
    }
    static DataValue.RecordValue evaluate(ProgramDocument document, String entry, Map<String, DataValue> values) {
        var input = new LinkedHashMap<String, DataValue>();
        document.archive().program().entry(entry).graph().inputs().keySet().forEach(name -> input.put(name, values.get(name)));
        var machine = new ComputationMachine(document.archive().compile(HARDWARE).select(entry));
        machine.begin(new DataValue.RecordValue(input));
        for (int tick = 0; machine.report().status() == ComputationMachine.Status.RUNNING; tick++) {
            assertTrue(tick < 300); machine.tick();
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status(), () -> machine.report().diagnostic().toString());
        return machine.report().output().orElseThrow();
    }
    @Test void publishedDeleteUsesTheQueriedVersionAndRechecksThePhysicalEntryAfterAcknowledgement() throws Exception {
        var document = load(); document.validate(HARDWARE);
        var initial = document.flow().orElseThrow().initial();
        var commands = (DataValue.ListValue)initial.field("commands");
        assertEquals(List.of("status", "seek", "result", "ack", "select", "result", "ack", "stat", "result", "ack",
            "write", "result", "ack", "stat", "result", "ack"), commands.values().stream()
            .map(c -> ((DataValue.TextValue)((DataValue.RecordValue)c).field("operation")).value()).toList());
        var version = new LibraryMutation.Version(UUID.randomUUID(), "reading", 17);
        var values = new LinkedHashMap<>(initial.fields()); values.put("index", new DataValue.IntegerValue(10));
        values.put("read_result", new DataValue.RecordValue(Map.of("version", version.value())));
        var packet = (DataValue.RecordValue)evaluate(document, "prepare", values).field("packet");
        var mutation = LibraryMutation.decode((DataValue.RecordValue)packet.field("data"));
        assertEquals(version, mutation.expected()); assertEquals("日志", mutation.volume());
        assertEquals("reading", mutation.entry()); assertTrue(mutation.replacement().isEmpty(), "Not a null replacement value");
        for (var key : document.archive().modules().keySet()) assertTrue(key.id().startsWith("library_delete."));
        assertEquals(document, new ProgramWorkspace(document).snapshot());
        assertThrows(ComputationException.class, () -> document.validate(new ResourceLimits(32, 32768, 4)),
            "The driver cannot borrow another Cluster member's program capacity");
    }
    static DataValue.RecordValue advance(ProgramDocument document, int index, String code, DataValue saved) {
        var values = new LinkedHashMap<>(document.flow().orElseThrow().initial().fields());
        values.put("index", new DataValue.IntegerValue(index)); values.put("read_result", saved);
        values.put("received", SerialResult.receive("received", new DataValue.RecordValue(Map.of(
            "code", new DataValue.TextValue(code), "sequence", new DataValue.IntegerValue(5), "value", DataValue.NullValue.INSTANCE))));
        return evaluate(document, "advance", values);
    }
    @Test void successRequiresAnAbsentPostDeleteSnapshotNotMerelySuccessfulTransportOrAcknowledgement() throws Exception {
        var document = load();
        for (boolean present : List.of(false, true)) {
            var snapshot = new DataValue.RecordValue(Map.of("present", new DataValue.BooleanValue(present)));
            var done = advance(document, 15, "acknowledged", snapshot);
            assertEquals(new DataValue.BooleanValue(true), done.field("done"));
            assertEquals(new DataValue.BooleanValue(!present), done.field("success"));
            assertEquals(snapshot, done.field("read_result"));
            if (present) assertEquals(new DataValue.TextValue("entry_still_present"), ((DataValue.RecordValue)done.field("error")).field("code"));
            else assertEquals(DataValue.NullValue.INSTANCE, done.field("error"));
        }
    }
    @Test void rejectionStopsWithoutAcknowledgingAndBusyRepeatsOnlyTheResultQuery() throws Exception {
        var document = load();
        for (String code : List.of("readonly", "denied", "missing_medium", "missing_entry", "stale_version", "conflict")) {
            var rejected = advance(document, 10, code, DataValue.NullValue.INSTANCE);
            assertEquals(new DataValue.BooleanValue(true), rejected.field("done"));
            assertEquals(new DataValue.BooleanValue(false), rejected.field("success"));
            assertEquals(new DataValue.IntegerValue(10), rejected.field("index"));
            assertEquals(new DataValue.TextValue(code), ((DataValue.RecordValue)rejected.field("error")).field("code"));
        }
        var busy = advance(document, 11, "busy", DataValue.NullValue.INSTANCE);
        assertEquals(new DataValue.BooleanValue(false), busy.field("done"));
        assertEquals(new DataValue.IntegerValue(11), busy.field("index"));
        var packet = (DataValue.RecordValue)document.flow().orElseThrow().initial().field("packet");
        var old = new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("library_reply"), "target", packet.field("target"),
            "request", new DataValue.TextValue("previous-delete:17"), "code", new DataValue.TextValue("written"),
            "sequence", new DataValue.IntegerValue(17), "value", DataValue.NullValue.INSTANCE));
        assertEquals(new DataValue.BooleanValue(false), evaluate(document, "match", Map.of("packet", packet,
            "received", SerialResult.receive("received", old))).field("matched"));
    }
}
