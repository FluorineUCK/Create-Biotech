package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValueEqualityBoundaryTest {
    @Test void forgedModesOffsetsCompletedFlagsParentsAndArenaAreRejected() {
        var installed = ValueEqualityTest.installation(DataType.Scalar.ANY, DataType.Scalar.ANY, 100000);
        var machine = new ComputationMachine(installed); machine.begin(new DataValue.RecordValue(Map.of(
            "left", new DataValue.TextValue("abc"), "right", new DataValue.TextValue("abc"))));
        while (!ValueEqualityHostContinuationTest.atOffset(machine.checkpoint(), "TEXT", 1)) machine.tick();
        var saved = machine.checkpoint(); var progress = ((MachineCheckpoint.ValueEqualityState)saved.frames().getLast()).progress();
        var frame = (DataValue.RecordValue)((DataValue.ListValue)progress.field("frames")).values().getFirst();
        for (var mutation : List.of(replace(frame, "offset", number(-1)), replace(frame, "offset", number(100)),
                replace(frame, "scan", number(1)), replace(frame, "index", number(1)),
                replace(frame, "mode", new DataValue.TextValue("DONE")), replace(frame, "mode", new DataValue.TextValue("WAIT_LIST"))))
            invalid(installed, withProgress(saved, replace(progress, "frames", new DataValue.ListValue(List.of(mutation)))));
        invalid(installed, withProgress(saved, replace(progress, "unequal", new DataValue.BooleanValue(true))));
        invalid(installed, withProgress(saved, new DataValue.RecordValue(Map.of("frames", new DataValue.ListValue(List.of()), "unequal", new DataValue.BooleanValue(true)))));
        var fields = new LinkedHashMap<>(progress.fields()); fields.put("trusted", new DataValue.BooleanValue(true));
        invalid(installed, withProgress(saved, new DataValue.RecordValue(fields)));
        invalid(installed, new MachineCheckpoint(saved.memory(), saved.status(), saved.paused(), saved.workUnits(), 1, saved.output(), saved.diagnostic(), saved.frames()));
        invalid(installed, new MachineCheckpoint(saved.memory(), saved.status(), saved.paused(), saved.workUnits(), saved.arenaUnits(), saved.output(), saved.diagnostic(),
            List.of(new MachineCheckpoint.ValueEqualityState(progress))));
        var frames = new ArrayList<>(saved.frames()); var root = (MachineCheckpoint.GraphState)frames.getFirst();
        frames.set(0, new MachineCheckpoint.GraphState(root.module(), 0, root.inputs(), new DataValue.RecordValue(Map.of())));
        invalid(installed, new MachineCheckpoint(saved.memory(), saved.status(), saved.paused(), saved.workUnits(), saved.arenaUnits(), saved.output(), saved.diagnostic(), frames));
    }
    @Test void coldNestedFailureUnwindRestoresExactly() {
        DataValue left = new DataValue.IntegerValue(1), right = new DataValue.IntegerValue(2);
        for (int i = 0; i < 24; i++) { left = new DataValue.ListValue(List.of(left)); right = new DataValue.ListValue(List.of(right)); }
        assertEquals(ValueEqualityTest.run(left, right, false), ValueEqualityTest.run(left, right, true));
    }
    private static DataValue.IntegerValue number(int n) { return new DataValue.IntegerValue(n); }
    private static DataValue.RecordValue replace(DataValue.RecordValue value, String key, DataValue entry) {
        var fields = new LinkedHashMap<>(value.fields()); fields.put(key, entry); return new DataValue.RecordValue(fields);
    }
    private static MachineCheckpoint withProgress(MachineCheckpoint saved, DataValue.RecordValue progress) {
        return ValueEqualityHostContinuationTest.withState(saved, new MachineCheckpoint.ValueEqualityState(progress));
    }
    private static void invalid(ProgramCompiler.InstalledProgram installed, MachineCheckpoint saved) {
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> ComputationMachine.restore(installed, saved)).code());
    }
}
