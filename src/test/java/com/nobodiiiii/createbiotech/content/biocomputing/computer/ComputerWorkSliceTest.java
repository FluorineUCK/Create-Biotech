package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntimeTest.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ComputerNbtCodec;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerWorkSliceTest {
    private static final ResourceLimits HARDWARE = new ResourceLimits(1000, 1_000_000, 4);

    @Test void activityTimeDoesNotAdvanceForEachWorkSliceAndRamCommitsOnlyOnCompletion() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), counter(1), HARDWARE);
        runtime.configurePeriodic(2, EMPTY);
        assertTrue(runtime.beginActivityTick());
        assertEquals(1, runtime.snapshot().periodic().orElseThrow().remainingTicks());
        assertEquals(0, runtime.runSlice(3).workUnits());
        assertTrue(runtime.beginActivityTick());
        assertEquals(2, runtime.snapshot().periodic().orElseThrow().remainingTicks());
        assertEquals(ComputationMachine.Status.RUNNING, runtime.report().status());
        assertEquals(0, runtime.report().workUnits());
        for (int i = 0; runtime.report().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 20);
            assertEquals(integer(0), runtime.memory().field("count"));
            long before = runtime.report().workUnits();
            runtime.runSlice(1);
            assertEquals(before + 1, runtime.report().workUnits());
            assertEquals(2, runtime.snapshot().periodic().orElseThrow().remainingTicks());
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, runtime.report().status());
        assertEquals(integer(1), runtime.memory().field("count"));
        var completed = runtime.snapshot();
        runtime.runSlice(Long.MAX_VALUE);
        assertEquals(completed, runtime.snapshot());
    }

    @Test void zeroNegativeAndOversizedAllowancesKeepAnExplicitHardwareBound() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), counter(1), HARDWARE);
        runtime.begin(EMPTY);
        var before = runtime.snapshot();
        runtime.runSlice(0);
        assertEquals(before, runtime.snapshot());
        assertThrows(IllegalArgumentException.class, () -> runtime.runSlice(-1));
        assertEquals(before, runtime.snapshot());
        runtime.runSlice(Long.MAX_VALUE);
        assertEquals(HARDWARE.workPerSlice(), runtime.report().workUnits());
        assertEquals(integer(0), runtime.memory().field("count"));

        var machine = new ComputationMachine(counter(1).compile(HARDWARE));
        machine.begin(EMPTY); var frozen = machine.checkpoint();
        machine.runSlice(0); assertEquals(frozen, machine.checkpoint());
        assertThrows(IllegalArgumentException.class, () -> machine.runSlice(Long.MIN_VALUE));
        assertEquals(frozen, machine.checkpoint());
        machine.runSlice(Long.MAX_VALUE);
        assertEquals(runtime.snapshot().machine(), machine.checkpoint());
    }

    @Test void slicesRespectPauseAndUnloadButDebugStepDoesNotAdvanceTheClock() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), counter(1), HARDWARE);
        runtime.configurePeriodic(3, EMPTY); runtime.begin(EMPTY);
        runtime.setPaused(true); var paused = runtime.snapshot();
        assertTrue(runtime.beginActivityTick()); runtime.runSlice(4);
        assertEquals(paused, runtime.snapshot());
        runtime.step(); assertEquals(1, runtime.report().workUnits());
        assertTrue(runtime.paused());
        assertEquals(3, runtime.snapshot().periodic().orElseThrow().remainingTicks());
        runtime.setPaused(false); runtime.unload(); var unloaded = runtime.snapshot();
        assertFalse(runtime.beginActivityTick()); runtime.runSlice(4); runtime.step();
        assertEquals(unloaded, runtime.snapshot());
        runtime.load(); runtime.runSlice(1);
        assertEquals(2, runtime.report().workUnits());
        assertEquals(3, runtime.snapshot().periodic().orElseThrow().remainingTicks());
    }

    @Test void existingTickEqualsOneActivityTickAndItsAllocatedWork() {
        UUID id = UUID.randomUUID();
        var whole = new ComputerRuntime(id, counter(1), HARDWARE);
        var sliced = new ComputerRuntime(id, counter(1), HARDWARE);
        whole.configurePeriodic(2, EMPTY); sliced.configurePeriodic(2, EMPTY);
        for (int tick = 0; tick < 20; tick++) {
            if (tick == 4) { whole.setPaused(true); sliced.setPaused(true); }
            if (tick == 6) { whole.setPaused(false); sliced.setPaused(false); }
            if (tick == 8) { whole.unload(); sliced.unload(); }
            if (tick == 10) { whole.load(); sliced.load(); }
            whole.tick();
            if (sliced.beginActivityTick())
                for (int budget = 0; budget < HARDWARE.workPerSlice(); budget++) sliced.runSlice(1);
            assertEquals(whole.snapshot(), sliced.snapshot(), "activity tick " + tick);
        }
        assertNotEquals(integer(0), sliced.memory().field("count"));
    }

    @Test void savedSlicesRetainProgressWithoutApplyingAQueuedInstallation() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), counter(1), HARDWARE);
        runtime.begin(EMPTY);
        assertEquals(ComputerRuntime.InstallResult.QUEUED,
            runtime.requestInstall(counter(10), ComputerRuntime.MemoryPolicy.PRESERVE));
        for (int i = 0; runtime.report().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 20);
            runtime.setPaused(true);
            var restored = restore(runtime);
            assertEquals(runtime.snapshot(), restored.snapshot());
            restored.runSlice(1); assertEquals(runtime.snapshot(), restored.snapshot());
            restored.load(); restored.runSlice(1); assertEquals(runtime.snapshot(), restored.snapshot());
            restored.setPaused(false); runtime.setPaused(false);
            runtime.runSlice(1); restored.runSlice(1);
            assertEquals(runtime.snapshot(), restored.snapshot()); runtime = restored;
        }
        assertEquals(integer(1), runtime.memory().field("count"));
        assertTrue(runtime.snapshot().pending().isPresent());
        var completed = runtime.snapshot(); runtime.runSlice(4);
        assertEquals(completed, runtime.snapshot());
        assertTrue(runtime.beginActivityTick());
        assertTrue(runtime.snapshot().pending().isEmpty());
        assertEquals(ComputationMachine.Status.IDLE, runtime.report().status());
        runtime.begin(EMPTY); completeSlices(runtime);
        assertEquals(integer(11), runtime.memory().field("count"));
    }

    @Test void failedSliceKeepsOldRamAndDisablesPeriodicRestart() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), counter(Long.MAX_VALUE), HARDWARE);
        runtime.begin(EMPTY); completeSlices(runtime);
        assertEquals(integer(Long.MAX_VALUE), runtime.memory().field("count"));
        runtime.configurePeriodic(1, EMPTY); assertTrue(runtime.beginActivityTick());
        completeSlices(runtime);
        assertEquals(ComputationMachine.Status.FAILED, runtime.report().status());
        assertEquals(ComputationException.Code.ARITHMETIC_OVERFLOW, runtime.report().diagnostic().orElseThrow().code());
        assertEquals(integer(Long.MAX_VALUE), runtime.memory().field("count"));
        assertFalse(runtime.snapshot().periodic().orElseThrow().enabled());
        assertTrue(runtime.outbound().isEmpty());
        var failed = runtime.snapshot(); runtime.beginActivityTick(); runtime.runSlice(4);
        assertEquals(failed, runtime.snapshot());
        assertEquals(failed, restore(runtime).snapshot());
    }

    @Test void rejectedPendingInstallStopsTheActivityBoundaryWithoutAdvancingOldCode() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), counter(1), HARDWARE);
        runtime.begin(EMPTY);
        var incompatible = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("count", new ProgramDefinition.Cell(DataType.Scalar.TEXT, new DataValue.TextValue("initial"))), List.of()), Map.of());
        assertEquals(ComputerRuntime.InstallResult.QUEUED,
            runtime.requestInstall(incompatible, ComputerRuntime.MemoryPolicy.PRESERVE));
        completeSlices(runtime);
        var before = runtime.snapshot();
        assertEquals(ComputationMachine.Status.SUCCEEDED, runtime.report().status());
        assertFalse(runtime.beginActivityTick());
        assertEquals(before.program(), runtime.snapshot().program());
        assertEquals(before.machine(), runtime.snapshot().machine());
        assertEquals(integer(1), runtime.memory().field("count"));
        assertTrue(runtime.snapshot().pending().isEmpty());
        assertEquals(ComputationException.Code.TYPE_MISMATCH, runtime.installationDiagnostic().orElseThrow().code());
        assertEquals(runtime.snapshot(), restore(runtime).snapshot());
    }

    @Test void rpcEffectsPublishOnlyOnceAfterTheWholeEventSurvivesSliceRestoration() {
        var client = new ComputerRuntime(UUID.randomUUID(), RequestReplyFixtures.client(), HARDWARE);
        client.begin(RequestReplyFixtures.input("query", 12));
        for (int i = 0; client.report().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 20);
            assertTrue(client.outbound().isEmpty());
            assertTrue(client.snapshot().rpc().pending().isEmpty());
            assertFalse(client.snapshot().rpc().staged().isEmpty());
            var restored = restore(client); restored.load();
            assertEquals(client.snapshot(), restored.snapshot()); client = restored;
            client.runSlice(1);
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, client.report().status());
        var outgoing = client.outbound().orElseThrow();
        assertEquals(1, client.snapshot().rpc().pending().size());
        assertTrue(client.snapshot().rpc().staged().isEmpty());
        client.runSlice(4); assertEquals(outgoing, client.outbound().orElseThrow());
        assertTrue(client.acknowledge(outgoing.eventId(), outgoing.ordinal()));
        client.runSlice(4); assertTrue(client.outbound().isEmpty());

        var server = new ComputerRuntime(UUID.randomUUID(), RequestReplyFixtures.server(), HARDWARE);
        assertEquals(AdmissionStatus.ACCEPTED, server.receive(RequestReplyFixtures.request(client.id(), outgoing, "client")));
        var admitted = server.snapshot(); server.runSlice(4);
        assertEquals(admitted, server.snapshot());
        assertTrue(server.beginActivityTick());
        assertEquals(0, server.report().workUnits());
        completeSlices(server);
        assertEquals(integer(12), server.memory().field("result"));
        assertEquals(RequestReplyFixtures.input("answer", 12), server.outbound().orElseThrow().payload());
    }

    private static ComputerRuntime restore(ComputerRuntime runtime) {
        return ComputerRuntime.restore(ComputerNbtCodec.decode(
            ComputerNbtCodec.encode(runtime.snapshot(), SAVE_LIMITS), SAVE_LIMITS), HARDWARE);
    }

    private static void completeSlices(ComputerRuntime runtime) {
        for (int i = 0; runtime.report().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 30); runtime.runSlice(1);
        }
    }
}
