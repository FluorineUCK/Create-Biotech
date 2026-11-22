package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ComputerMailboxTest {
    @Test void admissionCopiesNamedInputsWithoutExecutingAndOwnerTickProcessesFifo() {
        var receiver = computer(receiver()); var source = UUID.randomUUID();
        var first = message(source, 0, 4); var second = message(source, 1, 8);
        assertEquals(AdmissionStatus.ACCEPTED, receiver.receive(first));
        assertEquals(AdmissionStatus.ACCEPTED, receiver.receive(second));
        assertEquals(integer(0), receiver.memory().field("total"));
        assertEquals(ComputationMachine.Status.IDLE, receiver.report().status());
        receiver.tick(); finish(receiver); assertEquals(integer(4), receiver.memory().field("total"));
        assertEquals(1, receiver.snapshot().mailbox().queued().size());
        receiver.tick(); finish(receiver); assertEquals(integer(12), receiver.memory().field("total"));
        assertTrue(receiver.snapshot().mailbox().queued().isEmpty());
    }

    @Test void duplicateBeforeAndAfterSaveDoesNotExecuteAgainAndOlderIdentityIsRejected() {
        var receiver = computer(receiver()); var source = UUID.randomUUID(); var first = message(source, 0, 4);
        assertEquals(AdmissionStatus.ACCEPTED, receiver.receive(first));
        assertEquals(AdmissionStatus.DUPLICATE, receiver.receive(first));
        receiver.tick();
        for (int i = 0; receiver.report().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); receiver = reload(receiver); receiver.load(); receiver.step();
        }
        receiver = reload(receiver); receiver.load();
        assertEquals(AdmissionStatus.DUPLICATE, receiver.receive(first));
        receiver.tick(); assertEquals(integer(4), receiver.memory().field("total"));
        assertEquals(AdmissionStatus.ACCEPTED, receiver.receive(message(source, 2, 9)));
        assertEquals(AdmissionStatus.STALE, receiver.receive(first));
        assertEquals(AdmissionStatus.IDENTITY_CONFLICT, receiver.receive(message(source, 2, 20)));
    }

    @Test void pauseUnloadResetAndInstallDoNotStealOrReplayQueuedInputs() {
        var receiver = computer(receiver()); var first = message(UUID.randomUUID(), 0, 4);
        receiver.setPaused(true); assertEquals(AdmissionStatus.ACCEPTED, receiver.receive(first));
        var before = receiver.snapshot(); receiver.tick(); assertEquals(before, receiver.snapshot());
        receiver.unload(); assertEquals(AdmissionStatus.UNAVAILABLE, receiver.receive(message(UUID.randomUUID(), 0, 8)));
        receiver.tick(); receiver.step(); assertEquals(before, receiver.snapshot()); receiver.load();
        assertThrows(ComputationException.class, () -> receiver.requestInstall(counter(1), ComputerRuntime.MemoryPolicy.RESET));
        assertThrows(ComputationException.class, () -> receiver.begin(new DataValue.RecordValue(Map.of("amount", integer(100)))));
        receiver.step(); assertEquals(ComputationMachine.Status.RUNNING, receiver.report().status());
        assertEquals(ComputerRuntime.InstallResult.QUEUED, receiver.requestInstall(counter(1), ComputerRuntime.MemoryPolicy.RESET));
        assertEquals(AdmissionStatus.BUSY, receiver.receive(message(UUID.randomUUID(), 0, 8)));
        receiver.reset(); assertTrue(receiver.paused()); assertTrue(receiver.snapshot().mailbox().queued().isEmpty());
        assertEquals(AdmissionStatus.DUPLICATE, receiver.receive(first));
        assertEquals(integer(0), receiver.memory().field("total"));
    }

    @Test void payloadSchemaAndMemoryFailuresDoNotConsumeReceiptOrQueueCapacity() {
        var receiver = new ComputerRuntime(UUID.randomUUID(), receiver(), new ResourceLimits(100, 100, 1));
        var wrong = new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, EMPTY);
        assertEquals(AdmissionStatus.TYPE_MISMATCH, receiver.receive(wrong));
        var valid = message(wrong.source(), 0, 4);
        assertEquals(AdmissionStatus.MEMORY_LIMIT, receiver.receive(valid));
        assertTrue(receiver.snapshot().mailbox().receipts().isEmpty());
        assertEquals(ComputationMachine.Status.IDLE, receiver.report().status());
        var normal = new ComputerRuntime(UUID.randomUUID(), receiver(), HARDWARE, new MailboxLimits(1, 1));
        assertEquals(AdmissionStatus.ACCEPTED, normal.receive(valid));
        assertEquals(AdmissionStatus.DUPLICATE, normal.receive(valid));
        assertEquals(AdmissionStatus.QUEUE_FULL, normal.receive(message(valid.source(), 1, 6)));
        normal.tick(); finish(normal);
        assertEquals(AdmissionStatus.SOURCE_LIMIT, normal.receive(message(UUID.randomUUID(), 0, 4)));
        assertEquals(AdmissionStatus.ACCEPTED, normal.receive(message(valid.source(), 1, 6)));
    }

    @Test void queueReservationSurvivesPeriodicChangesAndHardwareRevalidation() {
        var receiver = computer(receiver()); var input = new DataValue.RecordValue(Map.of("amount", integer(1)));
        receiver.configurePeriodic(20, input); receiver.receive(message(UUID.randomUUID(), 0, 4));
        var queued = receiver.snapshot().mailbox(); receiver.clearPeriodic(); assertEquals(queued, receiver.snapshot().mailbox());
        receiver.configurePeriodic(20, input); receiver = reload(receiver); receiver.load();
        receiver.tick(); finish(receiver); assertEquals(integer(4), receiver.memory().field("total"));
        assertFalse(receiver.snapshot().mailbox().receipts().isEmpty());
        var saved = receiver.snapshot();
        assertThrows(ComputationException.class, () -> ComputerRuntime.restore(saved, new ResourceLimits(100, 100, 1)));
    }

    @Test void senderSequenceRangesAreNeverReusedAfterResetFailureOrReload() {
        var sender = computer(ComputerOutboxTest.sender(2)); sender.begin(EMPTY); finish(sender);
        var first = sender.outbound().orElseThrow(); assertEquals(0, first.sequence());
        sender.acknowledge(first.eventId(), 0); assertEquals(1, sender.outbound().orElseThrow().sequence());
        sender.reset(); sender = reload(sender); sender.load(); sender.begin(EMPTY); finish(sender);
        assertEquals(2, sender.outbound().orElseThrow().sequence());
        sender.reset(); var saved = sender.snapshot();
        var exhausted = new ComputerRuntime.Snapshot(saved.id(), saved.program(), saved.machine(), saved.pending(), saved.installationDiagnostic(),
            saved.periodic(), saved.delivery(), saved.mailbox(), Long.MAX_VALUE);
        var restored = ComputerRuntime.restore(exhausted, HARDWARE); restored.load();
        assertEquals(ComputationException.Code.ARITHMETIC_OVERFLOW, assertThrows(ComputationException.class, () -> restored.begin(EMPTY)).code());
        assertEquals(exhausted, restored.snapshot());
    }

    @Test void hostAdmissionIsDurableBeforeReturnAndIdleTickStartsReceiver() {
        var limits = new ComputerHostLimits(HARDWARE, SAVE_LIMITS, new MailboxLimits(2, 2));
        var reference = new ComputerBlockHost[1];
        var host = reference[0] = new ComputerBlockHost(() -> assertEquals(reference[0].snapshot().orElseThrow(),
            ComputerNbtCodec.decode((CompoundTag) reference[0].write(), SAVE_LIMITS)));
        host.install(receiver(), ComputerRuntime.MemoryPolicy.RESET, limits, true);
        var first = message(UUID.randomUUID(), 0, 4);
        assertEquals(AdmissionStatus.ACCEPTED, host.receive(first, limits, true));
        assertEquals(integer(0), host.snapshot().orElseThrow().machine().memory().field("total"));
        var saved = host.write();
        var restored = new ComputerBlockHost(() -> {}); restored.read(saved);
        assertEquals(AdmissionStatus.DUPLICATE, restored.receive(first, limits, true)); assertEquals(saved, restored.write());
        restored.tick(limits, true);
        for (int i = 0; restored.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); restored.tick(limits, true);
        }
        assertEquals(integer(4), restored.snapshot().orElseThrow().machine().memory().field("total"));
    }

    @Test void unsaveableHostAdmissionDoesNotAcknowledgeOrPublishMailbox() {
        var host = new ComputerBlockHost(() -> {}); var limits = new ComputerHostLimits(HARDWARE, SAVE_LIMITS);
        host.install(receiver(), ComputerRuntime.MemoryPolicy.RESET, limits, true); var saved = (CompoundTag) host.write();
        var tight = new ComputerHostLimits(HARDWARE, new ArchiveLimits(100_000, 1_000_000, ValueNbtCodec.decode(saved, SAVE_LIMITS).storageUnits()));
        assertEquals(AdmissionStatus.ARCHIVE_LIMIT, host.receive(message(UUID.randomUUID(), 0, 4), tight, true));
        assertEquals(saved, host.write()); assertTrue(host.snapshot().orElseThrow().mailbox().queued().isEmpty());
        assertTrue(host.loaded(), "Rejected external input must not suspend valid owned state");
        assertDoesNotThrow(() -> host.reset(tight, true));
    }

    @Test void oversizedAdmissionDoesNotStopActiveEventOrPoisonSubsequentSmallMessage() {
        var graph = new ComputationGraph(Map.of("text", DataType.Scalar.TEXT),
            Map.of("input", new ComputationGraph.Input("text")), Map.of("result", "input"));
        var program = new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
        var host = new ComputerBlockHost(() -> {});
        var limits = new ComputerHostLimits(HARDWARE, SAVE_LIMITS);
        host.install(program, ComputerRuntime.MemoryPolicy.RESET, limits, true);
        var small = new DataValue.RecordValue(Map.of("text", new DataValue.TextValue("ok")));
        host.begin(small, limits, true);
        var before = (CompoundTag) host.write();
        var tight = new ComputerHostLimits(HARDWARE, new ArchiveLimits(100_000, 1_000_000,
            ValueNbtCodec.decode(before, SAVE_LIMITS).storageUnits() + 2_000));
        var source = UUID.randomUUID();
        var event = UUID.randomUUID();
        var large = new DataValue.RecordValue(Map.of("text", new DataValue.TextValue("x".repeat(5_000))));
        assertEquals(AdmissionStatus.ARCHIVE_LIMIT, host.receive(new MessageEnvelope(source, 0, event, 0, large), tight, true));
        assertEquals(before, host.write());
        assertTrue(host.loaded());
        for (int i = 0; host.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); host.tick(tight, true);
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, host.snapshot().orElseThrow().machine().status());
        assertEquals(AdmissionStatus.ACCEPTED, host.receive(new MessageEnvelope(source, 0, event, 0, small), tight, true));
        assertEquals(1, host.snapshot().orElseThrow().mailbox().queued().size());
    }

    @Test void queuedMessagePrecedesDueTimerAndPendingOutputPreventsConsumption() {
        var receiver = computer(receiver()); receiver.configurePeriodic(1, new DataValue.RecordValue(Map.of("amount", integer(100))));
        receiver.receive(message(UUID.randomUUID(), 0, 7)); receiver.tick(); finish(receiver);
        assertEquals(integer(7), receiver.memory().field("total"));
        receiver.tick(); finish(receiver); assertEquals(integer(107), receiver.memory().field("total"));
        var sender = computer(ComputerOutboxTest.sender(1)); sender.begin(EMPTY); finish(sender);
        var output = sender.outbound().orElseThrow();
        assertEquals(AdmissionStatus.ACCEPTED, sender.receive(new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, EMPTY)));
        sender.tick(); assertEquals(integer(1), sender.memory().field("count")); assertEquals(1, sender.snapshot().mailbox().queued().size());
        sender.acknowledge(output.eventId(), 0); sender.tick(); finish(sender);
        assertEquals(integer(2), sender.memory().field("count")); assertEquals(1, sender.outbound().orElseThrow().sequence());
    }

    @Test void failureRetainsUnstartedMessagesAndDoesNotReuseReservedSendSequence() {
        var receiver = computer(receiver()); var source = UUID.randomUUID();
        var large = new MessageEnvelope(source, 0, UUID.randomUUID(), 0, new DataValue.RecordValue(Map.of("amount", integer(Long.MAX_VALUE))));
        receiver.receive(large); receiver.receive(message(source, 1, 1)); var last = message(source, 2, 1); receiver.receive(last);
        receiver.tick(); finish(receiver); receiver.tick();
        for (int i = 0; receiver.report().status() == ComputationMachine.Status.RUNNING; i++) { assertTrue(i < 100); receiver.tick(); }
        assertEquals(ComputationMachine.Status.FAILED, receiver.report().status()); assertEquals(1, receiver.snapshot().mailbox().queued().size());
        var stopped = receiver.snapshot(); for (int i = 0; i < 10; i++) receiver.tick(); assertEquals(stopped, receiver.snapshot());
        receiver.reset(); assertEquals(AdmissionStatus.DUPLICATE, receiver.receive(last));
        var template = ComputerOutboxTest.sender(1); var nodes = new LinkedHashMap<>(template.program().graph().nodes());
        nodes.put("old", new ComputationGraph.Constant(integer(Long.MAX_VALUE)));
        var faulty = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), nodes, template.program().graph().outputs()),
            template.program().cells(), template.program().writes(), template.program().sends()), Map.of());
        var sender = computer(faulty); sender.begin(EMPTY);
        for (int i = 0; sender.report().status() == ComputationMachine.Status.RUNNING; i++) { assertTrue(i < 100); sender.tick(); }
        assertEquals(ComputationMachine.Status.FAILED, sender.report().status()); assertEquals(1, sender.snapshot().nextSequence());
        sender.reset(); sender.requestInstall(template, ComputerRuntime.MemoryPolicy.RESET); sender.begin(EMPTY); finish(sender);
        assertEquals(1, sender.outbound().orElseThrow().sequence());
    }

    @Test void oldV3OutboxMigratesWithStableRemainingSuffixAndNextReservation() {
        var sender = computer(ComputerOutboxTest.sender(2)); sender.begin(EMPTY); finish(sender);
        var first = sender.outbound().orElseThrow(); sender.acknowledge(first.eventId(), 0);
        var encoded = (DataValue.RecordValue) ValueNbtCodec.decode(ComputerNbtCodec.encode(sender.snapshot(), SAVE_LIMITS), SAVE_LIMITS);
        var fields = new LinkedHashMap<>(encoded.fields()); fields.put("version", integer(3)); fields.remove("mailbox"); fields.remove("nextSequence");
        var delivery = new LinkedHashMap<>(((DataValue.RecordValue) fields.get("delivery")).fields()); delivery.remove("sequenceBase");
        fields.put("delivery", new DataValue.RecordValue(delivery));
        var restored = ComputerRuntime.restore(ComputerNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), SAVE_LIMITS), SAVE_LIMITS), HARDWARE);
        restored.load(); assertEquals(first.eventId(), restored.outbound().orElseThrow().eventId());
        assertEquals(1, restored.outbound().orElseThrow().sequence());
        restored.reset(); restored.begin(EMPTY); finish(restored); assertEquals(2, restored.outbound().orElseThrow().sequence());
    }

    @Test void restoreRejectsQueuedSchemaOrCapacityAndImpossibleSequenceRanges() {
        var runtime = computer(receiver()); runtime.receive(message(UUID.randomUUID(), 0, 1)); runtime.receive(message(UUID.randomUUID(), 0, 2));
        var saved = runtime.snapshot();
        assertEquals(ComputationException.Code.MEMORY_LIMIT, assertThrows(ComputationException.class,
            () -> ComputerRuntime.restore(saved, HARDWARE, new MailboxLimits(1, 2))).code());
        var wrongMailbox = MailboxState.empty().admit(new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, EMPTY), MailboxLimits.DEFAULT).state();
        var corrupt = new ComputerRuntime.Snapshot(saved.id(), saved.program(), saved.machine(), saved.pending(), saved.installationDiagnostic(), saved.periodic(), saved.delivery(), wrongMailbox, 0);
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> ComputerRuntime.restore(corrupt, HARDWARE)).code());
        var sender = computer(ComputerOutboxTest.sender(1)); sender.begin(EMPTY); var active = sender.snapshot();
        var reused = new ComputerRuntime.Snapshot(active.id(), active.program(), active.machine(), active.pending(), active.installationDiagnostic(), active.periodic(), active.delivery(), active.mailbox(), 0);
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> ComputerRuntime.restore(reused, HARDWARE)).code());
    }

    static ProgramArchive receiver() {
        var graph = new ComputationGraph(Map.of("amount", DataType.Scalar.INTEGER), Map.of(
            "input", new ComputationGraph.Input("amount"), "old", new ComputationGraph.ReadMemory("total"),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "old", "input")), Map.of("sum", "sum"));
        return new ProgramArchive(new ProgramDefinition(graph, Map.of("total", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(0))),
            List.of(new ProgramDefinition.Write("total", "sum"))), Map.of());
    }
    static MessageEnvelope message(UUID source, long sequence, int amount) {
        return new MessageEnvelope(source, sequence, UUID.randomUUID(), 0, new DataValue.RecordValue(Map.of("amount", integer(amount))));
    }
    private static ComputerRuntime reload(ComputerRuntime runtime) {
        var restored = ComputerRuntime.restore(ComputerNbtCodec.decode(ComputerNbtCodec.encode(runtime.snapshot(), SAVE_LIMITS), SAVE_LIMITS), HARDWARE);
        assertEquals(runtime.snapshot(), restored.snapshot()); return restored;
    }
}
