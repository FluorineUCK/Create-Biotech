package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowManagedTasksTest {
    private static final ModuleDefinition.Key COPY = new ModuleDefinition.Key("copy", 1);
    private static final ComputerHostLimits LIMITS = new ComputerHostLimits(new ResourceLimits(1000, 100_000, 1),
        FlowArchiveTest.ARCHIVE, MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 3);
    private static DataValue.RecordValue value(long value) {
        return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(value)));
    }
    private static FlowProgram program() {
        return new FlowProgram(Map.of("value", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Sleep("main_wait", "value"),
            new FlowProgram.Procedure("definition", COPY, Map.of("value", DataType.Scalar.INTEGER),
                Map.of("result", DataType.Scalar.INTEGER), Map.of("result", "value")),
            new FlowProgram.Sleep("child_wait", "value"), new FlowProgram.EndProcedure("return")));
    }
    private static ComputerBlockHost installed() {
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(program(), value(20), LIMITS, true);
        owner.setPaused(false, LIMITS, true);
        return owner;
    }
    private static FlowRuntime.Checkpoint main(ComputerBlockHost owner) { return owner.flowSnapshot().orElseThrow().state(); }
    private static FlowRuntime.Checkpoint task(ComputerBlockHost owner, UUID task) { return owner.flowTask(task).orElseThrow(); }

    @Test void sleepingMainDoesNotBlockAChildAndEveryRoundUsesTheOneComputerAllowance() {
        var owner = installed(); owner.tick(1, LIMITS, true, Map.of());
        assertEquals(FlowRuntime.Status.SLEEPING, main(owner).status());
        UUID child = owner.startFlowTask(COPY, value(0), LIMITS, true);
        assertEquals(0, owner.advanceFlowRound(1, Map.of(), LIMITS, true), "Starting a task cannot mint more work this tick");
        owner.tick(2, LIMITS, true, Map.of());
        assertEquals(1, task(owner, child).work()); assertEquals(19, main(owner).sleepTicks());
        owner.tick(2, LIMITS, true, Map.of());
        assertEquals(1, task(owner, child).work()); assertEquals(19, main(owner).sleepTicks());
        owner.tick(3, LIMITS, true, Map.of());
        assertEquals(FlowRuntime.Status.COMPLETE, task(owner, child).status());
        assertEquals(new DataValue.RecordValue(Map.of("result", new DataValue.IntegerValue(0))), task(owner, child).locals());
        assertEquals(18, main(owner).sleepTicks()); assertEquals(1, main(owner).work());
    }
    @Test void claimingTheLastTaskRetainsItsRetirementIdentityAcrossMainOnlyProgressAndReload() {
        var owner = installed(); owner.tick(1, LIMITS, true, Map.of());
        UUID child = owner.startFlowTask(COPY, value(0), LIMITS, true);
        owner.tick(2, LIMITS, true, Map.of()); owner.tick(3, LIMITS, true, Map.of());
        assertEquals(FlowRuntime.Status.COMPLETE, task(owner, child).status());
        owner.claimFlowTask(child, LIMITS, true);
        var tasks = owner.flowSnapshot().orElseThrow().tasks();
        assertTrue(tasks.contexts().isEmpty()); assertTrue(owner.flowTask(child).isEmpty());
        assertTrue(tasks.metadataUnits() > 0, "Claim releases result data, not the identity needed to reject stale handoffs");
        owner.tick(4, LIMITS, true, Map.of()); owner.setPaused(true, LIMITS, true); owner.step(LIMITS, true);
        assertEquals(tasks, owner.flowSnapshot().orElseThrow().tasks(), "Main-only execution must retain retired task identities");
        var disk = owner.write(); owner.unload();
        var restored = new ComputerBlockHost(() -> {}); restored.read(disk); restored.tick(LIMITS, true);
        assertTrue(restored.loaded()); assertEquals(tasks, restored.flowSnapshot().orElseThrow().tasks());
        assertEquals(disk, restored.write());
    }
    @Test void roundRobinPauseSleepAndRetainedResultsSurviveOwnerReload() {
        var owner = installed(); owner.tick(1, LIMITS, true, Map.of());
        UUID a = owner.startFlowTask(COPY, value(0), LIMITS, true);
        UUID b = owner.startFlowTask(COPY, value(0), LIMITS, true);
        owner.tick(2, LIMITS, true, Map.of()); assertEquals(1, task(owner, a).work()); assertEquals(0, task(owner, b).work());
        var saved = owner.write();
        var restored = new ComputerBlockHost(() -> {}); restored.read(saved);
        restored.tick(3, LIMITS, true, Map.of());
        assertEquals(1, task(restored, a).work()); assertEquals(1, task(restored, b).work(), "Reload must not reset the rotation to A");
        restored.setPaused(true, LIMITS, true); var paused = restored.write();
        restored.tick(4, LIMITS, true, Map.of()); assertEquals(paused, restored.write());
        restored.unload(); restored.tick(5, LIMITS, false, Map.of()); assertEquals(paused, restored.write());
        restored.setPaused(false, LIMITS, true);
        restored.tick(1000, LIMITS, true, Map.of()); assertEquals(17, main(restored).sleepTicks(), "No catch-up after unload");
        assertEquals(FlowRuntime.Status.COMPLETE, task(restored, a).status());
        restored.tick(1001, LIMITS, true, Map.of()); assertEquals(FlowRuntime.Status.COMPLETE, task(restored, b).status());
        var full = restored.write();
        assertEquals(ComputationException.Code.BUSY, assertThrows(ComputationException.class,
            () -> restored.startFlowTask(COPY, value(0), LIMITS, true)).code());
        assertEquals(full, restored.write()); assertTrue(restored.loaded());
        assertEquals(task(restored, a), restored.claimFlowTask(a, LIMITS, true)); assertTrue(restored.flowTask(a).isEmpty());
        restored.startFlowTask(COPY, value(0), LIMITS, true);
        assertEquals(2, restored.flowSnapshot().orElseThrow().tasks().contexts().size());
    }
    @Test void collectionAdmissionChargesAllPrivateStateAndRetainsTheOldSaveOnRejection() {
        var owner = installed();
        var child = owner.startFlowTask(COPY, value(1), LIMITS, true);
        var saved = owner.flowSnapshot().orElseThrow();
        long privateUnits = 0, shared = 0;
        for (var state : List.of(saved.state(), task(owner, child))) {
            var runtime = FlowRuntime.restore(saved.program(), state, LIMITS.hardware());
            shared = runtime.sharedUnits(); privateUnits += runtime.retainedUnits() - shared;
        }
        long exact = saved.initialInputs().storageUnits() + saved.control().storageUnits()
            + saved.tasks().metadataUnits() + privateUnits + shared;
        var small = new ComputerHostLimits(new ResourceLimits(1000, exact - 1, 1), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), 3);
        var probe = new ComputerBlockHost(() -> {}); probe.installFlow(program(), value(20), small, true);
        var before = probe.write();
        assertEquals(ComputationException.Code.MEMORY_LIMIT, assertThrows(ComputationException.class,
            () -> probe.startFlowTask(COPY, value(1), small, true)).code());
        assertEquals(before, probe.write()); assertTrue(probe.loaded());
        var reloaded = new ComputerBlockHost(() -> {}); reloaded.read(owner.write()); reloaded.tick(small, true);
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, reloaded.diagnostic().orElseThrow().code());
        assertEquals(owner.write(), reloaded.write(), "Capacity shrink must not delete children or results");
    }
    @Test void tasksCannotBeSilentlyErasedByInstallationClaimOrUnrecognizedSaveFields() {
        var owner = installed(); var child = owner.startFlowTask(COPY, value(1), LIMITS, true);
        var before = owner.write();
        assertThrows(ComputationException.class, () -> owner.installFlow(program(), value(1), LIMITS, true));
        assertThrows(ComputationException.class, () -> owner.claimFlowTask(child, LIMITS, true));
        assertEquals(before, owner.write());
        var encoded = (net.minecraft.nbt.CompoundTag) before;
        assertEquals(16, encoded.getInt(FlowBlockNbtCodec.MARKER));
        var downgraded = encoded.copy(); downgraded.putInt(FlowBlockNbtCodec.MARKER, 14);
        assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(downgraded, LIMITS.archive()));
        var base = owner.flowSnapshot().orElseThrow(); var task = task(owner, child);
        assertThrows(IllegalArgumentException.class, () -> new FlowBlockHost.Saved(base.program(), base.initialInputs(), base.state(),
            base.receipts(), base.control(), new FlowTasks(List.of(task, task), Optional.empty())));
        assertThrows(IllegalArgumentException.class, () -> new FlowBlockHost.Saved(base.program(), base.initialInputs(), base.state(),
            base.receipts(), base.control(), new FlowTasks(List.of(task), Optional.of(UUID.randomUUID()))));
    }
    @Test void explicitResetClearsTasksButNotAcceptedPhysicalSerialWorkOrTheCurrentCpuAllowance() {
        var device = SerialFlowHostTest.device(); device.configure("s0", net.minecraft.core.Direction.EAST, true);
        var limits = new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), SerialFlowHostTest.SERIAL, 3);
        var owner = new ComputerBlockHost(() -> {}, pin -> {}, device); owner.installFlow(program(), value(20), limits, true);
        owner.setPaused(false, limits, true); owner.tick(1, limits, true, Map.of());
        var id = owner.startFlowTask(COPY, value(20), limits, true); var computer = main(owner).computer();
        assertTrue(device.prepareSend("s0", SerialFlowHostTest.RECORD).publish()); var physical = device.write();
        owner.reset(limits, true);
        assertTrue(owner.flowTask(id).isEmpty()); assertEquals(FlowTasks.EMPTY, owner.flowSnapshot().orElseThrow().tasks());
        assertEquals(computer, main(owner).computer()); assertTrue(main(owner).paused()); assertEquals(0, main(owner).work());
        assertEquals(physical, device.write(), "Resetting player tasks cannot undo an already accepted device operation");
        owner.setPaused(false, limits, true); assertEquals(0, owner.advanceFlowRound(1, Map.of(), limits, true));
    }
    @Test void twoSerialTasksUseOneDeviceQueueAndRestoreEveryPublishedBoundaryWithoutDuplicateConsumption() {
        var device = SerialFlowHostTest.device(); device.configure("s0", net.minecraft.core.Direction.EAST, true);
        device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(SerialFlowHostTest.RECORD,
            new SerialRecordCodec.Limits(4096, 1048576, SerialFlowHostTest.ARCHIVE)));
        // This fixture exchanges typed records/ANY; declare parser/master capabilities, not extra RAM.
        var limits = new ComputerHostLimits(LIMITS.hardware(), SerialFlowHostTest.ARCHIVE, LIMITS.mailbox(), LIMITS.pins(), SerialFlowHostTest.SERIAL, 3, true, true);
        var program = new FlowProgram(Map.of("value", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("sleep", "value"),
            new FlowProgram.Procedure("definition", COPY, Map.of(), Map.of("result", SerialResult.RECEIVE), Map.of("result", "received")),
            new FlowProgram.SerialReceive("receive", "s0", "received"), new FlowProgram.EndProcedure("return")));
        var owner = new ComputerBlockHost(() -> {}, pin -> {}, device); owner.installFlow(program, value(20), limits, true);
        owner.step(limits, true);
        UUID a = owner.startFlowTask(COPY, new DataValue.RecordValue(Map.of()), limits, true);
        UUID b = owner.startFlowTask(COPY, new DataValue.RecordValue(Map.of()), limits, true);
        for (int i = 0; i < 4; i++) {
            owner.step(limits, true); var before = owner.flowSnapshot().orElseThrow();
            var saved = owner.write(); var disk = device.write(); owner.unload(); device.read(disk);
            owner = new ComputerBlockHost(() -> {}, pin -> {}, device); owner.read(saved); owner.tick(limits, true);
            assertEquals(before, owner.flowSnapshot().orElseThrow()); assertEquals(disk, device.write());
        }
        assertEquals(SerialResult.receive("received", SerialFlowHostTest.RECORD), task(owner, a).locals().field("result"));
        assertEquals(SerialResult.receive("empty", null), task(owner, b).locals().field("result"));
        assertEquals(20, main(owner).sleepTicks(), "Stepping a ready task must not tick another sleeping task");
        assertEquals(0, device.status("s0").receive().frames());
    }
    @Test void unsaveableTaskSerialResultDoesNotConsumeThePhysicalFrame() {
        var device = SerialFlowHostTest.device(); device.configure("s0", net.minecraft.core.Direction.EAST, true);
        var packet = new DataValue.RecordValue(Map.of("large", new DataValue.TextValue("z".repeat(500))));
        device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(packet,
            new SerialRecordCodec.Limits(4096, 1048576, SerialFlowHostTest.ARCHIVE)));
        var limits = new ComputerHostLimits(LIMITS.hardware(), SerialFlowHostTest.ARCHIVE, LIMITS.mailbox(), LIMITS.pins(), SerialFlowHostTest.SERIAL, 3, true, true);
        var program = new FlowProgram(Map.of("value", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("sleep", "value"),
            new FlowProgram.Procedure("definition", COPY, Map.of(), Map.of("result", SerialResult.RECEIVE), Map.of("result", "received")),
            new FlowProgram.SerialReceive("receive", "s0", "received"), new FlowProgram.EndProcedure("return")));
        var owner = new ComputerBlockHost(() -> {}, pin -> {}, device); owner.installFlow(program, value(20), limits, true);
        owner.step(limits, true); UUID task = owner.startFlowTask(COPY, new DataValue.RecordValue(Map.of()), limits, true);
        long savedUnits = ValueNbtCodec.decode(((net.minecraft.nbt.CompoundTag)owner.write()).getCompound("Data"), limits.archive()).storageUnits();
        var tight = new ComputerHostLimits(limits.hardware(), new ArchiveLimits(100000, 1000000, savedUnits + 1),
            limits.mailbox(), limits.pins(), limits.serial(), 3, limits.isa1a(), limits.isa1b());
        var oldOwner = owner.write(); var oldDevice = device.write();
        assertThrows(ComputerBlockHost.HostException.class, () -> owner.step(tight, true));
        assertEquals(oldOwner, owner.write()); assertEquals(oldDevice, device.write());
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, owner.diagnostic().orElseThrow().code());
        owner.step(limits, true); assertEquals(1, task(owner, task).work());
        assertEquals(SerialResult.receive("received", packet), task(owner, task).locals().field("received"));
        assertEquals(0, device.status("s0").receive().frames());
    }
    @Test void rootCanKeepItsOutputWhileAnUnrelatedTaskRunsAndRetires() {
        var base = program(); var steps = new ArrayList<>(base.steps());
        steps.addFirst(new FlowProgram.SignalWrite("drive", "p0", "value"));
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(new FlowProgram(base.inputs(), steps).withAssembly(), value(20), LIMITS, true);
        owner.step(LIMITS, true); assertEquals(Map.of("p0", 15), main(owner).signals().outputs(), "Existing single-flow pins still operate");
        var id=owner.startFlowTask(COPY,value(0),LIMITS,true);
        for(int i=0;i<30 && task(owner,id).status()!=FlowRuntime.Status.COMPLETE;i++) {
            owner.step(LIMITS,true);
            var saved=owner.write();owner=new ComputerBlockHost(()->{});owner.read(saved);owner.tick(LIMITS,true);
            assertEquals(saved,owner.write());assertEquals(Map.of("p0",15),main(owner).signals().outputs());
        }
        assertEquals(FlowRuntime.Status.COMPLETE,owner.claimFlowTask(id,LIMITS,true).status());
        assertEquals(Map.of("p0",15),main(owner).signals().outputs());
    }
    @Test void immutableConstantPoolIsChargedOnceAcrossRetainedContexts() {
        var base = program();
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var large = new ComputationGraph(Map.of(), Map.of("constant", new ComputationGraph.Constant(new DataValue.TextValue("k".repeat(2000)))), Map.of("out", "constant"));
        var archive = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(
            new ProgramDefinition(empty, Map.of(), List.of(), List.of(), Map.of("large", new ProgramDefinition.Entry(large, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(base.inputs(), base.steps(), archive);
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(program, value(20), LIMITS, true);
        owner.startFlowTask(COPY, value(0), LIMITS, true); owner.startFlowTask(COPY, value(0), LIMITS, true);
        var saved = owner.flowSnapshot().orElseThrow();
        var all = new ArrayList<>(saved.tasks().contexts()); all.add(saved.state());
        long shared = 0, privateUnits = 0;
        for (var state : all) {
            var runtime = FlowRuntime.restore(program, state, LIMITS.hardware()); shared = runtime.sharedUnits(); privateUnits += runtime.retainedUnits() - shared;
        }
        assertTrue(shared >= 2000);
        long exact = shared + privateUnits + saved.initialInputs().storageUnits() + saved.control().storageUnits() + saved.tasks().metadataUnits();
        var small = new ComputerHostLimits(new ResourceLimits(1000, exact, 1), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), 3);
        var restored = new ComputerBlockHost(() -> {}); restored.read(owner.write()); restored.tick(small, true);
        assertTrue(restored.loaded(), "Two tasks must not multiply the immutable program pool");
        assertEquals(saved, restored.flowSnapshot().orElseThrow());
    }
    @Test void twoPureComputationsYieldAndRestoreWithinOneComputerWorkBudget() {
        var integer = DataType.Scalar.INTEGER;
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var increment = new ComputationGraph(Map.of("value", integer), Map.of("value", new ComputationGraph.Input("value"),
            "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "value", "one")), Map.of("result", "sum"));
        var archive = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(
            new ProgramDefinition(empty, Map.of(), List.of(), List.of(), Map.of("increment", new ProgramDefinition.Entry(increment, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(Map.of("value", integer), List.of(new FlowProgram.Sleep("main_wait", "value"),
            new FlowProgram.Procedure("definition", COPY, Map.of("value", integer), Map.of("result", integer), Map.of("result", "value")),
            new FlowProgram.Evaluate("add_one", "increment", new FlowCallBindings(Map.of("value", "value"), Map.of("result", "value"))),
            new FlowProgram.EndProcedure("return")), archive);
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(program, value(1000), LIMITS, true); owner.setPaused(false, LIMITS, true);
        UUID a = owner.startFlowTask(COPY, value(1), LIMITS, true); UUID b = owner.startFlowTask(COPY, value(41), LIMITS, true);
        boolean savedPureFrame = false; long total = 0;
        for (int tick = 1; tick < 100; tick++) {
            owner.tick(tick, LIMITS, true, Map.of());
            long next = main(owner).work() + task(owner, a).work() + task(owner, b).work();
            assertEquals(1, next - total); total = next;
            savedPureFrame |= task(owner, a).expression().isPresent() || task(owner, b).expression().isPresent();
            var saved = owner.flowSnapshot().orElseThrow(); var encoded = owner.write(); owner.unload();
            owner = new ComputerBlockHost(() -> {}); owner.read(encoded); owner.setPaused(false, LIMITS, true);
            assertEquals(saved, owner.flowSnapshot().orElseThrow());
            if (task(owner, a).status() == FlowRuntime.Status.COMPLETE && task(owner, b).status() == FlowRuntime.Status.COMPLETE) break;
            assertTrue(tick < 99);
        }
        assertTrue(savedPureFrame); assertEquals(FlowRuntime.Status.SLEEPING, main(owner).status());
        assertEquals(new DataValue.IntegerValue(2), task(owner, a).locals().field("result"));
        assertEquals(new DataValue.IntegerValue(42), task(owner, b).locals().field("result"));
    }
    @Test void claimingTheLastServedTaskKeepsTheNextSurvivingTasksTurn() {
        var owner = installed(); owner.tick(1, LIMITS, true, Map.of());
        UUID a = owner.startFlowTask(COPY, value(0), LIMITS, true); UUID b = owner.startFlowTask(COPY, value(0), LIMITS, true);
        owner.tick(2, LIMITS, true, Map.of()); owner.tick(3, LIMITS, true, Map.of()); owner.tick(4, LIMITS, true, Map.of());
        owner.claimFlowTask(a, LIMITS, true); UUID c = owner.startFlowTask(COPY, value(0), LIMITS, true);
        owner.tick(5, LIMITS, true, Map.of());
        assertEquals(FlowRuntime.Status.COMPLETE, task(owner, b).status()); assertEquals(0, task(owner, c).work());
    }
}
