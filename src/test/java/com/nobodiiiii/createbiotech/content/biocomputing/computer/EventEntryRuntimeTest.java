package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.EventEntryFixtures.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.CommunicationStatusView;
import java.util.*;
import org.junit.jupiter.api.Test;

class EventEntryRuntimeTest {
    @Test void manualMessageAndTimerHaveDifferentInputsButOneMemory() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), program(false), HARDWARE);
        runtime.begin(input("manual", 1)); finish(runtime);
        assertEquals(integer(1), runtime.memory().field("n"));
        assertEquals(AdmissionStatus.ACCEPTED, runtime.receive(message(7)));
        assertEquals(integer(1), runtime.memory().field("n"));
        runtime.tick(); finish(runtime);
        assertEquals(integer(7), runtime.memory().field("n"));
        assertEquals(new DataValue.TextValue("message"),
            com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerStatusView.of(runtime.snapshot()).field("entry"));
        runtime.configurePeriodic(1, EMPTY); runtime.tick(); finish(runtime);
        assertEquals(integer(8), runtime.memory().field("n"));
        assertEquals(integer(42), runtime.memory().field("untouched"));
        runtime.disablePeriodic(); runtime.begin(input("manual", 11)); finish(runtime);
        assertEquals(integer(11), runtime.memory().field("n"));
        assertEquals(DataValue.NullValue.INSTANCE,
            com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerStatusView.of(runtime.snapshot()).field("entry"));
    }

    @Test void messageAdmissionUsesHandlerSchemaAndRemainsInertWhilePausedOrUnloaded() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), program(false), HARDWARE);
        runtime.setPaused(true);
        var wrong = new com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope(
            UUID.randomUUID(), 0, UUID.randomUUID(), 0, input("manual", 7));
        assertEquals(AdmissionStatus.TYPE_MISMATCH, runtime.receive(wrong));
        assertTrue(runtime.snapshot().mailbox().receipts().isEmpty());
        assertEquals(AdmissionStatus.ACCEPTED, runtime.receive(message(7)));
        var queued = runtime.snapshot(); runtime.tick(); assertEquals(queued, runtime.snapshot());
        assertThrows(ComputationException.class, () -> runtime.begin(input("manual", 4)));
        runtime.unload(); runtime.tick(); runtime.step(); assertEquals(queued, runtime.snapshot());
        runtime.load(); runtime.step(); assertEquals(integer(0), runtime.memory().field("n"));
        finish(runtime); assertTrue(runtime.paused()); assertEquals(integer(7), runtime.memory().field("n"));
    }

    @Test void committedSendCursorAndStatusUseOnlyTheActiveEntry() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), program(true), HARDWARE);
        runtime.begin(input("manual", 1)); finish(runtime);
        var root = runtime.outbound().orElseThrow(); assertEquals("root_peer", root.endpoint());
        assertEquals(AdmissionStatus.ACCEPTED, runtime.receive(message(7)));
        runtime.tick(); assertEquals(root, runtime.outbound().orElseThrow());
        assertEquals(1, runtime.snapshot().mailbox().queued().size());
        assertTrue(runtime.acknowledge(root.eventId(), root.ordinal()));
        runtime.tick(); finish(runtime);
        var first = runtime.outbound().orElseThrow();
        assertEquals("message_peer_a", first.endpoint()); assertEquals(1, first.sequence());
        var status = (DataValue.RecordValue)CommunicationStatusView.of(Optional.of(runtime.snapshot()), LIMITS,
            true, Optional.empty(), Optional.empty()).field("communication");
        assertEquals(integer(2), status.field("outbox_remaining"));
        assertEquals(new DataValue.TextValue("message_peer_a"), status.field("next_endpoint"));
        assertTrue(runtime.acknowledge(first.eventId(), first.ordinal()));
        var second = runtime.outbound().orElseThrow();
        assertEquals("message_peer_b", second.endpoint()); assertEquals(2, second.sequence());
        assertTrue(runtime.acknowledge(second.eventId(), second.ordinal())); assertTrue(runtime.outbound().isEmpty());
    }

    @Test void rejectedBusyBeginRetainsHandlerContinuationAndResetReturnsToRoot() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), program(false), HARDWARE);
        assertEquals(AdmissionStatus.ACCEPTED, runtime.receive(message(7))); runtime.step();
        var running = runtime.snapshot();
        assertThrows(ComputationException.class, () -> runtime.begin(input("manual", 9)));
        assertEquals(running, runtime.snapshot());
        finish(runtime); runtime.reset();
        assertEquals(integer(0), runtime.memory().field("n"));
        runtime.begin(input("manual", 3)); finish(runtime);
        assertEquals(integer(3), runtime.memory().field("n"));
    }

    @Test void installationWaitsForHandlerSendsAndThenPreservesSharedMemory() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), program(true), HARDWARE);
        assertEquals(AdmissionStatus.ACCEPTED, runtime.receive(message(7))); runtime.tick(); finish(runtime);
        assertEquals(ComputerRuntime.InstallResult.QUEUED,
            runtime.requestInstall(program(false), ComputerRuntime.MemoryPolicy.PRESERVE));
        runtime.tick(); assertEquals("message_peer_a", runtime.outbound().orElseThrow().endpoint());
        for (int i = 0; i < 2; i++) { var send = runtime.outbound().orElseThrow(); assertTrue(runtime.acknowledge(send.eventId(), send.ordinal())); }
        runtime.tick(); assertEquals(ComputationMachine.Status.IDLE, runtime.report().status());
        assertEquals(integer(7), runtime.memory().field("n")); assertTrue(runtime.snapshot().pending().isEmpty());
        runtime.configurePeriodic(1, EMPTY); runtime.tick(); finish(runtime);
        assertEquals(integer(8), runtime.memory().field("n"));
    }
}
