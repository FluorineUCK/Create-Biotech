package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LibraryQueryRecoveryDriverTest {
    @Test void wrongKindOrIdleCannotBeClaimedAndBusyNeverRepeatsTheOriginalOperation() throws Exception {
        var document = load();
        for (var cases : Map.of(0L, List.of("idle", "unavailable"), 1L, List.of("written", "read", "stat", "empty", "stale_operation", "changed")).entrySet())
            for (String code : cases.getValue()) {
                var result = LibraryWriteRecoveryDriverTest.advance(document, cases.getKey(), code, DataValue.NullValue.INSTANCE, DataValue.NullValue.INSTANCE);
                assertEquals(new DataValue.BooleanValue(true), result.field("done")); assertEquals(new DataValue.BooleanValue(false), result.field("success"));
                assertEquals(new DataValue.IntegerValue(cases.getKey()), result.field("index"), "Rejected result is not acknowledged");
                assertEquals(DataValue.NullValue.INSTANCE, result.field("read_result"));
            }
        for (int index : List.of(0, 1)) {
            var result = LibraryWriteRecoveryDriverTest.advance(document, index, "busy", DataValue.NullValue.INSTANCE, DataValue.NullValue.INSTANCE);
            assertEquals(new DataValue.BooleanValue(false), result.field("done")); assertEquals(new DataValue.IntegerValue(1), result.field("index"));
        }
        assertThrows(ComputationException.class, () -> document.validate(new ResourceLimits(32, 32768, 4)));
    }
    static ProgramDocument load() throws Exception {
        try (var input = LibraryQueryRecoveryDriverTest.class.getClassLoader().getResourceAsStream(
            "assets/create_biotech/biocomputing/examples/library_query_recovery_driver.bcp")) {
            assertNotNull(input, "A player must be able to claim a retained query without re-querying or hidden retransmission");
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(input.readAllBytes()).strip()), LibraryReadDriverTest.ARCHIVE);
        }
    }
    @Test void explicitlyClaimsOnlyTheChosenQueryKindAndAcknowledgesWithoutSeekingOrReadingAgain() throws Exception {
        var original = load(); original.validate(LibraryWriteRecoveryDriverTest.HARDWARE);
        var body = original.flow().orElseThrow();
        var commands = ((DataValue.ListValue)body.initial().field("commands")).values();
        assertEquals(List.of("status", "result", "ack"), commands.stream()
            .map(v -> ((DataValue.TextValue)((DataValue.RecordValue)v).field("operation")).value()).toList());
        assertEquals(new DataValue.TextValue("list"), ((DataValue.RecordValue)commands.get(1)).field("expected"));
        for (String kind : List.of("read", "stat", "list")) {
            var changed = new ArrayList<>(commands);
            var command = new LinkedHashMap<>(((DataValue.RecordValue)changed.get(1)).fields()); command.put("expected", new DataValue.TextValue(kind));
            changed.set(1, new DataValue.RecordValue(command));
            var initial = new LinkedHashMap<>(body.initial().fields()); initial.put("commands", new DataValue.ListValue(changed));
            var document = new ProgramDocument(original.archive(), original.positions(), original.terminal(), Optional.of(
                new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(initial))));
            var status = LibraryWriteRecoveryDriverTest.advance(document, 0, "result_pending", DataValue.NullValue.INSTANCE, DataValue.NullValue.INSTANCE);
            assertEquals(new DataValue.IntegerValue(17), status.field("sequence"));
            var data = new DataValue.RecordValue(Map.of("metadata", new DataValue.TextValue(kind)));
            var copied = LibraryWriteRecoveryDriverTest.advance(document, 1, kind, data, DataValue.NullValue.INSTANCE);
            assertEquals(data, copied.field("read_result")); assertEquals(new DataValue.BooleanValue(false), copied.field("done"));
            var acknowledged = LibraryWriteRecoveryDriverTest.advance(document, 2, "acknowledged", DataValue.NullValue.INSTANCE, data);
            assertEquals(data, acknowledged.field("read_result")); assertEquals(new DataValue.BooleanValue(true), acknowledged.field("success"));
            var rejected = LibraryWriteRecoveryDriverTest.advance(document, 1, "written", data, DataValue.NullValue.INSTANCE);
            assertEquals(new DataValue.BooleanValue(true), rejected.field("done")); assertEquals(new DataValue.BooleanValue(false), rejected.field("success"));
            assertEquals(new DataValue.IntegerValue(1), rejected.field("index"));
        }
        for (var key : original.archive().modules().keySet()) {
            assertTrue(key.id().startsWith("library_query_recovery."));
            assertNotNull(new ProgramWorkspace(original).exportSubgraph(key, LibraryReadDriverTest.ARCHIVE));
        }
    }
}
