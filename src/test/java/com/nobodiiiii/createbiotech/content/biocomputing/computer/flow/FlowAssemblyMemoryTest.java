package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowAssemblyMemoryTest {
    static FlowProgram.Memory mem(String source, FlowProgram.MemoryKind kind, String cell, String local) { return new FlowProgram.Memory(source, kind, cell, local); }
    static FlowProgram withSteps(List<FlowProgram.Step> steps) { return new FlowProgram(program().inputs(), steps, program().expressions()).withAssembly(); }
    static ComputerBlockHost install(FlowProgram program) {
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(program, input(), FlowAssemblyTasksTest.LIMITS, true); return owner;
    }
    static DataValue.RecordValue obj(Object... pairs) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) fields.put((String)pairs[i], pairs[i + 1] instanceof Boolean b
            ? new DataValue.BooleanValue(b) : new DataValue.TextValue((String)pairs[i + 1]));
        return new DataValue.RecordValue(fields);
    }
    static DataValue.RecordValue input() { return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(42))); }
    static FlowProgram program() {
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))), List.of()), Map.of());
        var encoded = (DataValue.RecordValue)FlowProgramCodec.encode(new FlowProgram(Map.of("value", DataType.Scalar.INTEGER), List.of(), archive).withAssembly());
        var fields = new LinkedHashMap<>(encoded.fields());
        fields.put("steps", new DataValue.ListValue(List.of(
            op("begin", "start", "", ""), op("write", "stage", "counter", "value"),
            op("read", "old", "counter", "old"), op("commit", "publish", "", "result"),
            op("begin", "again", "", ""), op("read", "new", "counter", "current"), op("rollback", "end", "", ""))));
        return FlowProgramCodec.decode(new DataValue.RecordValue(fields));
    }
    static DataValue op(String operation, String source, String cell, String local) {
        return obj("kind", "memory", "source", source, "operation", operation, "cell", cell, "local", local);
    }
    @Test void publicOwnerUsesOldSnapshotAndPublishesAtomicallyAcrossEverySaveBoundary() {
        var limits = FlowAssemblyTasksTest.LIMITS;
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(program(), input(), limits, true);
        for (int i = 0; i < 20 && owner.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) {
            owner.step(limits, true); owner = FlowAssemblyTasksTest.restored(owner);
            assertNotEquals(FlowRuntime.Status.FAILED, owner.flowSnapshot().orElseThrow().state().status(), () -> "Unexpected transaction fault");
        }
        var state = owner.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE, state.status());
        assertEquals(new DataValue.IntegerValue(0), state.locals().fields().get("old"));
        assertEquals(new DataValue.IntegerValue(42), state.locals().fields().get("current"));
        assertEquals(obj("ok", true, "code", "committed"), state.locals().fields().get("result"));
        assertEquals(9, state.work(), "Commit pays start, one cell preparation and publication separately");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void twoPrivateTasksCompeteThroughExplicitCellsWithoutBlockingTheSleepingMain(boolean structured) {
        var type = Map.<String,DataType>of("value", DataType.Scalar.INTEGER);
        var resultType = com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemory.RESULT_TYPE;
        var key = new ModuleDefinition.Key("store", 1);
        var owner = install(withSteps(List.of(new FlowProgram.Sleep("main_sleep", "value"),
            new FlowProgram.Procedure("definition", key, type, Map.of("result", resultType), Map.of("result", "result")),
            structured ? new FlowProgram.Atomic("begin", "result") : mem("begin", FlowProgram.MemoryKind.BEGIN, "", ""),
            mem("read", FlowProgram.MemoryKind.READ, "counter", "old"), mem("write", FlowProgram.MemoryKind.WRITE, "counter", "value"),
            structured ? new FlowProgram.EndAtomic("commit") : mem("commit", FlowProgram.MemoryKind.COMMIT, "", "result"),
            new FlowProgram.EndProcedure("return"))));
        var limits = FlowAssemblyTasksTest.LIMITS; owner.step(limits, true);
        var main = owner.flowSnapshot().orElseThrow().state();
        var a = owner.startFlowTask(key, new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(11))), limits, true);
        var b = owner.startFlowTask(key, new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(22))), limits, true);
        for (int i = 0; i < 16; i++) { owner.step(limits, true); owner = FlowAssemblyTasksTest.restored(owner); }
        assertEquals(main, owner.flowSnapshot().orElseThrow().state());
        assertEquals(obj("ok", true, "code", "committed"), owner.flowTask(a).orElseThrow().locals().field("result"));
        assertEquals(obj("ok", false, "code", "write_conflict"), owner.flowTask(b).orElseThrow().locals().field("result"));
        assertEquals(new DataValue.IntegerValue(11), owner.flowSnapshot().orElseThrow().memory().cells().field("counter"));
        assertTrue(owner.flowSnapshot().orElseThrow().memory().owners().isEmpty());
    }
    @Test void sleepOrRendezvousCannotCarryAnOpenTransactionAndDiscardOnlyUncommittedWrites() {
        for (var wait : List.<FlowProgram.Step>of(new FlowProgram.Sleep("wait", "value"),
                new FlowProgram.Read("wait", "x0", "received", new DataType.RecordType(Map.of())))) {
            var owner = install(withSteps(List.of(mem("begin", FlowProgram.MemoryKind.BEGIN, "", ""),
                mem("write", FlowProgram.MemoryKind.WRITE, "counter", "value"), wait)));
            for (int i = 0; i < 3; i++) owner.step(FlowAssemblyTasksTest.LIMITS, true);
            owner = FlowAssemblyTasksTest.restored(owner);
            var saved = owner.flowSnapshot().orElseThrow();
            assertEquals(FlowRuntime.Status.FAILED, saved.state().status());
            assertEquals(ComputationException.Code.INVALID_ARGUMENT, saved.state().diagnostic().orElseThrow().code());
            assertEquals(new DataValue.IntegerValue(0), saved.memory().cells().field("counter"));
            assertTrue(saved.memory().owners().isEmpty()); assertTrue(saved.receipts().isEmpty());
        }
    }
    @Test void completingWithAnOpenTransactionIsAFaultNotAnImplicitCommit() {
        var owner = install(withSteps(List.of(mem("begin", FlowProgram.MemoryKind.BEGIN, "", ""))));
        owner.step(FlowAssemblyTasksTest.LIMITS, true); owner = FlowAssemblyTasksTest.restored(owner);
        assertEquals(FlowRuntime.Status.FAILED, owner.flowSnapshot().orElseThrow().state().status());
        assertTrue(owner.flowSnapshot().orElseThrow().memory().owners().isEmpty());
    }
    @Test void anotherComputerCannotObserveThisBanksWritesAndResetRestoresOnlyItsInitialCells() {
        var owner = install(program()); var other = install(program()); var otherBefore = other.write();
        for (int i = 0; i < 9; i++) owner.step(FlowAssemblyTasksTest.LIMITS, true);
        assertEquals(new DataValue.IntegerValue(42), owner.flowSnapshot().orElseThrow().memory().cells().field("counter"));
        assertEquals(otherBefore, other.write());
        owner.reset(FlowAssemblyTasksTest.LIMITS, true);
        owner = FlowAssemblyTasksTest.restored(owner);
        assertEquals(new DataValue.IntegerValue(0), owner.flowSnapshot().orElseThrow().memory().cells().field("counter"));
    }
    @Test void oldOwnerVersionsCannotSilentlyReinitializeTransactionMemory() {
        var owner = install(program()); owner.step(FlowAssemblyTasksTest.LIMITS, true);
        var raw = (net.minecraft.nbt.CompoundTag)owner.write(); raw.putInt(FlowBlockNbtCodec.MARKER, 27);
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(FlowAssemblyTasksTest.LIMITS, true);
        assertFalse(restored.loaded()); assertEquals(raw, restored.write()); assertTrue(restored.diagnostic().isPresent());
    }
    static long ram(FlowBlockHost.Saved saved) {
        return saved.initialInputs().storageUnits() + saved.control().storageUnits() + saved.memory().storageUnits()
            + FlowRuntime.restore(saved.program(), saved.state(), FlowAssemblyTasksTest.LIMITS.hardware()).retainedUnits();
    }
    @Test void aCommitCannotPublishItsBankBeforeAllocatingTheResultAndRegisterProjection() {
        String result = "result".repeat(100);
        var owner = install(withSteps(List.of(mem("begin", FlowProgram.MemoryKind.BEGIN, "", ""),
            mem("write", FlowProgram.MemoryKind.WRITE, "counter", "value"), mem("commit", FlowProgram.MemoryKind.COMMIT, "", result))));
        var limits = FlowAssemblyTasksTest.LIMITS;
        for (int i = 0; i < 4; i++) owner.step(limits, true);
        var before = owner.flowSnapshot().orElseThrow(); var raw = owner.write(); owner.step(limits, true);
        var after = owner.flowSnapshot().orElseThrow(); assertTrue(ram(after) > ram(before));
        var tight = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            new ResourceLimits(limits.hardware().programUnits(), ram(after) - 1, 1), limits.archive(), limits.mailbox(), limits.pins(), limits.serial(), limits.taskSlots());
        var limited = new ComputerBlockHost(() -> {}); limited.read(raw); limited.tick(tight, true); assertTrue(limited.loaded());
        limited.step(tight, true); assertTrue(limited.loaded());
        var rejected = limited.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.FAILED, rejected.state().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, rejected.state().diagnostic().orElseThrow().code());
        assertEquals(new DataValue.IntegerValue(0), rejected.memory().cells().field("counter"));
        assertFalse(rejected.state().locals().fields().containsKey(result)); assertTrue(rejected.memory().owners().isEmpty());
    }
    @Test void archiveFailureRetainsTheCompletePreCommitSaveAndCanResumeAfterCapacityReturns() {
        String result = "result".repeat(100);
        var owner = install(withSteps(List.of(mem("begin", FlowProgram.MemoryKind.BEGIN, "", ""),
            mem("write", FlowProgram.MemoryKind.WRITE, "counter", "value"), mem("commit", FlowProgram.MemoryKind.COMMIT, "", result))));
        var limits = FlowAssemblyTasksTest.LIMITS;
        for (int i = 0; i < 4; i++) owner.step(limits, true);
        var raw = (net.minecraft.nbt.CompoundTag)owner.write(); owner.step(limits, true);
        var completed = (net.minecraft.nbt.CompoundTag)owner.write();
        long capacity = ValueNbtCodec.decode(completed.getCompound("Data"), limits.archive()).storageUnits() - 1;
        assertTrue(ValueNbtCodec.decode(raw.getCompound("Data"), limits.archive()).storageUnits() <= capacity);
        var tight = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(limits.hardware(),
            new ArchiveLimits(100_000, 1_000_000, capacity), limits.mailbox(), limits.pins(), limits.serial(), limits.taskSlots());
        var limited = new ComputerBlockHost(() -> {}); limited.read(raw); limited.tick(tight, true); assertTrue(limited.loaded());
        assertThrows(ComputerBlockHost.HostException.class, () -> limited.step(tight, true)); assertEquals(raw, limited.write());
        assertFalse(limited.loaded(), "Archive rejection must be observable, not silently retried every tick");
        assertTrue(limited.diagnostic().isPresent());
        var recovered = new ComputerBlockHost(() -> {}); recovered.read(limited.write()); recovered.tick(limits, true); recovered.step(limits, true);
        assertEquals(owner.flowSnapshot().orElseThrow(), recovered.flowSnapshot().orElseThrow());
    }
    @Test void aMissingTransactionCannotTurnAnAlreadyStartedSnapshotIntoAnEmptyBankOnReload() {
        var owner = install(program()); owner.step(FlowAssemblyTasksTest.LIMITS, true);
        var saved = owner.flowSnapshot().orElseThrow(); var raw = (net.minecraft.nbt.CompoundTag)owner.write();
        var data = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), FlowAssemblyTasksTest.LIMITS.archive());
        var fields = new LinkedHashMap<>(data.fields()); fields.put("memory", saved.memory().rollback(saved.state().flow()).value());
        raw.put("Data", ValueNbtCodec.encode(new DataValue.RecordValue(fields), FlowAssemblyTasksTest.LIMITS.archive()));
        var copy = new ComputerBlockHost(() -> {}); copy.read(raw); copy.tick(FlowAssemblyTasksTest.LIMITS, true);
        assertFalse(copy.loaded()); assertEquals(raw, copy.write()); assertTrue(copy.diagnostic().isPresent());
    }
    @Test void declaringCellsDoesNotBypassOppositeDirectionXBusTaskRejection() {
        var packet = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER)); var key = new ModuleDefinition.Key("send", 1);
        var p = withSteps(List.of(new FlowProgram.Read("receive", "x0", "packet", packet),
            new FlowProgram.Procedure("definition", key, Map.of("packet", packet), Map.of(), Map.of()),
            new FlowProgram.Write("write", "x0", "packet"), new FlowProgram.EndProcedure("return")));
        var owner = install(p); var limits = FlowAssemblyTasksTest.LIMITS; owner.step(limits, true);
        var main = owner.flowSnapshot().orElseThrow().state();
        var task = owner.startFlowTask(key, new DataValue.RecordValue(Map.of("packet", input())), limits, true);
        owner.step(limits, true); owner = FlowAssemblyTasksTest.restored(owner);
        assertEquals(main, owner.flowSnapshot().orElseThrow().state());
        assertEquals(ComputationException.Code.WRITE_CONFLICT, owner.flowTask(task).orElseThrow().diagnostic().orElseThrow().code());
    }
    @Test void aSynchronousCallKeepsItsTasksTransactionButNotItsCallersRegisters() {
        var key = new ModuleDefinition.Key("stage", 1);
        var owner = install(withSteps(List.of(mem("begin", FlowProgram.MemoryKind.BEGIN, "", ""),
            new FlowProgram.Invoke("invoke", key, new FlowCallBindings(Map.of("argument", "value"), Map.of())),
            mem("commit", FlowProgram.MemoryKind.COMMIT, "", "result"),
            new FlowProgram.Procedure("definition", key, Map.of("argument", DataType.Scalar.INTEGER), Map.of(), Map.of()),
            mem("write", FlowProgram.MemoryKind.WRITE, "counter", "argument"), new FlowProgram.EndProcedure("return"))));
        boolean inside = false;
        for (int i = 0; i < 25 && owner.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) {
            owner.step(FlowAssemblyTasksTest.LIMITS, true); owner = FlowAssemblyTasksTest.restored(owner);
            var state = owner.flowSnapshot().orElseThrow().state();
            if (!state.assembly().orElseThrow().calls().orElseThrow().frames().isEmpty()) {
                inside = true; assertTrue(state.assembly().orElseThrow().transaction().orElseThrow());
                assertFalse(state.locals().fields().containsKey("value"));
            }
        }
        assertTrue(inside); var saved = owner.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, saved.state().status());
        assertFalse(saved.state().assembly().orElseThrow().transaction().orElseThrow());
        assertEquals(new DataValue.IntegerValue(42), saved.memory().cells().field("counter"));
    }
    @Test void preparationProgressMustMatchTheExactPaidInstructionAfterRestore() {
        var owner = install(program()); var limits = FlowAssemblyTasksTest.LIMITS;
        for (int i = 0; i < 4; i++) owner.step(limits, true);
        var saved = owner.flowSnapshot().orElseThrow(); var raw = (net.minecraft.nbt.CompoundTag)owner.write();
        var data = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), limits.archive());
        var fields = new LinkedHashMap<>(data.fields()); fields.put("memory", saved.memory().prepareCell(saved.state().flow(), 0).value());
        raw.put("Data", ValueNbtCodec.encode(new DataValue.RecordValue(fields), limits.archive()));
        var copy = new ComputerBlockHost(() -> {}); copy.read(raw); copy.tick(limits, true);
        assertFalse(copy.loaded()); assertEquals(raw, copy.write());
    }
    @Test void memoryInstructionsReserveTheSameRepresentableFaultCycleAsXBus() {
        var p = withSteps(List.of(new FlowProgram.Forever("loop"), mem("begin", FlowProgram.MemoryKind.BEGIN, "", ""),
            mem("rollback", FlowProgram.MemoryKind.ROLLBACK, "", ""), new FlowProgram.EndForever("repeat"),
            new FlowProgram.Read("unused_xbus", "x0", "packet", new DataType.RecordType(Map.of()))));
        var owner = install(p); var limits = FlowAssemblyTasksTest.LIMITS; owner.step(limits, true);
        var raw = (net.minecraft.nbt.CompoundTag)owner.write(); var data = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), limits.archive());
        var flow = (DataValue.RecordValue)data.field("flow"); var state = (DataValue.RecordValue)flow.field("state");
        long last = (Long.MAX_VALUE - p.steps().size() - 1) / 2;
        var s = new LinkedHashMap<>(state.fields()); s.put("work", new DataValue.IntegerValue(last - 1));
        s.put("operation", new DataValue.IntegerValue(2 * (last - 1) + p.steps().size() + 1));
        var f = new LinkedHashMap<>(flow.fields()); f.put("state", new DataValue.RecordValue(s));
        var d = new LinkedHashMap<>(data.fields()); d.put("flow", new DataValue.RecordValue(f));
        raw.put("Data", ValueNbtCodec.encode(new DataValue.RecordValue(d), limits.archive()));
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(limits, true); assertTrue(restored.loaded());
        restored.step(limits, true); restored = FlowAssemblyTasksTest.restored(restored);
        var failed = restored.flowSnapshot().orElseThrow(); assertEquals(FlowRuntime.Status.FAILED, failed.state().status());
        assertEquals(ComputationException.Code.ARITHMETIC_OVERFLOW, failed.state().diagnostic().orElseThrow().code());
        assertTrue(failed.memory().owners().isEmpty());
    }
}
