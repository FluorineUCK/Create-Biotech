package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class PeriodicHostTest {
    private static final ComputerHostLimits LIMITS = new ComputerHostLimits(new ResourceLimits(1000, 1_000_000, 100), SAVE_LIMITS);
    @Test void idleHostCountsWithoutAMenuAndRestoresRemainingTicksBeforeStarting() {
        var changes = new AtomicInteger(); var ref = new ComputerBlockHost[1];
        var host = ref[0] = new ComputerBlockHost(() -> {
            assertEquals(ref[0].snapshot().orElseThrow(), ComputerNbtCodec.decode((CompoundTag)ref[0].write(), SAVE_LIMITS));
            changes.incrementAndGet();
        });
        host.install(counter(3), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        host.configurePeriodic(2, EMPTY, LIMITS, true); host.tick(LIMITS, true);
        assertEquals(1, host.snapshot().orElseThrow().periodic().orElseThrow().remainingTicks());
        assertEquals(integer(0), host.snapshot().orElseThrow().machine().memory().field("count"));
        var saved = host.write(); int dirty = changes.get();
        host.tick(LIMITS, false); host.tick(LIMITS, false); assertEquals(saved, host.write()); assertEquals(dirty, changes.get());
        var restored = new ComputerBlockHost(() -> {}); restored.read(saved);
        restored.tick(LIMITS, true);
        assertEquals(integer(3), restored.snapshot().orElseThrow().machine().memory().field("count"));
        restored.tick(LIMITS, true); restored.tick(LIMITS, true);
        assertEquals(integer(6), restored.snapshot().orElseThrow().machine().memory().field("count"));
        restored.disablePeriodic(LIMITS, true); var disabled = restored.write();
        for (int i = 0; i < 10; i++) restored.tick(LIMITS, true);
        assertEquals(disabled, restored.write()); assertTrue(restored.snapshot().orElseThrow().periodic().isPresent());
    }
    @Test void unsaveableConfigurationRollsBackTimerInputsAndRamTogether() {
        var graph = new ComputationGraph(Map.of("data", DataType.Scalar.ANY), Map.of("n", new ComputationGraph.Constant(integer(1))), Map.of("out", "n"));
        var program = new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
        var limits = new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(10_000, 2000, 100_000));
        var host = new ComputerBlockHost(() -> {}); host.install(program, ComputerRuntime.MemoryPolicy.RESET, limits, true);
        var small = new DataValue.RecordValue(Map.of("data", integer(1)));
        host.configurePeriodic(5, small, limits, true); var before = host.snapshot(); var saved = host.write();
        var large = new DataValue.RecordValue(Map.of("data", new DataValue.TextValue("x".repeat(4000))));
        assertThrows(ComputerBlockHost.HostException.class, () -> host.configurePeriodic(1, large, limits, true));
        assertEquals(saved, host.write()); assertEquals(before, host.snapshot());
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
    }
    @Test void invalidCommandsAndUnavailableOwnersCannotReplaceSavedSchedule() {
        var host = new ComputerBlockHost(() -> {}); host.install(counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        host.configurePeriodic(5, EMPTY, LIMITS, true); var before = host.write();
        assertThrows(RuntimeException.class, () -> host.configurePeriodic(0, EMPTY, LIMITS, true));
        assertEquals(before, host.write());
        assertThrows(ComputerBlockHost.HostException.class, () -> host.disablePeriodic(LIMITS, false));
        assertEquals(before, host.write());
        host.setPaused(true, LIMITS, true); var paused = host.write();
        host.tick(LIMITS, true); host.step(LIMITS, true); assertEquals(paused, host.write());
    }
}
