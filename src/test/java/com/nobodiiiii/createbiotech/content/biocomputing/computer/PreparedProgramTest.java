package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PreparedProgramTest {
    static final ComputerHostLimits LIMITS = new ComputerHostLimits(new ResourceLimits(1000, 100000, 1),
        new ArchiveLimits(100, 10000, 1000000));
    static ProgramDocument source() {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("wait", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Sleep("sleep", "wait")), new DataValue.RecordValue(Map.of("wait", new DataValue.IntegerValue(0))),
            FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        return workspace.snapshot();
    }
    @Test void preparationDoesNotMutateAnOwnerAndInstallationStartsPausedOnTheExistingOwner() {
        var changes = new java.util.concurrent.atomic.AtomicInteger();
        var owner = new ComputerBlockHost(changes::incrementAndGet);
        var prepared = PreparedProgram.prepare(source(), LIMITS);
        assertEquals(PreparedProgram.InstallationPolicy.RESET_PAUSED, prepared.installationPolicy());
        assertNull(owner.write()); assertEquals(0, changes.get());
        assertEquals(ComputerRuntime.InstallResult.APPLIED,
            owner.installProgram(prepared, ComputerRuntime.MemoryPolicy.RESET, LIMITS, true));
        assertTrue(owner.flowSnapshot().orElseThrow().state().paused());
        assertTrue(owner.flowSnapshot().orElseThrow().state().assembly().isPresent());
        assertEquals(0, owner.flowSnapshot().orElseThrow().state().work());
        var restored = new ComputerBlockHost(() -> {}); restored.read(owner.write()); restored.tick(LIMITS, true);
        assertEquals(owner.flowSnapshot(), restored.flowSnapshot());
    }

    @Test void preparationForLargeHardwareCannotBorrowItsCodeOrRamOnOtherComputers() {
        var prepared = PreparedProgram.prepare(source(), LIMITS);
        for (var hardware : List.of(new ResourceLimits(0, 100000, 1), new ResourceLimits(1000, 0, 1))) {
            var target = new ComputerHostLimits(hardware, LIMITS.archive());
            var owner = new ComputerBlockHost(() -> {});
            assertThrows(ComputationException.class,
                () -> owner.installProgram(prepared, ComputerRuntime.MemoryPolicy.RESET, target, true));
            assertNull(owner.write()); assertTrue(owner.flowSnapshot().isEmpty());
        }
        var independent = new ComputerBlockHost(() -> {});
        independent.installProgram(prepared, ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        assertTrue(independent.flowSnapshot().orElseThrow().state().assembly().isPresent());
    }

    @Test void failedPreparedInstallKeepsThePreviousProgramPcAndBanksWithoutDirtying() {
        var changes = new java.util.concurrent.atomic.AtomicInteger(); var owner = new ComputerBlockHost(changes::incrementAndGet);
        var prepared = PreparedProgram.prepare(source(), LIMITS);
        owner.installProgram(prepared, ComputerRuntime.MemoryPolicy.RESET, LIMITS, true); owner.step(LIMITS, true);
        var saved = owner.flowSnapshot(); var raw = owner.write(); int dirty = changes.get();
        assertThrows(IllegalArgumentException.class,
            () -> owner.installProgram(prepared, ComputerRuntime.MemoryPolicy.PRESERVE, LIMITS, true));
        var tiny = new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(1, 10, 1));
        assertThrows(RuntimeException.class,
            () -> owner.installProgram(prepared, ComputerRuntime.MemoryPolicy.RESET, tiny, true));
        assertEquals(saved, owner.flowSnapshot()); assertEquals(raw, owner.write()); assertEquals(dirty, changes.get());
        var unavailable = new ComputerBlockHost(() -> fail("Unavailable installation cannot publish"));
        assertThrows(ComputerBlockHost.HostException.class,
            () -> unavailable.installProgram(prepared, ComputerRuntime.MemoryPolicy.RESET, LIMITS, false));
        assertNull(unavailable.write());
    }

    @Test void explicitUnsupportedIsaSourceRejectsPreparationRatherThanInstallingLegacyCode() {
        var owner = new ComputerBlockHost(() -> {});
        owner.installProgram(PreparedProgram.prepare(source(), LIMITS), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        owner.step(LIMITS, true); var saved = owner.flowSnapshot(); var raw = owner.write();
        assertEquals(ComputationException.Code.INVALID_GRAPH,
            assertThrows(ComputationException.class, () -> PreparedProgram.prepare(unknownInstructionDocument(), LIMITS)).code());
        assertEquals(saved, owner.flowSnapshot()); assertEquals(raw, owner.write());
        assertTrue(owner.flowSnapshot().orElseThrow().state().assembly().isPresent());
    }

    static ProgramDocument unknownInstructionDocument() {
        var workspace = new ProgramWorkspace();
        // CORRELATION is now a supported opcode; use an actually unknown instruction.
        var bad = new FlowProgram.Procedure("unsupported", new ModuleDefinition.Key("bad", 1),
            Map.of(), Map.of(), Map.of(), FlowBlockRules.NONE,
            Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource("UNSUPPORTED_OPCODE")));
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(
            new FlowProgram(Map.of(), List.of(bad, new FlowProgram.EndProcedure("return"))).withAssembly(),
            new DataValue.RecordValue(Map.of()))));
        return workspace.snapshot();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ComputerRuntime.MemoryPolicy.class)
    void legacyEventInstallationRetainsQueuedPolicyAndUsesTheOriginalOwner(ComputerRuntime.MemoryPolicy policy) {
        var owner = new ComputerBlockHost(() -> {});
        var original = PreparedProgram.prepare(ProgramDocument.of(ComputerRuntimeTest.counter(1)), LIMITS);
        var replacement = PreparedProgram.prepare(ProgramDocument.of(ComputerRuntimeTest.counter(10)), LIMITS);
        assertEquals(PreparedProgram.InstallationPolicy.LEGACY_EVENT, replacement.installationPolicy());
        owner.installProgram(original, ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        var id = owner.snapshot().orElseThrow().id(); owner.begin(new DataValue.RecordValue(Map.of()), LIMITS, true);
        assertEquals(ComputerRuntime.InstallResult.QUEUED, owner.installProgram(replacement, policy, LIMITS, true));
        assertEquals(policy, owner.snapshot().orElseThrow().pending().orElseThrow().policy());
        var queued = owner.write(); var runningOwner = owner;
        assertEquals(ComputationException.Code.BUSY, assertThrows(ComputationException.class,
            () -> runningOwner.installProgram(PreparedProgram.prepare(source(), LIMITS), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true)).code());
        assertEquals(queued, owner.write());
        finish(owner); assertEquals(new DataValue.IntegerValue(1), owner.snapshot().orElseThrow().machine().memory().field("count"));
        assertTrue(owner.snapshot().orElseThrow().pending().isPresent());
        var completed = owner.write(); var restored = new ComputerBlockHost(() -> {}); restored.read(completed);
        assertEquals(completed, restored.write());
        // The existing event activity boundary applies a completed queued installation before execution.
        owner.tick(LIMITS, true); restored.tick(LIMITS, true);
        assertEquals(owner.snapshot(), restored.snapshot()); owner = restored;
        owner.begin(new DataValue.RecordValue(Map.of()), LIMITS, true); finish(owner);
        assertEquals(new DataValue.IntegerValue(policy == ComputerRuntime.MemoryPolicy.PRESERVE ? 11 : 10),
            owner.snapshot().orElseThrow().machine().memory().field("count"));
        assertEquals(id, owner.snapshot().orElseThrow().id()); assertFalse(owner.sequentialMode());
        assertEquals(ComputerRuntime.InstallResult.APPLIED,
            owner.installProgram(original, ComputerRuntime.MemoryPolicy.RESET, LIMITS, true));
        assertEquals(new DataValue.IntegerValue(0), owner.snapshot().orElseThrow().machine().memory().field("count"));
    }
    private static void finish(ComputerBlockHost owner) {
        for (int i = 0; owner.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); owner.step(LIMITS, true);
        }
    }

    @Test void preparationDoesNotGrantAnotherTargetMissingPhysicalPins() {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(new FlowProgram.SignalRead("sample", "p0", "level")),
            new DataValue.RecordValue(Map.of()), FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        var prepared = PreparedProgram.prepare(workspace.snapshot(), LIMITS);
        var noPins = new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), new HardwarePins(List.of(), List.of()));
        var owner = new ComputerBlockHost(() -> fail("Rejected pin mismatch cannot publish"));
        assertThrows(FlowPins.Unavailable.class,
            () -> owner.installProgram(prepared, ComputerRuntime.MemoryPolicy.RESET, noPins, true));
        assertNull(owner.write());
    }
}
