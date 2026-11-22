package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.EventEntryFixtures.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus;
import java.util.*;
import org.junit.jupiter.api.Test;

class EventEntryComputerSaveTest {
    @Test void everyHandlerSliceRestoresWithoutAdvancingAndKeepsItsSendPrefix() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), program(true), HARDWARE);
        runtime.setPaused(true); assertEquals(AdmissionStatus.ACCEPTED, runtime.receive(message(7)));
        runtime.step();
        for (int i = 0; runtime.report().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); var snapshot = runtime.snapshot(); runtime = restore(snapshot);
            assertEquals(snapshot, runtime.snapshot()); assertFalse(runtime.loaded()); assertTrue(runtime.paused());
            runtime.tick(); runtime.step(); assertEquals(snapshot, runtime.snapshot()); runtime.load(); runtime.step();
        }
        assertEquals(integer(7), runtime.memory().field("n"));
        var first = runtime.outbound().orElseThrow(); assertEquals("message_peer_a", first.endpoint());
        assertTrue(runtime.acknowledge(first.eventId(), first.ordinal()));
        runtime = restore(runtime.snapshot()); runtime.load();
        var second = runtime.outbound().orElseThrow(); assertEquals("message_peer_b", second.endpoint());
        assertEquals(1, second.ordinal()); assertTrue(runtime.acknowledge(second.eventId(), second.ordinal()));
        assertTrue(runtime.outbound().isEmpty());
    }

    @Test void unknownSavedEntryIsRejectedRatherThanSilentlyRunningRoot() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), program(false), HARDWARE);
        var encoded = ValueNbtCodec.decode(ComputerNbtCodec.encode(runtime.snapshot(), ARCHIVE), ARCHIVE);
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)encoded).fields());
        fields.put("activeEntry", new DataValue.TextValue("missing"));
        var saved = ComputerNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE);
        assertThrows(ComputationException.class, () -> ComputerRuntime.restore(saved, HARDWARE));
    }

    @Test void hostMailboxPersistenceAndHardwareRevalidationUseMessageInputSchema() {
        var host = new ComputerBlockHost(() -> {});
        host.install(program(false), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        host.begin(input("manual", 1), LIMITS, true);
        for (int i = 0; host.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); host.step(LIMITS, true);
        }
        host.setPaused(true, LIMITS, true); assertEquals(AdmissionStatus.ACCEPTED, host.receive(message(7), LIMITS, true));
        var saved = host.write(); var restored = new ComputerBlockHost(() -> {}); restored.read(saved);
        restored.tick(LIMITS, true);
        assertEquals(host.snapshot(), restored.snapshot()); assertEquals(integer(1), restored.snapshot().orElseThrow().machine().memory().field("n"));
        restored.step(LIMITS, true);
        for (int i = 0; restored.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); restored.step(LIMITS, true);
        }
        assertEquals(integer(7), restored.snapshot().orElseThrow().machine().memory().field("n"));
        assertTrue(restored.snapshot().orElseThrow().machine().paused());
    }

    @Test void changingToAnExistingIncompatibleEntryRejectsTheSavedContinuation() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), program(true), HARDWARE);
        assertEquals(AdmissionStatus.ACCEPTED, runtime.receive(message(7))); runtime.step();
        assertEquals(ComputationMachine.Status.RUNNING, runtime.report().status());
        var data = (DataValue.RecordValue)ValueNbtCodec.decode(ComputerNbtCodec.encode(runtime.snapshot(), ARCHIVE), ARCHIVE);
        var fields = new LinkedHashMap<>(data.fields());
        fields.put("activeEntry", new DataValue.TextValue("timer"));
        var saved = ComputerNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE);
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,
            assertThrows(ComputationException.class, () -> ComputerRuntime.restore(saved, HARDWARE)).code());
    }

    @Test void handlerDeliveryCursorCannotExceedItsOwnSends() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), program(true), HARDWARE);
        assertEquals(AdmissionStatus.ACCEPTED, runtime.receive(message(7))); runtime.step(); finish(runtime);
        var data = (DataValue.RecordValue)ValueNbtCodec.decode(ComputerNbtCodec.encode(runtime.snapshot(), ARCHIVE), ARCHIVE);
        var fields = new LinkedHashMap<>(data.fields());
        var delivery = new LinkedHashMap<>(((DataValue.RecordValue)data.field("delivery")).fields());
        delivery.put("acknowledged", integer(3)); // message owns exactly two sends, unlike root's one.
        fields.put("delivery", new DataValue.RecordValue(delivery));
        var saved = ComputerNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE);
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,
            assertThrows(ComputationException.class, () -> ComputerRuntime.restore(saved, HARDWARE)).code());
    }

    @Test void versionFourComputerAndVersionTwoProgramContinueUsingRoot() {
        var definition = program(false).program();
        var rootOnly = new ProgramArchive(new ProgramDefinition(definition.graph(), definition.cells(), definition.writes(), List.of()), Map.of());
        var runtime = new ComputerRuntime(UUID.randomUUID(), rootOnly, HARDWARE);
        runtime.begin(input("manual", 3)); finish(runtime);
        var data = (DataValue.RecordValue)ValueNbtCodec.decode(ComputerNbtCodec.encode(runtime.snapshot(), ARCHIVE), ARCHIVE);
        var fields = new LinkedHashMap<>(data.fields());
        fields.put("version", integer(4)); fields.remove("activeEntry");
        var oldProgram = new LinkedHashMap<>(((DataValue.RecordValue)fields.get("program")).fields());
        oldProgram.put("version", integer(2)); oldProgram.remove("entries");
        fields.put("program", new DataValue.RecordValue(oldProgram));
        var saved = ComputerNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE);
        var restored = ComputerRuntime.restore(saved, HARDWARE); restored.load();
        assertEquals(integer(3), restored.memory().field("n"));
        restored.begin(input("manual", 9)); finish(restored);
        assertEquals(integer(9), restored.memory().field("n"));
    }

    private static ComputerRuntime restore(ComputerRuntime.Snapshot snapshot) {
        return ComputerRuntime.restore(ComputerNbtCodec.decode(ComputerNbtCodec.encode(snapshot, ARCHIVE), ARCHIVE), HARDWARE);
    }
}
