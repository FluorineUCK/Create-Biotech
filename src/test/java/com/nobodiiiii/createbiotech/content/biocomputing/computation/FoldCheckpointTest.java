package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.FoldProgramFixtures.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.FoldGraphTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FoldCheckpointTest {
    @Test void restorationRejectsTamperedFoldProgressTypesReturnsChildrenAndArena() {
        var installed = archive(numbers(1,2), integer(0)).compile(ROOMY);
        var machine = new ComputationMachine(installed); machine.begin(new DataValue.RecordValue(Map.of()));
        boolean checked = false, childChecked = false, completedChecked = false;
        while (machine.report().status() == ComputationMachine.Status.RUNNING) {
            var state = machine.checkpoint();
            for (int i = 0; i < state.frames().size(); i++) {
                if (!(state.frames().get(i) instanceof MachineCheckpoint.FoldState fold)) continue;
                var frames = new ArrayList<>(state.frames());
                for (var invalid : List.of(new MachineCheckpoint.FoldState(-1, fold.accumulator(), fold.returned()),
                    new MachineCheckpoint.FoldState(3, fold.accumulator(), fold.returned()),
                    new MachineCheckpoint.FoldState(fold.index(), new DataValue.TextValue("bad"), fold.returned()),
                    new MachineCheckpoint.FoldState(fold.index(), fold.accumulator(), Optional.of(new DataValue.RecordValue(Map.of()))))) {
                    frames.set(i, invalid); invalid(installed, with(state, frames, state.arenaUnits()));
                }
                frames.set(i, fold);
                invalid(installed, with(state, frames, 0)); checked = true;
                // Charge every other live frame, then omit only the Fold accumulator.
                long otherLive = installed.usage().constantUnits() + installed.usage().initialMemoryUnits()
                    + state.memory().storageUnits();
                for (var live : state.frames()) {
                    if (live instanceof MachineCheckpoint.GraphState graph) {
                        otherLive += graph.inputs().storageUnits();
                        for (var value : graph.values().fields().values()) otherLive += value.storageUnits();
                    } else if (live instanceof MachineCheckpoint.FoldState current && current.returned().isPresent())
                        otherLive += current.returned().orElseThrow().storageUnits();
                }
                var exact = with(state, state.frames(), otherLive + fold.accumulator().storageUnits());
                assertDoesNotThrow(() -> ComputationMachine.restore(installed, exact));
                invalid(installed, with(state, state.frames(), otherLive));
                if (fold.returned().isPresent()) {
                    long withoutReturn = otherLive + fold.accumulator().storageUnits()
                        - fold.returned().orElseThrow().storageUnits();
                    invalid(installed, with(state, state.frames(), withoutReturn));
                }
                if (fold.index() == 0) {
                    frames.set(i, new MachineCheckpoint.FoldState(0, integer(9), fold.returned()));
                    invalid(installed, with(state, frames, state.arenaUnits())); frames.set(i, fold);
                }
                if (i + 1 < frames.size()) {
                    var child = (MachineCheckpoint.GraphState) frames.get(i + 1);
                    frames.set(i + 1, new MachineCheckpoint.GraphState(Optional.of(new ModuleDefinition.Key("sum", 2)), child.cursor(), child.inputs(), child.values()));
                    invalid(installed, with(state, frames, state.arenaUnits()));
                    frames.set(i + 1, new MachineCheckpoint.GraphState(child.module(), child.cursor(), new DataValue.RecordValue(Map.of("item", integer(99), "total", integer(0))), child.values()));
                    invalid(installed, with(state, frames, state.arenaUnits())); frames.set(i + 1, child);
                    frames.set(i, new MachineCheckpoint.FoldState(fold.index(), fold.accumulator(), Optional.of(new DataValue.RecordValue(Map.of("next", integer(1))))));
                    invalid(installed, with(state, frames, state.arenaUnits())); childChecked = true;
                }
                if (fold.index() == 2) {
                    frames.set(i, new MachineCheckpoint.FoldState(2, fold.accumulator(), Optional.of(new DataValue.RecordValue(Map.of("next", integer(3))))));
                    invalid(installed, with(state, frames, state.arenaUnits())); completedChecked = true;
                }
            }
            machine.step();
        }
        assertTrue(checked && childChecked && completedChecked);
    }
    @Test void initialAndEachUpdatedAccumulatorAreChargedAndRuntimeLimitDoesNotTruncate() {
        var installed = archive(numbers(1,2), integer(0)).compile(ROOMY);
        var machine = new ComputationMachine(installed); machine.begin(new DataValue.RecordValue(Map.of()));
        boolean created = false, updated = false;
        while (machine.report().status() == ComputationMachine.Status.RUNNING) {
            var before = machine.checkpoint(); machine.step(); var after = machine.checkpoint();
            var oldFold = before.frames().stream().filter(MachineCheckpoint.FoldState.class::isInstance).map(MachineCheckpoint.FoldState.class::cast).findFirst();
            var newFold = after.frames().stream().filter(MachineCheckpoint.FoldState.class::isInstance).map(MachineCheckpoint.FoldState.class::cast).findFirst();
            if (oldFold.isEmpty() && newFold.isPresent()) { assertEquals(1, after.arenaUnits() - before.arenaUnits()); created = true; }
            if (oldFold.isPresent() && oldFold.get().returned().isPresent()) {
                assertEquals(1, after.arenaUnits() - before.arenaUnits()); updated = true;
            }
        }
        assertTrue(created && updated);
        long peak = machine.report().peakMemoryUnits();
        var limited = archive(numbers(1,2), integer(0)).compile(new ResourceLimits(100, peak - 1, 1));
        var result = run(limited, new DataValue.RecordValue(Map.of()), true).report();
        assertEquals(ComputationMachine.Status.FAILED, result.status());
        assertEquals(MEMORY_LIMIT, result.diagnostic().orElseThrow().code()); assertTrue(result.output().isEmpty());
    }
    private static MachineCheckpoint with(MachineCheckpoint state, List<MachineCheckpoint.FrameState> frames, long arena) {
        return new MachineCheckpoint(state.memory(), state.status(), state.paused(), state.workUnits(), arena, state.output(), state.diagnostic(), frames);
    }
    private static void invalid(ProgramCompiler.InstalledProgram installed, MachineCheckpoint state) {
        assertCode(INVALID_CHECKPOINT, () -> ComputationMachine.restore(installed, state));
    }
}
