package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntimeTest.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingFlowFixture;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerOwnerBudgetTest {
    private static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(
        new ResourceLimits(1000, 100_000, 4), SAVE_LIMITS));

    @Test void replacingAFlowCannotRenewItsComputersAllowanceInTheSameTick() {
        var host = new ComputerBlockHost(() -> {});
        installLoop(host);
        assertTrue(host.beginFlowTick(10, LIMITS, true));
        assertEquals(4, drain(host, 10));
        host.reset(LIMITS, true);
        installLoop(host);
        assertFalse(host.beginFlowTick(10, LIMITS, true));
        assertEquals(0, drain(host, 10));
        assertEquals(0, host.flowSnapshot().orElseThrow().state().work());
        assertTrue(host.beginFlowTick(11, LIMITS, true));
        assertEquals(4, drain(host, 11));
    }

    @Test void duplicatePhysicalEventTicksDoNotAdvanceTheClockOrAllocateMoreWork() {
        var host = new ComputerBlockHost(() -> {});
        host.install(counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        host.configurePeriodic(2, EMPTY, LIMITS, true);
        host.setPaused(false, LIMITS, true);
        host.tick(20, LIMITS, true, Map.of());
        assertEquals(1, host.snapshot().orElseThrow().periodic().orElseThrow().remainingTicks());
        var first = host.write(); host.tick(20, LIMITS, true, Map.of()); assertEquals(first, host.write());
        host.tick(21, LIMITS, true, Map.of());
        assertEquals(4, host.snapshot().orElseThrow().machine().workUnits());
        assertEquals(integer(0), host.snapshot().orElseThrow().machine().memory().field("count"));
        var working = host.write(); host.tick(21, LIMITS, true, Map.of()); assertEquals(working, host.write());
        host.tick(22, LIMITS, true, Map.of());
        assertEquals(integer(1), host.snapshot().orElseThrow().machine().memory().field("count"));
    }

    @Test void publishingOneRoundCannotReenterAutomaticExecutionThroughItsDirtyCallback() {
        var owner = new ComputerBlockHost[1]; var armed = new boolean[1]; var nested = new long[] {-1};
        owner[0] = new ComputerBlockHost(() -> {
            if (armed[0]) {
                armed[0] = false;
                nested[0] = owner[0].advanceFlowRound(30, Map.of(), LIMITS, true);
            }
        });
        var host = owner[0]; installLoop(host); assertTrue(host.beginFlowTick(30, LIMITS, true));
        armed[0] = true;
        assertEquals(1, host.advanceFlowRound(30, Map.of(), LIMITS, true));
        assertEquals(0, nested[0]);
        assertEquals(1, host.flowSnapshot().orElseThrow().state().work());
        assertEquals(3, drain(host, 30));
        assertEquals(4, host.flowSnapshot().orElseThrow().state().work());
    }

    @Test void hardwareChangesCanOnlyTightenAnAlreadyOpenedActivity() {
        var host = new ComputerBlockHost(() -> {}); installLoop(host);
        var faster = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, 100_000, 10), SAVE_LIMITS));
        var slower = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, 100_000, 1), SAVE_LIMITS));
        assertTrue(host.beginFlowTick(40, LIMITS, true));
        assertEquals(1, host.advanceFlowRound(40, Map.of(), LIMITS, true));
        assertEquals(3, drain(host, 40, faster));
        assertTrue(host.beginFlowTick(41, faster, true));
        assertEquals(10, drain(host, 41, faster));
        assertTrue(host.beginFlowTick(42, faster, true));
        assertEquals(1, host.advanceFlowRound(42, Map.of(), faster, true));
        assertEquals(0, drain(host, 42, slower));
        assertEquals(0, drain(host, 42, faster));
        assertTrue(host.beginFlowTick(43, faster, true));
        assertEquals(10, drain(host, 43, faster));
        assertEquals(25, host.flowSnapshot().orElseThrow().state().work());
    }

    @Test void changingModesCannotRefreshThePhysicalComputersActivity() {
        var host = new ComputerBlockHost(() -> {}); installLoop(host);
        assertTrue(host.beginFlowTick(50, LIMITS, true));
        assertEquals(1, host.advanceFlowRound(50, Map.of(), LIMITS, true));
        host.reset(LIMITS, true);
        host.install(counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        host.begin(EMPTY, LIMITS, true); host.setPaused(false, LIMITS, true);
        var event = host.write(); host.tick(50, LIMITS, true, Map.of());
        assertEquals(event, host.write());
        host.reset(LIMITS, true); installLoop(host);
        assertFalse(host.beginFlowTick(50, LIMITS, true));
        assertEquals(3, drain(host, 50));
        assertTrue(host.beginFlowTick(51, LIMITS, true));
        assertEquals(4, drain(host, 51));
    }

    @Test void pauseUnloadAndSameOwnerReadDoNotRefillTheCurrentAllowance() {
        var host = new ComputerBlockHost(() -> {}); installLoop(host);
        assertTrue(host.beginFlowTick(60, LIMITS, true));
        assertEquals(1, host.advanceFlowRound(60, Map.of(), LIMITS, true));
        host.setPaused(true, LIMITS, true); assertEquals(0, drain(host, 60));
        host.setPaused(false, LIMITS, true); host.unload();
        assertFalse(host.beginFlowTick(60, LIMITS, false));
        assertEquals(0, host.advanceFlowRound(60, Map.of(), LIMITS, false));
        host.read(host.write());
        assertFalse(host.beginFlowTick(60, LIMITS, true));
        assertEquals(3, drain(host, 60));
        assertEquals(4, host.flowSnapshot().orElseThrow().state().work());
        assertFalse(host.beginFlowTick(61, LIMITS, false));
        assertTrue(host.beginFlowTick(100, LIMITS, true));
        assertEquals(4, drain(host, 100));
    }

    @Test void sleepingAdvancesOncePerActiveTickNotPerRoundOrElapsedWorldTime() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Sleep("sleep", "ticks"), new FlowProgram.Forever("loop"),
            new FlowProgram.EndForever("end"))), new DataValue.RecordValue(Map.of("ticks", integer(2))), LIMITS, true);
        host.setPaused(false, LIMITS, true);
        assertTrue(host.beginFlowTick(70, LIMITS, true)); assertEquals(1, drain(host, 70));
        assertEquals(2, host.flowSnapshot().orElseThrow().state().sleepTicks());
        assertFalse(host.beginFlowTick(70, LIMITS, true)); assertEquals(0, drain(host, 70));
        assertTrue(host.beginFlowTick(71, LIMITS, true)); assertEquals(0, drain(host, 71));
        assertEquals(1, host.flowSnapshot().orElseThrow().state().sleepTicks());
        host.setPaused(true, LIMITS, true);
        assertTrue(host.beginFlowTick(72, LIMITS, true)); assertEquals(0, drain(host, 72));
        assertEquals(1, host.flowSnapshot().orElseThrow().state().sleepTicks());
        host.unload(); assertFalse(host.beginFlowTick(73, LIMITS, false));
        host.setPaused(false, LIMITS, true);
        assertTrue(host.beginFlowTick(1000, LIMITS, true)); assertEquals(4, drain(host, 1000));
        assertEquals(0, host.flowSnapshot().orElseThrow().state().sleepTicks());
        assertEquals(5, host.flowSnapshot().orElseThrow().state().work());
    }

    @Test void aFailedPublicationCannotRetryWithFreshWorkInTheSameActivity() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(BiocomputingFlowFixture.calculator(), BiocomputingFlowFixture.inputs(5), LIMITS, true);
        host.setPaused(false, LIMITS, true);
        var old = host.flowSnapshot().orElseThrow(); var raw = host.write();
        long units = ValueNbtCodec.decode(((CompoundTag) raw).getCompound("Data"), LIMITS.archive()).storageUnits();
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(100_000, 1_000_000, units)));
        assertTrue(host.beginFlowTick(80, tight, true));
        assertEquals(0, host.advanceFlowRound(80, Map.of(), tight, true));
        assertFalse(host.loaded()); assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
        assertEquals(raw, host.write()); assertEquals(old, host.flowSnapshot().orElseThrow());
        assertFalse(host.beginFlowTick(80, LIMITS, true)); assertTrue(host.loaded());
        assertEquals(0, drain(host, 80)); assertEquals(old, host.flowSnapshot().orElseThrow());
        assertTrue(host.beginFlowTick(81, LIMITS, true));
        assertEquals(1, host.advanceFlowRound(81, Map.of(), LIMITS, true));
        assertEquals(1, host.flowSnapshot().orElseThrow().state().work());
    }

    @Test void eventPublicationCannotReenterTheStandaloneTickSeam() {
        var owner = new ComputerBlockHost[1]; var armed = new boolean[1];
        owner[0] = new ComputerBlockHost(() -> {
            if (armed[0]) { armed[0] = false; owner[0].tick(LIMITS, true); }
        });
        var host = owner[0]; host.install(counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        host.begin(EMPTY, LIMITS, true); host.setPaused(false, LIMITS, true); armed[0] = true;
        host.tick(90, LIMITS, true, Map.of());
        assertEquals(4, host.snapshot().orElseThrow().machine().workUnits());
        assertEquals(integer(0), host.snapshot().orElseThrow().machine().memory().field("count"));
        host.tick(91, LIMITS, true, Map.of());
        assertEquals(integer(1), host.snapshot().orElseThrow().machine().memory().field("count"));
    }

    @Test void explicitDebugStepDoesNotRefillAnExhaustedAutomaticAllowance() {
        var host = new ComputerBlockHost(() -> {}); installLoop(host);
        assertTrue(host.beginFlowTick(110, LIMITS, true)); assertEquals(4, drain(host, 110));
        host.setPaused(true, LIMITS, true); host.step(LIMITS, true, Map.of());
        assertEquals(5, host.flowSnapshot().orElseThrow().state().work());
        assertTrue(host.flowSnapshot().orElseThrow().state().paused());
        host.setPaused(false, LIMITS, true);
        assertFalse(host.beginFlowTick(110, LIMITS, true)); assertEquals(0, drain(host, 110));
        host.tick(110, LIMITS, true, Map.of());
        assertEquals(5, host.flowSnapshot().orElseThrow().state().work());
        assertTrue(host.beginFlowTick(111, LIMITS, true)); assertEquals(4, drain(host, 111));
        assertEquals(9, host.flowSnapshot().orElseThrow().state().work());
    }

    private static void installLoop(ComputerBlockHost host) {
        var empty = new ProgramArchive(new ProgramDefinition(
            new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of()), Map.of());
        host.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.EndForever("end")), empty), EMPTY, LIMITS, true);
        host.setPaused(false, LIMITS, true);
    }

    private static long drain(ComputerBlockHost host, long tick) {
        return drain(host, tick, LIMITS);
    }
    private static long drain(ComputerBlockHost host, long tick, ComputerHostLimits limits) {
        long spent = 0;
        for (int i = 0; i < 20; i++) {
            long step = host.advanceFlowRound(tick, Map.of(), limits, true);
            if (step == 0) return spent;
            spent += step;
        }
        fail("Host did not enforce a finite allowance"); return spent;
    }
}
