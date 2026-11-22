package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult;
import java.util.*;
import org.junit.jupiter.api.Test;

class LibraryWriteRecoveryDriverTest {
    static final ResourceLimits HARDWARE = new ResourceLimits(192, 49152, 6);
    static ProgramDocument load() throws Exception {
        try (var stream = LibraryWriteRecoveryDriverTest.class.getClassLoader().getResourceAsStream(
                "assets/create_biotech/biocomputing/examples/library_write_recovery_driver.bcp")) {
            assertNotNull(stream, "A player needs an explicit public recovery program, not a hidden resend");
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(stream.readAllBytes()).strip()), LibraryReadDriverTest.ARCHIVE);
        }
    }
    static DataValue.RecordValue advance(ProgramDocument document, long index, String code, DataValue data, DataValue saved) {
        var machine = new ComputationMachine(document.archive().compile(HARDWARE).select("advance"));
        machine.begin(new DataValue.RecordValue(Map.of("commands", document.flow().orElseThrow().initial().field("commands"),
            "index", new DataValue.IntegerValue(index), "read_result", saved, "received", SerialResult.receive("received",
                new DataValue.RecordValue(Map.of("code", new DataValue.TextValue(code), "sequence", new DataValue.IntegerValue(17), "value", data))))));
        for (int tick = 0; machine.report().status() == ComputationMachine.Status.RUNNING; tick++) { assertTrue(tick < 300); machine.tick(); }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
        return machine.report().output().orElseThrow();
    }
    @Test void playerCanAdoptOnlyAnUnclaimedWriteResultWithoutWritingAgainAndMustAcknowledgeIt() throws Exception {
        var document = load(); document.validate(HARDWARE);
        var commands = (DataValue.ListValue)document.flow().orElseThrow().initial().field("commands");
        assertEquals(List.of("status", "result", "ack"), commands.values().stream()
            .map(v -> ((DataValue.TextValue)((DataValue.RecordValue)v).field("operation")).value()).toList());
        for (String code : List.of("busy", "result_pending")) {
            var status = advance(document, 0, code, DataValue.NullValue.INSTANCE, DataValue.NullValue.INSTANCE);
            assertEquals(new DataValue.IntegerValue(1), status.field("index"));
            assertEquals(new DataValue.IntegerValue(17), status.field("sequence"));
            assertEquals(new DataValue.BooleanValue(false), status.field("done"));
        }
        var result = new DataValue.RecordValue(Map.of("version", new DataValue.RecordValue(Map.of("revision", new DataValue.IntegerValue(5)))));
        var copied = advance(document, 1, "written", result, DataValue.NullValue.INSTANCE);
        assertEquals(result, copied.field("read_result")); assertEquals(new DataValue.BooleanValue(false), copied.field("done"));
        assertEquals(new DataValue.IntegerValue(2), copied.field("index"));
        var acknowledged = advance(document, 2, "acknowledged", DataValue.NullValue.INSTANCE, copied.field("read_result"));
        assertEquals(new DataValue.BooleanValue(true), acknowledged.field("success")); assertEquals(result, acknowledged.field("read_result"));
        for (var key : document.archive().modules().keySet()) assertTrue(key.id().startsWith("library_write_recovery."));
    }
    @Test void idleChangedOrNonWriteResultsCannotBecomeRecoveredSuccessAndBusyOnlyRepeatsResultQuery() throws Exception {
        var document = load();
        for (var failure : Map.of(0L, List.of("idle", "unavailable"), 1L, List.of("read", "stat", "stale_operation", "empty", "conflict")).entrySet())
            for (String code : failure.getValue()) {
                var result = advance(document, failure.getKey(), code, DataValue.NullValue.INSTANCE, DataValue.NullValue.INSTANCE);
                assertEquals(new DataValue.BooleanValue(true), result.field("done"));
                assertEquals(new DataValue.BooleanValue(false), result.field("success"));
                assertEquals(new DataValue.IntegerValue(failure.getKey()), result.field("index"), "No acknowledgement follows an unknown result");
                assertNotEquals(DataValue.NullValue.INSTANCE, result.field("error"));
            }
        var busy = advance(document, 1, "busy", DataValue.NullValue.INSTANCE, DataValue.NullValue.INSTANCE);
        assertEquals(new DataValue.IntegerValue(1), busy.field("index"));
        assertEquals(new DataValue.BooleanValue(false), busy.field("done"));
    }
    @Test void oldReplyIsIgnoredRatherThanClaimedByTheNewRecoveryRun() throws Exception {
        var document = load();
        var packet = (DataValue.RecordValue)document.flow().orElseThrow().initial().field("packet");
        var old = new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("library_reply"), "target", packet.field("target"),
            "request", new DataValue.TextValue("old-write:4"), "sequence", new DataValue.IntegerValue(17),
            "code", new DataValue.TextValue("written"), "value", new DataValue.RecordValue(Map.of())));
        var machine = new ComputationMachine(document.archive().compile(HARDWARE).select("match"));
        machine.begin(new DataValue.RecordValue(Map.of("packet", packet, "received", SerialResult.receive("received", old))));
        for (int tick = 0; machine.report().status() == ComputationMachine.Status.RUNNING; tick++) { assertTrue(tick < 300); machine.tick(); }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
        assertEquals(new DataValue.BooleanValue(false), machine.report().output().orElseThrow().field("matched"));
    }
}
