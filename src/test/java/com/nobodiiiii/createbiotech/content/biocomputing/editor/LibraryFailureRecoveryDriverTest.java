package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LibraryFailureRecoveryDriverTest {
    static ProgramDocument load() throws Exception {
        try (var stream = LibraryFailureRecoveryDriverTest.class.getClassLoader().getResourceAsStream(
            "assets/create_biotech/biocomputing/examples/library_failure_recovery_driver.bcp")) {
            assertNotNull(stream, "A player needs an explicit editable failure receipt program, not an automatic error ACK");
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(stream.readAllBytes()).strip()), LibraryReadDriverTest.ARCHIVE);
        }
    }
    @Test void explicitlyClaimsTheChosenFailureAndKeepsItsCodeAfterAcknowledgementWithoutRepeatingWork() throws Exception {
        var document = load(); document.validate(new ResourceLimits(192, 49152, 6));
        var commands = ((DataValue.ListValue)document.flow().orElseThrow().initial().field("commands")).values();
        assertEquals(List.of("status", "result", "ack"), commands.stream()
            .map(v -> ((DataValue.TextValue)((DataValue.RecordValue)v).field("operation")).value()).toList());
        assertEquals(new DataValue.TextValue("denied"), ((DataValue.RecordValue)commands.get(1)).field("expected"));
        var status = LibraryWriteRecoveryDriverTest.advance(document, 0, "result_pending", DataValue.NullValue.INSTANCE, DataValue.NullValue.INSTANCE);
        assertEquals(new DataValue.IntegerValue(17), status.field("sequence"));
        assertEquals(new DataValue.IntegerValue(1), status.field("index"));
        var claimed = LibraryWriteRecoveryDriverTest.advance(document, 1, "denied", DataValue.NullValue.INSTANCE, DataValue.NullValue.INSTANCE);
        var receipt = new DataValue.RecordValue(Map.of("code", new DataValue.TextValue("denied"),
            "sequence", new DataValue.IntegerValue(17), "value", DataValue.NullValue.INSTANCE));
        assertEquals(receipt, claimed.field("read_result"), "Copy the failure receipt, not its usually empty data field");
        assertEquals(new DataValue.IntegerValue(2), claimed.field("index"));
        assertEquals(new DataValue.BooleanValue(false), claimed.field("success"), "Receiving is not acknowledgement");
        var acknowledged = LibraryWriteRecoveryDriverTest.advance(document, 2, "acknowledged", DataValue.NullValue.INSTANCE, receipt);
        assertEquals(receipt, acknowledged.field("read_result"), "The original failure survives ACK");
        assertEquals(new DataValue.BooleanValue(true), acknowledged.field("success"), "Only recovery/ACK succeeded, never the original write");
        assertEquals(new DataValue.BooleanValue(true), acknowledged.field("done"));
    }
    @Test void unfinishedIdleOrDifferentResultsStopWithoutAdvancingToAcknowledgement() throws Exception {
        var document = load();
        for (var cases : Map.of(0L, List.of("busy", "idle", "unavailable"),
                1L, List.of("busy", "written", "read", "stat", "list", "conflict", "stale_operation", "empty"),
                2L, List.of("idle", "stale_operation", "empty")).entrySet())
            for (String code : cases.getValue()) {
                var result = LibraryWriteRecoveryDriverTest.advance(document, cases.getKey(), code, DataValue.NullValue.INSTANCE, DataValue.NullValue.INSTANCE);
                assertEquals(new DataValue.BooleanValue(true), result.field("done"), code);
                assertEquals(new DataValue.BooleanValue(false), result.field("success"), code);
                assertEquals(new DataValue.IntegerValue(cases.getKey()), result.field("index"), "Never adopt a pending operation or silently ACK a different result");
                assertNotEquals(DataValue.NullValue.INSTANCE, result.field("error"));
            }
        assertThrows(ComputationException.class, () -> document.validate(new ResourceLimits(32, 32768, 4)), "Other members cannot subsidize this driver's program capacity");
    }
    @Test void playerCatalogExposesAnEditableTerminalThatDoesNotLabelTheFailedWriteAsSuccessful() throws Exception {
        var catalog = ProgramExampleCatalog.load(id -> getClass().getClassLoader().getResourceAsStream("assets/" + id.getNamespace() + "/" + id.getPath()));
        assertTrue(catalog.stream().anyMatch(e -> e.id().equals("library_failure_recovery_driver")), "Players need a document-page entry");
        var document = load();
        var terminal = document.terminal().orElseThrow(); assertTrue(terminal.readOnly());
        var receipt = new DataValue.RecordValue(Map.of("code", new DataValue.TextValue("denied"), "sequence", new DataValue.IntegerValue(17), "value", DataValue.NullValue.INSTANCE));
        var output = LibraryWriteRecoveryDriverTest.advance(document, 2, "acknowledged", DataValue.NullValue.INSTANCE, receipt);
        var roots = new DataValue.RecordValue(Map.of("output", output));
        for (var pair : Map.of("acknowledged", new DataValue.BooleanValue(true), "receipt_code", new DataValue.TextValue("denied"),
                "receipt_sequence", new DataValue.IntegerValue(17), "receipt", receipt, "handling_error", DataValue.NullValue.INSTANCE).entrySet()) {
            var node = (com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition.Text)terminal.nodes().get(pair.getKey());
            assertEquals(pair.getValue(), node.value().resolve(roots), pair.getKey());
        }
        for (var key : document.archive().modules().keySet()) {
            assertTrue(key.id().startsWith("library_failure_recovery."));
            assertNotNull(new ProgramWorkspace(document).exportSubgraph(key, LibraryReadDriverTest.ARCHIVE));
        }
    }
    @Test void expectedFailureRemainsAnEditableCommandValueRatherThanAHiddenDeniedOnlyOperation() throws Exception {
        var workspace = new ProgramWorkspace(load());
        var initial = workspace.snapshot().flow().orElseThrow().initial();
        var commands = new ArrayList<>(((DataValue.ListValue)initial.field("commands")).values());
        var resultCommand = new LinkedHashMap<>(((DataValue.RecordValue)commands.get(1)).fields());
        resultCommand.put("expected", new DataValue.TextValue("readonly"));
        commands.set(1, new DataValue.RecordValue(resultCommand));
        var fields = new LinkedHashMap<>(initial.fields());
        fields.put("commands", new DataValue.ListValue(commands));
        var body = workspace.snapshot().flow().orElseThrow();
        workspace.setFlow(Optional.of(new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(fields))));
        var document = workspace.snapshot();
        var claimed = LibraryWriteRecoveryDriverTest.advance(document, 1, "readonly", DataValue.NullValue.INSTANCE, DataValue.NullValue.INSTANCE);
        assertEquals(new DataValue.IntegerValue(2), claimed.field("index"));
        assertEquals(new DataValue.TextValue("readonly"), ((DataValue.RecordValue)claimed.field("read_result")).field("code"));
        var other = LibraryWriteRecoveryDriverTest.advance(document, 1, "denied", DataValue.NullValue.INSTANCE, DataValue.NullValue.INSTANCE);
        assertEquals(new DataValue.BooleanValue(true), other.field("done"));
        assertEquals(new DataValue.BooleanValue(false), other.field("success"));
        assertEquals(new DataValue.IntegerValue(1), other.field("index"));
    }
    @Test void matchingFailureCodeDoesNotAllowAnOldReplyToBecomeTheCurrentReceipt() throws Exception {
        var document = load();
        var packet = (DataValue.RecordValue)document.flow().orElseThrow().initial().field("packet");
        var old = new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("library_reply"), "target", packet.field("target"),
            "request", new DataValue.TextValue("old-recovery:4"), "sequence", new DataValue.IntegerValue(17),
            "code", new DataValue.TextValue("denied"), "value", DataValue.NullValue.INSTANCE));
        var machine = new ComputationMachine(document.archive().compile(LibraryWriteRecoveryDriverTest.HARDWARE).select("match"));
        machine.begin(new DataValue.RecordValue(Map.of("packet", packet, "received",
            com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.receive("received", old))));
        for (int tick = 0; machine.report().status() == ComputationMachine.Status.RUNNING; tick++) { assertTrue(tick < 300); machine.tick(); }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
        assertEquals(new DataValue.BooleanValue(false), machine.report().output().orElseThrow().field("matched"));
    }
}
