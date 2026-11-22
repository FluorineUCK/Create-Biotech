package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingFlowFixture;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ComputerFlowHostTest {
    static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, 100000, 1),
        new ArchiveLimits(100000, 1000000, 1000000)));
    static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
    static final DataValue.RecordValue INPUTS = new DataValue.RecordValue(Map.of("packet",
        new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12)))));
    static FlowProgram writer() { return new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("send", "x0", "packet"))); }

    @Test void installedFlowOwnsTheOnlyDurableModeAndStartsPausedWithoutExecuting() {
        var reference = new ComputerBlockHost[1]; var changes = new AtomicInteger();
        var host = reference[0] = new ComputerBlockHost(() -> {
            assertEquals(reference[0].flowSnapshot().orElseThrow(), FlowBlockNbtCodec.decode((CompoundTag) reference[0].write(), LIMITS.archive()));
            changes.incrementAndGet();
        });
        host.installFlow(writer(), INPUTS, LIMITS, true);
        var installed = host.flowSnapshot().orElseThrow();
        assertTrue(host.snapshot().isEmpty()); assertTrue(installed.state().paused());
        assertEquals(0, installed.state().work()); assertEquals(INPUTS, installed.initialInputs());
        assertEquals(1, changes.get());
        host.tick(LIMITS, true); assertEquals(installed, host.flowSnapshot().orElseThrow());
        var restored = new ComputerBlockHost(() -> {}); restored.read(host.write());
        restored.tick(LIMITS, true); assertEquals(installed, restored.flowSnapshot().orElseThrow());
        restored.setPaused(false, LIMITS, true); restored.tick(LIMITS, true);
        assertEquals(FlowRuntime.Status.WAITING_WRITE, restored.flowSnapshot().orElseThrow().state().status());
        assertEquals(1, restored.flowSnapshot().orElseThrow().state().work());
    }
    @Test void rejectedModeSwitchDoesNotPoisonALaterValidExplicitSwitch() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()), LIMITS, true);
        var saved = host.flowSnapshot().orElseThrow(); var raw = host.write();
        var limited = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(100000, 1000000,
            ValueNbtCodec.decode(((CompoundTag) raw).getCompound("Data"), LIMITS.archive()).storageUnits() + 2000)));
        var large = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of("text",
            new ComputationGraph.Constant(new DataValue.TextValue("x".repeat(10000)))), Map.of("value", "text")), Map.of(), List.of()), Map.of());
        assertThrows(ComputerBlockHost.HostException.class, () -> host.install(large, ComputerRuntime.MemoryPolicy.RESET, limited, true));
        assertEquals(raw, host.write()); assertEquals(saved, host.flowSnapshot().orElseThrow());
        var empty = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of()), Map.of());
        host.install(empty, ComputerRuntime.MemoryPolicy.RESET, limited, true);
        assertFalse(host.sequentialMode()); assertTrue(host.loaded());
        assertEquals(saved.state().computer(), host.snapshot().orElseThrow().id());
    }
    @Test void hardwareReductionDuringPureComputationIsAResourceFailureNotSaveCorruption() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(BiocomputingFlowFixture.calculator(), BiocomputingFlowFixture.inputs(5), LIMITS, true);
        host.setPaused(false, LIMITS, true); host.tick(LIMITS, true); host.tick(LIMITS, true);
        var saved = host.flowSnapshot().orElseThrow(); var raw = host.write();
        long tight = saved.initialInputs().storageUnits() + saved.state().controlUnits() + saved.state().expression().orElseThrow().arenaUnits() - 1;
        var reduced = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, tight, 1), LIMITS.archive()));
        host.tick(reduced, true);
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
        assertEquals(raw, host.write()); assertEquals(saved, host.flowSnapshot().orElseThrow()); assertFalse(host.loaded());
        var cold = new ComputerBlockHost(() -> {}); cold.read(raw); cold.tick(reduced, true);
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, cold.diagnostic().orElseThrow().code());
        assertEquals(raw, cold.write());
        assertTrue(cold.synchronize(LIMITS, true)); assertEquals(saved, cold.flowSnapshot().orElseThrow());
        host.setPaused(true, LIMITS, true); assertTrue(host.loaded());
        assertEquals(saved.state().work(), host.flowSnapshot().orElseThrow().state().work());
    }
    @Test void failedSaveRollsBackAndResumesFromTheOldPausedCheckpointAfterLimitsReturn() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(BiocomputingFlowFixture.calculator(), BiocomputingFlowFixture.inputs(5), LIMITS, true);
        var old = host.flowSnapshot().orElseThrow(); var raw = host.write();
        long units = ValueNbtCodec.decode(((CompoundTag) raw).getCompound("Data"), LIMITS.archive()).storageUnits();
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(100000, 1000000, units)));
        assertThrows(ComputerBlockHost.HostException.class, () -> host.step(tight, true));
        assertEquals(raw, host.write()); assertEquals(old, host.flowSnapshot().orElseThrow());
        assertFalse(host.loaded()); assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
        host.tick(LIMITS, true); assertTrue(host.loaded()); assertEquals(old, host.flowSnapshot().orElseThrow());
        host.step(LIMITS, true); assertTrue(host.flowSnapshot().orElseThrow().state().paused());
        assertEquals(1, host.flowSnapshot().orElseThrow().state().work());
    }
    @Test void unavailableHostDoesNotAdvanceAndResetUsesInstalledInputsNotModifiedLocals() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(BiocomputingFlowFixture.calculator(), BiocomputingFlowFixture.inputs(5), LIMITS, true);
        host.step(LIMITS, true); var saved = host.flowSnapshot().orElseThrow(); var raw = host.write();
        host.tick(LIMITS, false); assertFalse(host.loaded()); assertEquals(raw, host.write());
        assertThrows(ComputerBlockHost.HostException.class, () -> host.step(LIMITS, false));
        host.unload(); host.tick(LIMITS, true); assertEquals(saved, host.flowSnapshot().orElseThrow());
        host.setPaused(false, LIMITS, true);
        for (int i = 0; host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.WAITING_WRITE; i++) {
            assertTrue(i < 50); host.tick(LIMITS, true);
        }
        assertEquals(BiocomputingFlowFixture.inputs(12), host.flowSnapshot().orElseThrow().state().locals());
        host.reset(LIMITS, true); var reset = host.flowSnapshot().orElseThrow();
        assertEquals(BiocomputingFlowFixture.inputs(5), reset.state().locals()); assertTrue(reset.state().paused());
        assertEquals(saved.state().computer(), reset.state().computer()); assertNotEquals(saved.state().flow(), reset.state().flow());
    }
    @Test void invalidFlowMarkerRemainsOpaqueAndNeverFallsBackToAnEventProgram() {
        var future = new CompoundTag(); future.putInt(FlowBlockNbtCodec.MARKER, 999); future.putString("other", "retain");
        var host = new ComputerBlockHost(() -> fail("Invalid saves never dirty")); host.read(future);
        future.putString("other", "caller changed"); host.tick(LIMITS, true);
        assertTrue(host.sequentialMode()); assertTrue(host.snapshot().isEmpty()); assertTrue(host.flowSnapshot().isEmpty());
        assertEquals(ComputerBlockHost.Code.INVALID_SAVE, host.diagnostic().orElseThrow().code());
        assertEquals("retain", ((CompoundTag) host.write()).getString("other"));
        assertThrows(ComputerBlockHost.HostException.class, () -> host.installFlow(writer(), INPUTS, LIMITS, true));
    }
    @Test void pendingEventAndPeriodicWorkCannotBeSilentlyReplacedAndFlowRejectsLegacyReception() {
        var host = new ComputerBlockHost(() -> {}); var empty = new DataValue.RecordValue(Map.of());
        host.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        var id = host.snapshot().orElseThrow().id(); host.begin(empty, LIMITS, true);
        var old = host.snapshot();
        assertThrows(ComputationException.class, () -> host.installFlow(writer(), INPUTS, LIMITS, true)); assertEquals(old, host.snapshot());
        host.reset(LIMITS, true); host.configurePeriodic(3, empty, LIMITS, true);
        assertThrows(ComputationException.class, () -> host.installFlow(writer(), INPUTS, LIMITS, true));
        host.clearPeriodic(LIMITS, true);
        var message = new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, empty);
        assertEquals(AdmissionStatus.ACCEPTED, host.receive(message, LIMITS, true));
        assertThrows(ComputationException.class, () -> host.installFlow(writer(), INPUTS, LIMITS, true));
        host.reset(LIMITS, true); host.installFlow(writer(), INPUTS, LIMITS, true);
        assertEquals(id, host.flowSnapshot().orElseThrow().state().computer());
        assertEquals(AdmissionStatus.UNSUPPORTED, host.receive(message, LIMITS, true));
        assertEquals(ReceptionStatus.UNSUPPORTED, host.receiveReady(id, message, LIMITS, true));
        host.step(LIMITS, true);
        assertThrows(ComputationException.class, () -> host.installFlow(writer(), INPUTS, LIMITS, true));
        assertThrows(ComputationException.class, () -> host.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true));
    }
    @Test void initialInputCopyIsChargedInAdditionToCurrentLocals() {
        var core = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), writer(), INPUTS, LIMITS.hardware());
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, core.checkpoint().controlUnits() + 2, 1), LIMITS.archive()));
        var host = new ComputerBlockHost(() -> fail("Rejected install cannot dirty"));
        var error = assertThrows(ComputationException.class, () -> host.installFlow(writer(), INPUTS, tight, true));
        assertEquals(ComputationException.Code.MEMORY_LIMIT, error.code()); assertNull(host.write()); assertFalse(host.sequentialMode());
    }
}
