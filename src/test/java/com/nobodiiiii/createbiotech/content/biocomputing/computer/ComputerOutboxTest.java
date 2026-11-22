package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerOutboxTest {
    @Test void onlySuccessfulCommitExposesNamedValuesAndOwnMemoryTogether() {
        var computer = computer(sender(2));
        assertTrue(computer.outbound().isEmpty());
        computer.begin(EMPTY);
        while (computer.report().status() == ComputationMachine.Status.RUNNING) {
            assertTrue(computer.outbound().isEmpty());
            assertEquals(integer(0), computer.memory().field("count"));
            computer.step();
        }
        var message = computer.outbound().orElseThrow();
        assertEquals("receiver", message.endpoint());
        assertEquals(integer(1), message.payload().field("count"));
        assertEquals(integer(1), computer.memory().field("count"));
        assertEquals(0, message.ordinal());
        assertThrows(UnsupportedOperationException.class, () -> message.payload().fields().put("count", integer(8)));
    }

    @Test void outboxIdentityAndAcknowledgedPrefixSurviveEverySaveBoundary() {
        var computer = computer(sender(2)); computer.begin(EMPTY);
        var event = computer.snapshot().delivery().orElseThrow().eventId();
        for (int i = 0; computer.report().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100);
            computer = reload(computer);
            assertEquals(event, computer.snapshot().delivery().orElseThrow().eventId());
            computer.load(); computer.step();
        }
        var first = computer.outbound().orElseThrow();
        assertEquals(event, first.eventId());
        assertFalse(computer.acknowledge(UUID.randomUUID(), 0));
        assertFalse(computer.acknowledge(event, 1));
        assertTrue(computer.acknowledge(event, 0));
        assertFalse(computer.acknowledge(event, 0));
        computer = reload(computer); computer.load();
        var second = computer.outbound().orElseThrow();
        assertEquals(event, second.eventId()); assertEquals(1, second.ordinal());
        assertEquals(first.payload(), second.payload());
        assertTrue(computer.acknowledge(event, 1));
        computer = reload(computer); computer.load();
        assertTrue(computer.outbound().isEmpty());
        computer.tick(); computer.step(); assertTrue(computer.outbound().isEmpty());
        computer.begin(EMPTY); finish(computer);
        assertNotEquals(event, computer.outbound().orElseThrow().eventId());
        assertEquals(integer(2), computer.outbound().orElseThrow().payload().field("count"));
    }

    @Test void manualBeginAndQueuedReplacementCannotDiscardPendingSends() {
        var computer = computer(sender(1)); computer.begin(EMPTY); finish(computer);
        var pending = computer.outbound().orElseThrow();
        var busy = assertThrows(ComputationException.class, () -> computer.begin(EMPTY));
        assertEquals(ComputationException.Code.BUSY, busy.code());
        assertEquals(ComputerRuntime.InstallResult.QUEUED, computer.requestInstall(counter(10), ComputerRuntime.MemoryPolicy.PRESERVE));
        var before = computer.snapshot(); computer.tick(); computer.step();
        assertEquals(before, computer.snapshot());
        assertTrue(computer.acknowledge(pending.eventId(), pending.ordinal()));
        computer.begin(EMPTY); finish(computer);
        assertEquals(integer(11), computer.memory().field("count"));
        assertTrue(computer.outbound().isEmpty()); assertTrue(computer.snapshot().delivery().isEmpty());
    }

    @Test void installationQueuedDuringComputationWaitsForItsLastSend() {
        var computer = computer(sender(2)); computer.begin(EMPTY);
        computer.requestInstall(counter(10), ComputerRuntime.MemoryPolicy.PRESERVE); finish(computer);
        var first = computer.outbound().orElseThrow(); computer.acknowledge(first.eventId(), 0);
        var before = computer.snapshot(); computer.tick(); assertEquals(before, computer.snapshot());
        computer.acknowledge(first.eventId(), 1); computer.tick();
        assertTrue(computer.snapshot().pending().isEmpty());
        assertEquals(ComputationMachine.Status.IDLE, computer.report().status());
    }

    @Test void periodicEventsWaitForDeliveryInsteadOfOverwritingOrCatchingUp() {
        var computer = computer(sender(1)); computer.configurePeriodic(1, EMPTY);
        computer.tick(); finish(computer); var first = computer.outbound().orElseThrow();
        for (int i = 0; i < 30; i++) computer.tick();
        assertEquals(first, computer.outbound().orElseThrow());
        assertEquals(integer(1), computer.memory().field("count"));
        assertEquals(0, computer.snapshot().periodic().orElseThrow().remainingTicks());
        computer.acknowledge(first.eventId(), 0); computer.tick(); finish(computer);
        assertEquals(integer(2), computer.memory().field("count"));
        assertNotEquals(first.eventId(), computer.outbound().orElseThrow().eventId());
    }

    @Test void pauseUnloadAndResetHaveExplicitOutboxSemantics() {
        var computer = computer(sender(1)); computer.begin(EMPTY); finish(computer);
        var first = computer.outbound().orElseThrow(); computer.setPaused(true);
        var frozen = computer.snapshot(); computer.tick(); assertEquals(frozen, computer.snapshot());
        computer.unload(); computer.tick(); computer.step(); assertEquals(frozen, computer.snapshot());
        assertThrows(ComputationException.class, () -> computer.acknowledge(first.eventId(), 0));
        computer.load(); computer.reset();
        assertTrue(computer.paused()); assertTrue(computer.outbound().isEmpty());
        assertTrue(computer.snapshot().delivery().isEmpty()); assertEquals(integer(0), computer.memory().field("count"));
        assertFalse(computer.acknowledge(first.eventId(), 0));
    }

    @Test void failedEventHasNoSendAndDoesNotCommitMemory() {
        var valid = sender(1); var nodes = new LinkedHashMap<>(valid.program().graph().nodes());
        nodes.put("old", new ComputationGraph.Constant(integer(Long.MAX_VALUE)));
        var graph = new ComputationGraph(Map.of(), nodes, valid.program().graph().outputs());
        var computer = computer(new ProgramArchive(new ProgramDefinition(graph, valid.program().cells(), valid.program().writes(), valid.program().sends()), Map.of()));
        computer.begin(EMPTY);
        for (int i = 0; computer.report().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); computer.step();
        }
        assertEquals(ComputationMachine.Status.FAILED, computer.report().status());
        assertTrue(computer.outbound().isEmpty()); assertTrue(computer.snapshot().delivery().isEmpty());
        assertEquals(integer(0), computer.memory().field("count"));
    }

    @Test void restoreRejectsMissingOrImpossibleDeliveryState() {
        var computer = computer(sender(2)); var idle = computer.snapshot();
        reject(withDelivery(idle, Optional.of(new ComputerRuntime.DeliveryState(UUID.randomUUID(), 0))));
        computer.begin(EMPTY); var running = computer.snapshot();
        reject(withDelivery(running, Optional.empty()));
        reject(withDelivery(running, Optional.of(new ComputerRuntime.DeliveryState(UUID.randomUUID(), 1))));
        finish(computer); var succeeded = computer.snapshot();
        reject(withDelivery(succeeded, Optional.empty()));
        reject(withDelivery(succeeded, Optional.of(new ComputerRuntime.DeliveryState(UUID.randomUUID(), 3))));
        assertThrows(IllegalArgumentException.class, () -> new ComputerRuntime.DeliveryState(UUID.randomUUID(), -1));
        var noSend = computer(counter(1)); noSend.begin(EMPTY); finish(noSend);
        reject(withDelivery(noSend.snapshot(), Optional.of(new ComputerRuntime.DeliveryState(UUID.randomUUID(), 0))));
    }

    @Test void legacyComputerSnapshotsWithoutSendsStillRestore() {
        var computer = computer(counter(1)); computer.begin(EMPTY); finish(computer);
        var encoded = (DataValue.RecordValue) ValueNbtCodec.decode(ComputerNbtCodec.encode(computer.snapshot(), SAVE_LIMITS), SAVE_LIMITS);
        for (int version : List.of(1, 2)) {
            var fields = new LinkedHashMap<>(encoded.fields()); fields.put("version", integer(version)); fields.remove("delivery");
            if (version == 1) fields.remove("periodic");
            var decoded = ComputerNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), SAVE_LIMITS), SAVE_LIMITS);
            var restored = ComputerRuntime.restore(decoded, HARDWARE);
            assertEquals(computer.snapshot(), restored.snapshot()); assertTrue(restored.outbound().isEmpty());
        }
    }

    static ProgramArchive sender(int sends) {
        var nodes = Map.<String, ComputationGraph.Node>of(
            "old", new ComputationGraph.ReadMemory("count"), "one", new ComputationGraph.Constant(integer(1)),
            "next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "old", "one"),
            "name", new ComputationGraph.Constant(new DataValue.TextValue("receiver")),
            "record", new ComputationGraph.MakeRecord(Map.of("count", "next")));
        var graph = new ComputationGraph(Map.of(), nodes, Map.of("endpoint", "name", "payload", "record", "next", "next"));
        return new ProgramArchive(new ProgramDefinition(graph,
            Map.of("count", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(0))),
            List.of(new ProgramDefinition.Write("count", "next")),
            Collections.nCopies(sends, new ProgramDefinition.Send("endpoint", "payload"))), Map.of());
    }
    private static ComputerRuntime reload(ComputerRuntime computer) {
        var decoded = ComputerNbtCodec.decode(ComputerNbtCodec.encode(computer.snapshot(), SAVE_LIMITS), SAVE_LIMITS);
        var result = ComputerRuntime.restore(decoded, HARDWARE);
        assertEquals(computer.snapshot(), result.snapshot()); assertFalse(result.loaded()); return result;
    }
    private static ComputerRuntime.Snapshot withDelivery(ComputerRuntime.Snapshot s, Optional<ComputerRuntime.DeliveryState> delivery) {
        return new ComputerRuntime.Snapshot(s.id(), s.program(), s.machine(), s.pending(), s.installationDiagnostic(), s.periodic(), delivery);
    }
    private static void reject(ComputerRuntime.Snapshot snapshot) {
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,
            assertThrows(ComputationException.class, () -> ComputerRuntime.restore(snapshot, HARDWARE)).code());
    }
}
