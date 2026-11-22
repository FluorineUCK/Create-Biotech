package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerReceptionTest {
    static final ResourceLimits HARDWARE = new ResourceLimits(1_000, 100_000, 1);
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(100_000, 1_000_000, 1_000_000);

    @Test void pausedReceiverDoesNotQueueAndReadyReceiverOwnsOneUnexecutedFrame() {
        var receiver = new ComputerRuntime(UUID.randomUUID(), receiver(), HARDWARE);
        var message = ComputerMailboxTest.message(UUID.randomUUID(), 0, 7);
        receiver.setPaused(true); var paused = receiver.snapshot();
        assertEquals(ReceptionStatus.PAUSED, receiver.receiveReady(message));
        assertEquals(paused, receiver.snapshot(), "Waiting is not buffered admission");
        receiver.setPaused(false);
        assertEquals(ReceptionStatus.RECEIVED, receiver.receiveReady(message));
        var received = receiver.snapshot();
        assertEquals(ComputationMachine.Status.RUNNING, received.machine().status());
        assertEquals(0, received.machine().workUnits());
        assertEquals(integer(0), receiver.memory().field("total"));
        assertEquals(Optional.of("message"), received.activeEntry());
        assertTrue(received.mailbox().queued().isEmpty());
        assertEquals(ReceptionStatus.RECEIVED, receiver.receiveReady(message));
        assertEquals(received, receiver.snapshot(), "A retry must not restart the receiver");
        finish(receiver);
        assertEquals(integer(7), receiver.memory().field("total"));
        assertEquals(ReceptionStatus.RECEIVED, receiver.receiveReady(message));
        receiver.tick(); assertEquals(integer(7), receiver.memory().field("total"));
    }

    @Test void durableReceptionRestoresWithoutAnAcknowledgementAndDoesNotRepeatAfterReset() {
        var receiver = new ComputerRuntime(UUID.randomUUID(), receiver(), HARDWARE);
        var message = ComputerMailboxTest.message(UUID.randomUUID(), 0, 7);
        assertEquals(ReceptionStatus.RECEIVED, receiver.receiveReady(message));
        var saved = receiver.snapshot();
        var decoded = ComputerNbtCodec.decode(ComputerNbtCodec.encode(saved, ARCHIVE), ARCHIVE);
        assertEquals(saved, decoded);
        var restored = ComputerRuntime.restore(decoded, HARDWARE); restored.load();
        assertEquals(ReceptionStatus.RECEIVED, restored.receiveReady(message));
        assertEquals(saved, restored.snapshot());
        finish(restored); assertEquals(integer(7), restored.memory().field("total"));
        restored.reset(); var reset = restored.snapshot();
        assertEquals(ReceptionStatus.RECEIVED, restored.receiveReady(message));
        assertEquals(reset, restored.snapshot(), "Reset cannot retroactively undo a completed handoff");
    }

    @Test void bufferedAdmissionAndHistoricalDedupNeverProveDirectReception() {
        var receiver = new ComputerRuntime(UUID.randomUUID(), receiver(), HARDWARE);
        var message = ComputerMailboxTest.message(UUID.randomUUID(), 0, 7);
        assertEquals(AdmissionStatus.ACCEPTED, receiver.receive(message));
        var queued = receiver.snapshot();
        assertEquals(ReceptionStatus.LEGACY_DELIVERY, receiver.receiveReady(message));
        assertEquals(queued, receiver.snapshot());
        receiver.reset();
        var restored = ComputerRuntime.restore(ComputerNbtCodec.decode(ComputerNbtCodec.encode(receiver.snapshot(), ARCHIVE), ARCHIVE), HARDWARE);
        restored.load(); var reset = restored.snapshot();
        assertEquals(ReceptionStatus.LEGACY_DELIVERY, restored.receiveReady(message));
        assertEquals(AdmissionStatus.DUPLICATE, restored.receive(message), "Old delivery semantics stay unchanged");
        assertEquals(reset, restored.snapshot());
    }

    @Test void noImplicitRootReceiverAndWaitingDoesNotStealAnActiveOrQueuedEvent() {
        var message = ComputerMailboxTest.message(UUID.randomUUID(), 0, 7);
        var rootOnly = new ComputerRuntime(UUID.randomUUID(), ComputerMailboxTest.receiver(), HARDWARE);
        var initial = rootOnly.snapshot();
        assertEquals(ReceptionStatus.UNSUPPORTED, rootOnly.receiveReady(message)); assertEquals(initial, rootOnly.snapshot());
        var receiver = new ComputerRuntime(UUID.randomUUID(), receiver(), HARDWARE);
        receiver.begin(message.payload()); var active = receiver.snapshot();
        assertEquals(ReceptionStatus.WAITING_FOR_RECEIVER, receiver.receiveReady(message)); assertEquals(active, receiver.snapshot());
        finish(receiver);
        receiver.receive(ComputerMailboxTest.message(UUID.randomUUID(), 0, 3)); var queued = receiver.snapshot();
        assertEquals(ReceptionStatus.WAITING_FOR_RECEIVER, receiver.receiveReady(message)); assertEquals(queued, receiver.snapshot());
        receiver.unload();
        assertEquals(ReceptionStatus.UNAVAILABLE, receiver.receiveReady(message)); assertEquals(queued, receiver.snapshot());
    }

    @Test void oldAndConflictingIdentitiesCannotBeAcknowledgedAsTheCurrentHandoff() {
        var receiver = new ComputerRuntime(UUID.randomUUID(), receiver(), HARDWARE);
        var first = ComputerMailboxTest.message(UUID.randomUUID(), 2, 7);
        assertEquals(ReceptionStatus.RECEIVED, receiver.receiveReady(first)); finish(receiver);
        var state = receiver.snapshot();
        assertEquals(ReceptionStatus.STALE, receiver.receiveReady(ComputerMailboxTest.message(first.source(), 1, 9)));
        assertEquals(ReceptionStatus.IDENTITY_CONFLICT, receiver.receiveReady(ComputerMailboxTest.message(first.source(), 2, 9)));
        assertEquals(state, receiver.snapshot());
    }

    @Test void namedRequestHandoffRestoresReturnContextButDoesNotCompleteBusinessProcessing() {
        var template = receiver().program();
        var requestProgram = new ProgramArchive(new ProgramDefinition(template.graph(), template.cells(), template.writes(), List.of(),
            Map.of("request", new ProgramDefinition.Entry(template.graph(), template.writes(), List.of()))), Map.of());
        var receiver = new ComputerRuntime(UUID.randomUUID(), requestProgram, HARDWARE);
        var plain = ComputerMailboxTest.message(UUID.randomUUID(), 0, 7);
        var context = new RequestContext(new RequestCorrelation(plain.source(), plain.eventId(), plain.ordinal()), "caller");
        var request = new MessageEnvelope(plain.source(), plain.sequence(), plain.eventId(), plain.ordinal(), plain.payload(), new MessageProtocol.Request(context));
        assertEquals(ReceptionStatus.RECEIVED, receiver.receiveReady(request));
        var decoded = ComputerNbtCodec.decode(ComputerNbtCodec.encode(receiver.snapshot(), ARCHIVE), ARCHIVE);
        assertEquals(Optional.of(context), decoded.rpc().request());
        assertEquals(ComputationMachine.Status.RUNNING, decoded.machine().status());
        assertEquals(0, decoded.machine().workUnits()); assertEquals(integer(0), decoded.machine().memory().field("total"));
        assertTrue(receiver.outbound().isEmpty());
    }

    static ProgramArchive receiver() {
        var old = ComputerMailboxTest.receiver().program();
        return new ProgramArchive(new ProgramDefinition(old.graph(), old.cells(), old.writes(), old.sends(),
            Map.of("message", new ProgramDefinition.Entry(old.graph(), old.writes(), old.sends()))), Map.of());
    }
    static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    static void finish(ComputerRuntime runtime) {
        for (int i = 0; runtime.report().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); runtime.tick();
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, runtime.report().status());
    }
}
