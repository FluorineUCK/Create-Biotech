package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointTransport;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import org.junit.jupiter.api.Test;

class CommunicationStatusViewTest {
    static final ComputerHostLimits LIMITS = new ComputerHostLimits(new ResourceLimits(1000, 1_000_000, 100),
        new ArchiveLimits(100_000, 1_000_000, 10_000_000), new MailboxLimits(3, 5));
    static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());

    @Test void emptyOwnerHasExplicitAbsenceAndConfiguredCapacity() {
        var data = view(Optional.empty(), Optional.empty());
        assertEquals(new DataValue.BooleanValue(false), data.field("program_installed"));
        assertEquals(DataValue.NullValue.INSTANCE, data.field("phase"));
        assertEquals(DataValue.NullValue.INSTANCE, data.field("target_valid"));
        assertEquals(integer(0), data.field("inbox_count"));
        assertEquals(integer(3), data.field("inbox_limit"));
        assertEquals(integer(5), data.field("source_limit"));
    }

    @Test void observationCountsCompleteQueueAndReceiptStorageWithoutMutatingIt() {
        var graph = new ComputationGraph(Map.of("n", DataType.Scalar.INTEGER),
            Map.of("input", new ComputationGraph.Input("n")), Map.of("n", "input"));
        var computer = runtime(new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of()));
        var payload = new DataValue.RecordValue(Map.of("n", integer(7)));
        computer.receive(new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, payload));
        var before = computer.snapshot();
        var data = view(Optional.of(before), Optional.empty());
        assertEquals(integer(1), data.field("inbox_count"));
        assertEquals(integer(1), data.field("tracked_sources"));
        assertEquals(integer(before.mailbox().storageUnits()), data.field("mailbox_units"));
        assertEquals(before, computer.snapshot());
        computer.tick();
        assertEquals(integer(0), view(Optional.of(computer.snapshot()), Optional.empty()).field("inbox_count"));
        assertEquals(integer(1), view(Optional.of(computer.snapshot()), Optional.empty()).field("tracked_sources"));
    }

    @Test void onlyCommittedValidTargetIsShownAndLargePayloadNeverConsumesDiagnosticWire() {
        var computer = runtime(sender("Target", new DataValue.TextValue("secret".repeat(10_000))));
        computer.begin(EMPTY);
        assertEquals(integer(1), view(Optional.of(computer.snapshot()), Optional.empty()).field("outbox_remaining"));
        assertEquals(DataValue.NullValue.INSTANCE, view(Optional.of(computer.snapshot()), Optional.empty()).field("next_endpoint"));
        while (computer.report().status() == ComputationMachine.Status.RUNNING) computer.tick();
        var envelope = CommunicationStatusView.of(Optional.of(computer.snapshot()), LIMITS, true, Optional.empty(),
            Optional.of(new EndpointTransport.Result("queue_full", Optional.of(AdmissionStatus.QUEUE_FULL))));
        var data = (DataValue.RecordValue) envelope.field("communication");
        assertEquals(new DataValue.TextValue("Target"), data.field("next_endpoint"));
        assertEquals(new DataValue.BooleanValue(true), data.field("target_valid"));
        assertEquals(new DataValue.TextValue("queue_full"), ((DataValue.RecordValue)data.field("last_transport")).field("code"));
        var answer = new ComputerControlSession.Answer("ok", 1, "", Optional.of(computer.snapshot()), Optional.empty(), Optional.of(envelope));
        var response = ProgramStatusResponse.fromAnswer(1, UUID.randomUUID(), 1, answer, LIMITS.archive());
        assertEquals("ok", response.code());
        assertTrue(response.status().length < 4_096);
        assertEquals(envelope, ProgramEditorWire.decodeValue(response.status(), LIMITS.archive()));
        assertFalse(envelope.toString().contains("secret"));
        assertFalse(data.fields().containsKey("memory"));
        assertFalse(data.fields().containsKey("queued"));
        var outgoing = computer.outbound().orElseThrow(); computer.acknowledge(outgoing.eventId(), outgoing.ordinal());
        var drained = view(Optional.of(computer.snapshot()), Optional.empty());
        assertEquals(integer(0), drained.field("outbox_remaining"));
        assertEquals(DataValue.NullValue.INSTANCE, drained.field("next_endpoint"));
    }

    @Test void invalidDestinationIsNotClippedIntoADifferentValidName() {
        var computer = runtime(sender("x".repeat(50_000), integer(1)));
        computer.begin(EMPTY); while (computer.report().status() == ComputationMachine.Status.RUNNING) computer.tick();
        var data = view(Optional.of(computer.snapshot()), Optional.empty());
        assertEquals(new DataValue.BooleanValue(false), data.field("target_valid"));
        assertEquals(DataValue.NullValue.INSTANCE, data.field("next_endpoint"));
        assertTrue(ProgramEditorWire.encodeValue(data, LIMITS.archive()).length < 4_096);
    }

    private static DataValue.RecordValue view(Optional<ComputerRuntime.Snapshot> snapshot, Optional<EndpointTransport.Result> last) {
        return (DataValue.RecordValue)CommunicationStatusView.of(snapshot, LIMITS, snapshot.isPresent(), Optional.empty(), last).field("communication");
    }
    private static DataValue.IntegerValue integer(long n) { return new DataValue.IntegerValue(n); }
    private static ComputerRuntime runtime(ProgramArchive program) { return new ComputerRuntime(UUID.randomUUID(), program, LIMITS.hardware(), LIMITS.mailbox()); }
    static ProgramArchive sender(String endpoint, DataValue value) {
        var graph = new ComputationGraph(Map.of(), Map.of("target", new ComputationGraph.Constant(new DataValue.TextValue(endpoint)),
            "payload", new ComputationGraph.Constant(new DataValue.RecordValue(Map.of("value", value)))), Map.of("target", "target", "message", "payload"));
        return new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of(), List.of(new ProgramDefinition.Send("target", "message"))), Map.of());
    }
}
