package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static com.nobodiiiii.createbiotech.content.biocomputing.computer.EventEntryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerTerminalLocalEntryTest {
    @Test void localNamedEntryCommitsItsOwnCellAndNeverFallsBackToRoot() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), program(false), HARDWARE);
        runtime.beginLocal("message", input("amount", 9));
        assertEquals(integer(0), runtime.memory().field("n"), "event writes commit only at success");
        finish(runtime);
        assertEquals(integer(9), runtime.memory().field("n"));
        runtime.reset();
        assertThrows(IllegalArgumentException.class, () -> runtime.beginLocal("missing", EMPTY));
        assertEquals(integer(0), runtime.memory().field("n"));
    }

    @Test void rejectedLocalBeginIsFailStopAndDoesNotApplyQueuedInstallationOrUnpause() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), program(false), HARDWARE);
        runtime.beginLocal("message", input("amount", 4)); runtime.setPaused(true);
        var running = runtime.snapshot();
        assertThrows(ComputationException.class, () -> runtime.beginLocal("message", input("amount", 8)));
        assertEquals(running, runtime.snapshot());
        assertTrue(runtime.paused());
        assertEquals(ComputerRuntime.InstallResult.QUEUED,
            runtime.requestInstall(program(false), ComputerRuntime.MemoryPolicy.PRESERVE));
        var queued = runtime.snapshot();
        assertThrows(ComputationException.class, () -> runtime.beginLocal("message", input("amount", 8)));
        assertEquals(queued, runtime.snapshot(), "a button must not realize a queued installation");
    }

    @Test void hostRejectsSpeculativelyAndNamedContinuationSurvivesSaveRestore() {
        var host = new ComputerBlockHost(() -> {});
        host.install(program(false), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        var before = host.snapshot();
        assertThrows(ComputationException.class, () -> host.beginLocal("message", input("wrong", 1), LIMITS, true));
        assertEquals(before, host.snapshot()); assertTrue(host.loaded()); assertTrue(host.diagnostic().isEmpty());
        host.beginLocal("message", input("amount", 12), LIMITS, true); host.step(LIMITS, true);
        var midway = host.snapshot().orElseThrow(); assertEquals(Optional.of("message"), midway.activeEntry());
        assertThrows(ComputationException.class, () -> host.beginLocal("message", input("amount", 13), LIMITS, true));
        assertEquals(midway, host.snapshot().orElseThrow()); assertTrue(host.loaded()); assertTrue(host.diagnostic().isEmpty());
        var loaded = new ComputerBlockHost(() -> {}); loaded.read(host.write()); loaded.observe(LIMITS, true);
        assertEquals(midway, loaded.snapshot().orElseThrow());
        for (int i = 0; i < 100 && loaded.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++)
            loaded.step(LIMITS, true);
        assertEquals(integer(12), loaded.snapshot().orElseThrow().machine().memory().field("n"));
    }
}
