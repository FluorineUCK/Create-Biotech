package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class FlowTaskSlotsTest {
    private static final ModuleDefinition.Key COPY = new ModuleDefinition.Key("copy", 1);
    private static final ComputerHostLimits LIMITS = limits(100_000, 3);
    private static ComputerHostLimits limits(long memory, int slots) {
        return new ComputerHostLimits(new ResourceLimits(1000, memory, 1), FlowArchiveTest.ARCHIVE,
            MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, slots);
    }
    private static DataValue.RecordValue value(long n) { return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(n))); }
    private static FlowProgram program() {
        return new FlowProgram(Map.of("value", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("main_wait", "value"),
            new FlowProgram.Procedure("definition", COPY, Map.of("value", DataType.Scalar.INTEGER),
                Map.of("result", DataType.Scalar.INTEGER), Map.of("result", "value")), new FlowProgram.EndProcedure("return")));
    }
    private static ComputerBlockHost installed() {
        return installed(1000);
    }
    private static ComputerBlockHost installed(long wait) {
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(program(), value(wait), LIMITS, true);
        owner.step(LIMITS, true); return owner;
    }
    private static FlowBlockHost.Saved saved(ComputerBlockHost owner) { return owner.flowSnapshot().orElseThrow(); }
    private static ComputerBlockHost reload(CompoundTag data, ComputerHostLimits limits) {
        var owner = new ComputerBlockHost(() -> {}); owner.read(data); owner.tick(limits, true); return owner;
    }
    private static UUID complete(ComputerBlockHost owner, long input) {
        UUID id = owner.startFlowTask(COPY, value(input), LIMITS, true); owner.step(LIMITS, true);
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowTask(id).orElseThrow().status()); return id;
    }
    private static CompoundTag disk(ComputerBlockHost owner) { return (CompoundTag) owner.write(); }

    @Test void repeatedRealAdmissionAndClaimReuseOneSlotWithoutReusingItsIncarnationOrKeepingResults() {
        var owner = installed(); var main = saved(owner).state(); UUID previous = null; long retainedUnits = -1;
        for (int i = 0; i < 48; i++) {
            UUID id = complete(owner, i);
            var slots = saved(owner).tasks().slots().entries();
            assertEquals(List.of(new FlowTaskSlots.Slot(id, i, false)), slots);
            assertEquals(new DataValue.IntegerValue(i), owner.flowTask(id).orElseThrow().locals().field("result"));
            var activeDisk = disk(owner);
            owner = reload(activeDisk, LIMITS); assertTrue(owner.loaded()); assertEquals(activeDisk, disk(owner));
            if (previous != null) {
                UUID stale = previous; var current = owner;
                assertEquals(ComputationException.Code.MISSING_REFERENCE, assertThrows(ComputationException.class,
                    () -> current.claimFlowTask(stale, LIMITS, true)).code());
                assertEquals(activeDisk, disk(owner), "Old result handles cannot release a later slot occupant");
            }
            owner.claimFlowTask(id, LIMITS, true);
            assertTrue(saved(owner).tasks().contexts().isEmpty());
            assertEquals(List.of(new FlowTaskSlots.Slot(id, i, true)), saved(owner).tasks().slots().entries());
            long units = saved(owner).tasks().metadataUnits();
            if (retainedUnits < 0) retainedUnits = units;
            assertEquals(retainedUnits, units, "Sequential tasks must not grow an unbounded UUID history");
            var retiredDisk = disk(owner); owner = reload(retiredDisk, LIMITS);
            assertEquals(retiredDisk, disk(owner)); assertEquals(main, saved(owner).state()); previous = id;
        }
    }
    @Test void retiredSlotsStillCostMemoryAndSurviveHardwareShrinkThenReuse() {
        var owner = installed(); UUID first = complete(owner, 42); owner.claimFlowTask(first, LIMITS, true);
        var state = saved(owner); var disk = disk(owner);
        long exact = state.initialInputs().storageUnits() + state.control().storageUnits() + state.tasks().metadataUnits()
            + FlowRuntime.restore(state.program(), state.state(), LIMITS.hardware()).retainedUnits();
        var tight = reload(disk, limits(exact - 1, 1));
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, tight.diagnostic().orElseThrow().code());
        assertEquals(disk, disk(tight), "Resource rejection must not erase retirement history");
        var smaller = reload(disk, limits(exact, 1)); assertTrue(smaller.loaded());
        assertEquals(state, saved(smaller));
        assertEquals(ComputationException.Code.BUSY, assertThrows(ComputationException.class,
            () -> smaller.startFlowTask(COPY, value(7), limits(exact, 1), true)).code());
        assertEquals(disk, disk(smaller));
        UUID second = smaller.startFlowTask(COPY, value(7), LIMITS, true);
        assertEquals(List.of(new FlowTaskSlots.Slot(second, 1, false)), saved(smaller).tasks().slots().entries());
    }
    @Test void claimingAnEarlierResultDoesNotRenumberOrRestartAnotherTask() {
        var owner = installed(); UUID a = owner.startFlowTask(COPY, value(1), LIMITS, true);
        UUID b = owner.startFlowTask(COPY, value(2), LIMITS, true); owner.step(LIMITS, true);
        var beforeB = owner.flowTask(b).orElseThrow();
        owner.claimFlowTask(a, LIMITS, true); UUID c = owner.startFlowTask(COPY, value(3), LIMITS, true);
        assertEquals(List.of(new FlowTaskSlots.Slot(c, 1, false), new FlowTaskSlots.Slot(b, 0, false)), saved(owner).tasks().slots().entries());
        assertEquals(beforeB, owner.flowTask(b).orElseThrow());
        owner = reload(disk(owner), LIMITS); assertTrue(owner.loaded()); owner.step(LIMITS, true);
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowTask(b).orElseThrow().status());
        assertEquals(0, owner.flowTask(c).orElseThrow().work(), "Identity allocation must not replace the persisted fair execution cursor");
    }
    @Test void resetAndProgramReplacementClearSlotHistoryOnlyWithANewProgramGeneration() {
        var owner = installed(1); UUID first = complete(owner, 1); owner.claimFlowTask(first, LIMITS, true);
        var before = saved(owner); owner.reset(LIMITS, true);
        assertEquals(before.control().generation() + 1, saved(owner).control().generation());
        assertEquals(before.state().computer(), saved(owner).state().computer());
        assertNotEquals(before.state().flow(), saved(owner).state().flow()); assertEquals(FlowTasks.EMPTY, saved(owner).tasks());
        owner.step(LIMITS, true); UUID next = complete(owner, 2); owner.claimFlowTask(next, LIMITS, true);
        owner.step(LIMITS, true); owner.step(LIMITS, true);
        assertEquals(FlowRuntime.Status.COMPLETE, saved(owner).state().status());
        var completed = saved(owner); owner.installFlow(program(), value(1), LIMITS, true);
        assertEquals(completed.control().generation() + 1, saved(owner).control().generation());
        assertEquals(FlowTasks.EMPTY, saved(owner).tasks());
    }
    @Test void schema15MigrationDoesNotExecuteTasksAndNewSlotFieldsCannotBeSilentlyDowngraded() {
        var owner = installed(); UUID a = owner.startFlowTask(COPY, value(41), LIMITS, true);
        UUID b = owner.startFlowTask(COPY, value(42), LIMITS, true);
        var original = saved(owner); var encoded = disk(owner);
        var data = (DataValue.RecordValue) ValueNbtCodec.decode(encoded.getCompound("Data"), LIMITS.archive());
        var tasks = new LinkedHashMap<>(((DataValue.RecordValue)data.field("tasks")).fields()); tasks.remove("slots");
        var legacy = encoded.copy(); legacy.putInt(FlowBlockNbtCodec.MARKER, 15);
        legacy.put("Data", ValueNbtCodec.encode(with(data, "tasks", new DataValue.RecordValue(tasks)), LIMITS.archive()));
        var restored = reload(legacy, LIMITS); assertTrue(restored.loaded()); assertEquals(original, saved(restored));
        assertEquals(legacy, disk(restored), "Reading an old save does not rewrite or run it");
        restored.step(LIMITS, true); assertEquals(16, disk(restored).getInt(FlowBlockNbtCodec.MARKER));
        assertEquals(List.of(new FlowTaskSlots.Slot(a, 0, false), new FlowTaskSlots.Slot(b, 0, false)), saved(restored).tasks().slots().entries());
        var downgraded = encoded.copy(); downgraded.putInt(FlowBlockNbtCodec.MARKER, 15);
        assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(downgraded, LIMITS.archive()));
        var broken = reload(downgraded, LIMITS);
        assertEquals(ComputerBlockHost.Code.INVALID_SAVE, broken.diagnostic().orElseThrow().code()); assertEquals(downgraded, disk(broken));
    }
    @Test void exhaustedIncarnationRejectsAdmissionWithoutChangingOwnerOrExistingTask() {
        var owner = installed(); UUID old = complete(owner, 1); owner.claimFlowTask(old, LIMITS, true);
        var base = saved(owner); var exhausted = new FlowTasks(List.of(), Optional.empty(),
            new FlowTaskSlots(List.of(new FlowTaskSlots.Slot(old, Long.MAX_VALUE, true))));
        var data = FlowBlockNbtCodec.encode(new FlowBlockHost.Saved(base.program(), base.initialInputs(), base.state(),
            base.receipts(), base.control(), exhausted), LIMITS.archive());
        var restored = reload(data, LIMITS); assertTrue(restored.loaded());
        assertEquals(ComputationException.Code.ARITHMETIC_OVERFLOW, assertThrows(ComputationException.class,
            () -> restored.startFlowTask(COPY, value(2), LIMITS, true)).code());
        assertTrue(restored.loaded()); assertEquals(data, disk(restored));
    }
    @Test void corruptSlotOwnershipCannotHideDropOrReassignAStoredContext() {
        var owner = installed(); owner.startFlowTask(COPY, value(42), LIMITS, true);
        var disk = disk(owner); var data = (DataValue.RecordValue) ValueNbtCodec.decode(disk.getCompound("Data"), LIMITS.archive());
        var tasks = (DataValue.RecordValue) data.field("tasks");
        var slot = (DataValue.RecordValue) ((DataValue.ListValue)tasks.field("slots")).values().getFirst();
        var invalid = List.of(new DataValue.ListValue(List.of()), new DataValue.ListValue(List.of(slot, slot)),
            new DataValue.ListValue(List.of(with(slot, "retired", new DataValue.BooleanValue(true)))),
            new DataValue.ListValue(List.of(with(slot, "incarnation", new DataValue.IntegerValue(-1)))),
            new DataValue.ListValue(List.of(with(slot, "flow", new DataValue.TextValue(UUID.randomUUID().toString())))),
            new DataValue.ListValue(List.of(with(slot, "flow", new DataValue.TextValue(saved(owner).state().flow().toString())))),
            new DataValue.ListValue(List.of(with(slot, "unknown", DataValue.NullValue.INSTANCE))));
        for (var corruptSlots : invalid) {
            var corrupt = disk.copy(); corrupt.put("Data", ValueNbtCodec.encode(with(data, "tasks", with(tasks, "slots", corruptSlots)), LIMITS.archive()));
            assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(corrupt, LIMITS.archive()));
            var restored = reload(corrupt, LIMITS);
            assertEquals(ComputerBlockHost.Code.INVALID_SAVE, restored.diagnostic().orElseThrow().code());
            assertEquals(corrupt, disk(restored));
        }
    }
    private static DataValue.RecordValue with(DataValue.RecordValue source, String key, DataValue value) {
        var fields = new LinkedHashMap<>(source.fields()); fields.put(key, value); return new DataValue.RecordValue(fields);
    }
}
