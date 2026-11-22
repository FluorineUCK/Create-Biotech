package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowAssemblyTasksTest {
    static final ModuleDefinition.Key COPY = new ModuleDefinition.Key("copy", 1);
    static final ComputerHostLimits LIMITS = new ComputerHostLimits(new ResourceLimits(1000, 100_000, 1),
        FlowArchiveTest.ARCHIVE, MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 3, true);
    static DataValue.RecordValue value(long n) { return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(n))); }
    static FlowProgram program() {
        var type = Map.<String,DataType>of("value", DataType.Scalar.INTEGER);
        return new FlowProgram(type, List.of(new FlowProgram.Sleep("main_wait", "value"),
            new FlowProgram.Procedure("definition", COPY, type, Map.of("result", DataType.Scalar.INTEGER), Map.of("result", "value")),
            new FlowProgram.Sleep("child_wait", "value"), new FlowProgram.EndProcedure("return"))).withAssembly();
    }
    @Test void failedTryLaunchPaysForFalseWithoutPublishingTheSpeculativeChild() {
        var target = new ModuleDefinition.Key("alarm", 1);
        var program = new FlowProgram(Map.of(), List.of(
            new FlowProgram.TryLaunch("try", target, "ok"),
            new FlowProgram.Procedure("def", target, Map.of(), Map.of(), Map.of()),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, new DataValue.RecordValue(Map.of()), LIMITS, true);
        var before = host.write();
        host.step(LIMITS, true);
        var admitted = host.flowSnapshot().orElseThrow();
        assertEquals(1, admitted.tasks().contexts().size());
        var child = FlowRuntime.restore(program, admitted.tasks().contexts().getFirst(), LIMITS.hardware());
        var main = FlowRuntime.restore(program, admitted.state(), LIMITS.hardware());
        long memory = main.retainedUnits() + child.retainedUnits() - main.sharedUnits()
            + admitted.initialInputs().storageUnits() + admitted.control().storageUnits() + admitted.tasks().metadataUnits();
        long archive = ValueNbtCodec.decode(((net.minecraft.nbt.CompoundTag)host.write()).getCompound("Data"), LIMITS.archive()).storageUnits();
        var tightRam = new ResourceLimits(LIMITS.hardware().programUnits(), memory - 1, 1);
        var tightDisk = new ArchiveLimits(100000, 1000000, archive - 1);
        for (boolean disk : List.of(false, true)) {
            var limits = new ComputerHostLimits(disk ? LIMITS.hardware() : tightRam, disk ? tightDisk : LIMITS.archive(),
                LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), LIMITS.taskSlots());
            var probe = new ComputerBlockHost(() -> {});
            var notices = new ArrayList<String>(); probe.onLaunchRefused((code, handler) -> notices.add(code));
            probe.read(before); probe.tick(limits, true); assertTrue(probe.loaded());
            probe.step(limits, true);
            var refused = probe.flowSnapshot().orElseThrow();
            assertEquals(new DataValue.BooleanValue(false), refused.state().locals().field("ok"));
            assertEquals(1, refused.state().work());
            assertEquals(FlowTasks.EMPTY, refused.tasks());
            assertEquals(List.of(disk ? "handler_archive_full" : "handler_memory_full"), notices);
            probe = restored(probe); probe.step(LIMITS, true);
            assertTrue(probe.flowSnapshot().orElseThrow().tasks().contexts().isEmpty(), "More capacity must not replay the refused admission");
        }
    }

    @Test void aBusyTryLaunchAdvancesWithoutQueuingAndDoesNotStopTheCaller() {
        var entry = new ModuleDefinition.Key("on_change", 1);
        var program = new FlowProgram(Map.of(), List.of(
            new FlowProgram.TryLaunch("launch", entry, "started"),
            new FlowProgram.TryLaunch("again", entry, "duplicate"),
            new FlowProgram.Procedure("change", entry, Map.of(), Map.of(), Map.of()),
            new FlowProgram.Forever("loop"), new FlowProgram.EndForever("loop_end"),
            new FlowProgram.EndProcedure("change_end"))).withAssembly();
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(program, new DataValue.RecordValue(Map.of()), LIMITS, true);
        owner.step(LIMITS, true);
        assertEquals(new DataValue.BooleanValue(true), owner.flowSnapshot().orElseThrow().state().locals().field("started"));
        var task = owner.flowSnapshot().orElseThrow().tasks().contexts().getFirst().flow();
        owner = restored(owner);
        var refusals = new ArrayList<String>();
        owner.onLaunchRefused((code, handler) -> refusals.add(code + ":" + handler));
        for (int i = 0; i < 12 && !owner.flowSnapshot().orElseThrow().state().locals().fields().containsKey("duplicate"); i++)
            owner.step(LIMITS, true);
        var state = owner.flowSnapshot().orElseThrow();
        assertEquals(new DataValue.BooleanValue(false), state.state().locals().field("duplicate"));
        assertEquals(List.of(task), state.tasks().contexts().stream().map(FlowRuntime.Checkpoint::flow).toList());
        assertEquals(List.of("handler_already_running:on_change"), refusals);
        assertEquals(FlowRuntime.Status.READY, state.state().status());
    }

    @Test void tryLaunchDoesNotReplayAfterCapacityReturnsAndRetiresACompletedHandler() {
        var entry = new ModuleDefinition.Key("handler", 1);
        var program = new FlowProgram(Map.of(), List.of(
            new FlowProgram.TryLaunch("first", entry, "first_result"),
            new FlowProgram.TryLaunch("second", entry, "second_result"),
            new FlowProgram.Procedure("handler_def", entry, Map.of(), Map.of(), Map.of()),
            new FlowProgram.EndProcedure("handler_end"))).withAssembly();
        var owner = new ComputerBlockHost(() -> {});
        var oneSlot = new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), 1, true);
        var refusals = new ArrayList<String>();
        owner.onLaunchRefused((code, handler) -> refusals.add(code));
        owner.installFlow(program, new DataValue.RecordValue(Map.of()), oneSlot, true);
        owner.step(oneSlot, true);
        assertEquals(new DataValue.BooleanValue(false), owner.flowSnapshot().orElseThrow().state().locals().field("first_result"));
        assertEquals(List.of("handler_slots_full"), refusals);
        assertTrue(owner.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
        owner.step(LIMITS, true);
        assertEquals(new DataValue.BooleanValue(true), owner.flowSnapshot().orElseThrow().state().locals().field("second_result"));
        for (int i = 0; i < 8; i++) owner.step(LIMITS, true);
        var state = owner.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, state.state().status());
        assertTrue(state.tasks().contexts().isEmpty(), "A successful handler leaves no unclaimed result");
        assertEquals(1, state.tasks().slots().entries().size(), "The skipped attempt never claimed a slot");
        assertTrue(state.tasks().slots().entries().getFirst().retired());
    }

    @Test void aNumericEdgeOfAProgramValueCanLaunchTheHandlerWithoutBroadcastHardware() {
        var entry = new ModuleDefinition.Key("threshold_handler", 1);
        var integer = DataType.Scalar.INTEGER;
        var one = new DataValue.IntegerValue(1);
        var zero = new DataValue.IntegerValue(0);
        var graph = new ComputationGraph(Map.of("current", integer, "previous", integer), Map.of(
            "old", new ComputationGraph.Input("previous"), "new", new ComputationGraph.Input("current"),
            "threshold", new ComputationGraph.Constant(one),
            "edge", new ComputationGraph.Scalar(ScalarOperation.EDGE_RISING_INTEGER,
                Map.of("previous", "old", "current", "new", "threshold", "threshold"))), Map.of("fired", "edge"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("hits", new ProgramDefinition.Cell(integer, zero)), List.of(), List.of(),
            Map.of("crossing", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(Map.of("previous", integer, "current", integer), List.of(
            new FlowProgram.If("edge_check", new FlowInput.Expression("crossing", "fired",
                Map.of("previous", "previous", "current", "current"))),
            new FlowProgram.TryLaunch("dispatch", entry, "launched"),
            new FlowProgram.EndIf("end_if"),
            new FlowProgram.Procedure("handler_definition", entry, Map.of(), Map.of(), Map.of()),
            new FlowProgram.Shared("count", "hits", new FlowInput.Literal(one), true),
            new FlowProgram.EndProcedure("handler_end")), archive).withAssembly();
        var basic = new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), LIMITS.taskSlots());
        for (long sample : List.of(1L, 2L)) {
            var host = new ComputerBlockHost(() -> {});
            host.installFlow(program, new DataValue.RecordValue(Map.of("previous", zero, "current", new DataValue.IntegerValue(sample))), basic, true);
            host.setPaused(false, basic, true);
            for (long tick = 0; tick < 30; tick++) host.tick(tick, basic, true, Map.of());
            var saved = host.flowSnapshot().orElseThrow();
            assertEquals(new DataValue.IntegerValue(sample == 1 ? 0 : 1), saved.memory().cells().field("hits"));
            assertTrue(saved.tasks().contexts().isEmpty());
            assertEquals(FlowRuntime.Status.COMPLETE, saved.state().status());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void combinedEdgesKeepSamplingAfterMainEndsAndRetainBaselines(boolean rising) {
        var key = new ModuleDefinition.Key("monitor", 1);
        var handler = new ModuleDefinition.Key("handler", 1);
        var intType = DataType.Scalar.INTEGER;
        var zero = new DataValue.IntegerValue(0); var one = new DataValue.IntegerValue(1);
        var initial = rising ? one : new DataValue.IntegerValue(2);
        var updated = rising ? new DataValue.IntegerValue(2) : one;
        var graph = new ComputationGraph(Map.of(), Map.of(
            "current", new ComputationGraph.ReadMemory("value"), "threshold", new ComputationGraph.Constant(one),
            "rising", new ComputationGraph.Scalar(ScalarOperation.SAMPLE_RISING_INTEGER,
                Map.of("current", "current", "threshold", "threshold")),
            "falling", new ComputationGraph.Scalar(ScalarOperation.SAMPLE_FALLING_INTEGER,
                Map.of("current", "current", "threshold", "threshold")),
            "either", new ComputationGraph.Scalar(ScalarOperation.OR_BOOLEAN,
                Map.of("left", "rising", "right", "falling"))), Map.of("fired", "either"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("value", new ProgramDefinition.Cell(intType, initial), "hits", new ProgramDefinition.Cell(intType, zero)),
            List.of(), List.of(), Map.of("condition", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var writer = new ModuleDefinition.Key("writer", 1);
        var program = new FlowProgram(Map.of(), List.of(
            new FlowProgram.Procedure("monitor", key, Map.of(), Map.of(), Map.of()),
            new FlowProgram.Watch("watch", handler, new FlowInput.Expression("condition", "fired", Map.of())),
            new FlowProgram.EndProcedure("monitor_end"),
            new FlowProgram.Procedure("handler", handler, Map.of(), Map.of(), Map.of()),
            new FlowProgram.Shared("count", "hits", new FlowInput.Literal(one), true),
            new FlowProgram.EndProcedure("handler_end"),
            new FlowProgram.Procedure("writer", writer, Map.of(), Map.of(), Map.of()),
            new FlowProgram.Shared("write", "value", new FlowInput.Literal(updated), false),
            new FlowProgram.EndProcedure("writer_end")), archive).withAssembly();
        var limits = new ComputerHostLimits(new ResourceLimits(1000, 100_000, 12), LIMITS.archive(),
            LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), 4);
        var empty = new DataValue.RecordValue(Map.of());
        var host = new ComputerBlockHost(() -> {});
        var undersized = new ComputerHostLimits(limits.hardware(), limits.archive(), limits.mailbox(),
            limits.pins(), limits.serial(), 2);
        var noRoom = assertThrows(ComputationException.class, () -> host.installFlow(program, empty, undersized, true));
        assertEquals(ComputationException.Code.MEMORY_LIMIT, noRoom.code());
        assertTrue(host.flowSnapshot().isEmpty());
        host.installFlow(program, empty, limits, true);
        host.setPaused(false, limits, true);
        for (long tick = 1; tick <= 10; tick++) host.tick(tick, limits, true, Map.of());
        var saved = host.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, saved.state().status());
        assertEquals(zero, saved.memory().cells().field("hits"));
        assertEquals(key, saved.tasks().contexts().getFirst().entry().orElseThrow());
        host.setPaused(true, limits, true);
        var checkpoint = host.flowSnapshot().orElseThrow();
        var loaded = new ComputerBlockHost(() -> {}); loaded.read(host.write());
        loaded.tick(99, limits, true, Map.of());
        assertEquals(checkpoint, loaded.flowSnapshot().orElseThrow());
        loaded.startFlowTask(writer, empty, limits, true);
        loaded.setPaused(false, limits, true);
        for (long tick = 100; tick <= 120; tick++) loaded.tick(tick, limits, true, Map.of());
        assertEquals(one, loaded.flowSnapshot().orElseThrow().memory().cells().field("hits"));
    }

    static ComputerBlockHost installed() {
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(program(), value(100), LIMITS, true);
        owner.step(LIMITS, true); return owner;
    }
    static ComputerBlockHost restored(ComputerBlockHost owner) {
        var before = owner.flowSnapshot().orElseThrow(); var next = new ComputerBlockHost(() -> {}); next.read(owner.write());
        next.tick(LIMITS, true); assertTrue(next.loaded(), () -> next.diagnostic().toString());
        assertEquals(before, next.flowSnapshot().orElseThrow()); return next;
    }
    @Test void waitUntilYieldsAndRechecksLiveConditionAfterAnotherTaskAndAColdCheckpoint() {
        var bool = DataType.Scalar.BOOLEAN;
        var archive = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(new ProgramDefinition(
            new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("ready", new ProgramDefinition.Cell(bool, new DataValue.BooleanValue(false))), List.of(), List.of(),
            Map.of("read_ready", new ProgramDefinition.Entry(
                new ComputationGraph(Map.of(), Map.of("ready", new ComputationGraph.ReadMemory("ready")), Map.of("value", "ready")),
                List.of(), List.of()))), Map.of());
        var signal = new ModuleDefinition.Key("signal", 1);
        var program = new FlowProgram(Map.of(), List.of(
            new FlowProgram.WaitUntil("await_ready", new FlowInput.Expression("read_ready", "value")),
            new FlowProgram.Procedure("signal_def", signal, Map.of(), Map.of(), Map.of()),
            new FlowProgram.Shared("set_ready", "ready", new FlowInput.Literal(new DataValue.BooleanValue(true)), false),
            new FlowProgram.EndProcedure("signal_return")), archive).withAssembly();
        var owner = new ComputerBlockHost(() -> {});
        var empty = new DataValue.RecordValue(Map.of());
        owner.installFlow(program, empty, LIMITS, true);
        for (int i=0;i<20 && owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.SLEEPING;i++) owner.step(LIMITS,true);
        var sleeping = owner.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.SLEEPING, sleeping.status());
        assertEquals(1, sleeping.sleepTicks());
        owner = restored(owner);
        assertEquals(sleeping, owner.flowSnapshot().orElseThrow().state());
        var worker = owner.startFlowTask(signal, empty, LIMITS, true);
        owner.setPaused(false, LIMITS, true);
        for(int i=1;i<=40;i++) owner.tick(i,LIMITS,true,Map.of());
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowSnapshot().orElseThrow().state().status());
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowTask(worker).orElseThrow().status());
    }

    @Test void stopReturnsFromASynchronousCallAndThenEndsOnlyTheMainScript() {
        var stop = new ModuleDefinition.Key("stop", 1);
        var steps = new ArrayList<FlowProgram.Step>(List.of(
            new FlowProgram.Invoke("call", stop, new FlowCallBindings(Map.of(), Map.of("result", "answer"))),
            new FlowProgram.StopScript("main_stop"), new FlowProgram.Sleep("skip_main", "value"),
            new FlowProgram.Procedure("stop_definition", stop, Map.of(), Map.of("result", DataType.Scalar.INTEGER), Map.of("result", "ret")),
            new FlowProgram.Evaluate("result", "constant", Optional.of(new FlowCallBindings(Map.of(), Map.of("value", "ret")))),
            new FlowProgram.StopScript("call_stop"), new FlowProgram.Sleep("skip_callee", "ret"), new FlowProgram.EndProcedure("end")));
        steps.addAll(program().steps().subList(1, program().steps().size()));
        var archive = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(new ProgramDefinition(
            new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(), Map.of("constant",
            new ProgramDefinition.Entry(new ComputationGraph(Map.of(),
                Map.of("constant", new ComputationGraph.Constant(new DataValue.IntegerValue(42))), Map.of("value", "constant")),
                List.of(), List.of()))), Map.of());
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(new FlowProgram(Map.of("value", DataType.Scalar.INTEGER), steps, archive).withAssembly(), value(100), LIMITS, true);
        var worker = owner.startFlowTask(COPY, value(4), LIMITS, true);
        for (int i=0;i<24 && owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++) {
            owner.step(LIMITS,true); owner = restored(owner);
        }
        var done = owner.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE, done.status());
        assertEquals(new DataValue.IntegerValue(42), done.locals().field("answer"));
        assertEquals(FlowRuntime.Status.SLEEPING, owner.flowTask(worker).orElseThrow().status());
        owner.setPaused(false,LIMITS,true);
        for (int i=1;i<=12;i++) owner.tick(i,LIMITS,true,Map.of());
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowTask(worker).orElseThrow().status());
    }

    @Test void aPrivateProcedureTaskRunsWhileMainSleepsAndRestoresEveryInstruction() {
        var owner = installed(); var main = owner.flowSnapshot().orElseThrow().state();
        var child = owner.startFlowTask(COPY, value(0), LIMITS, true);
        for (int i = 0; i < 3; i++) {
            owner = restored(owner); var task = owner.flowTask(child).orElseThrow();
            assertEquals(Optional.of(COPY), task.entry()); assertTrue(task.assembly().isPresent());
            assertTrue(task.calls().frames().isEmpty()); assertTrue(task.expression().isEmpty());
            owner.step(LIMITS, true); assertEquals(main, owner.flowSnapshot().orElseThrow().state());
        }
        owner = restored(owner); var result = owner.flowTask(child).orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, result.status()); assertEquals(3, result.work());
        assertEquals(new DataValue.RecordValue(Map.of("result", new DataValue.IntegerValue(0))), result.locals());
        assertTrue(result.assembly().orElseThrow().calls().orElseThrow().frames().isEmpty());
        assertEquals(result, owner.claimFlowTask(child, LIMITS, true));
        owner = restored(owner); assertTrue(owner.flowTask(child).isEmpty()); assertEquals(main, owner.flowSnapshot().orElseThrow().state());
    }
    @Test void tasksRotateUnderOneBudgetAndRetainedResultsKeepTheirSlots() {
        var owner = installed(); var a = owner.startFlowTask(COPY, value(0), LIMITS, true);
        var b = owner.startFlowTask(COPY, value(0), LIMITS, true); owner.setPaused(false, LIMITS, true);
        for (int tick = 1; tick <= 6; tick++) {
            owner.tick(tick, LIMITS, true, Map.of());
            long work = owner.flowTask(a).orElseThrow().work() + owner.flowTask(b).orElseThrow().work();
            assertEquals(tick, work); owner.tick(tick, LIMITS, true, Map.of());
            assertEquals(work, owner.flowTask(a).orElseThrow().work() + owner.flowTask(b).orElseThrow().work());
            assertTrue(Math.abs(owner.flowTask(a).orElseThrow().work() - owner.flowTask(b).orElseThrow().work()) <= 1);
        }
        assertEquals(94, owner.flowSnapshot().orElseThrow().state().sleepTicks());
        owner.setPaused(true, LIMITS, true); owner = restored(owner); var full = owner.write();
        var target = owner;
        assertEquals(ComputationException.Code.BUSY, assertThrows(ComputationException.class, () -> target.startFlowTask(COPY, value(0), LIMITS, true)).code());
        assertEquals(full, owner.write()); owner.claimFlowTask(a, LIMITS, true);
        var next = owner.startFlowTask(COPY, value(0), LIMITS, true); assertNotEquals(a, next); restored(owner);
    }
    @Test void aTaskFaultDoesNotStopItsSiblingOrLoseTheFailedResultOnReload() {
        var owner = installed(); var bad = owner.startFlowTask(COPY, value(-1), LIMITS, true);
        var good = owner.startFlowTask(COPY, value(0), LIMITS, true);
        for (int i = 0; i < 4; i++) { owner.step(LIMITS, true); owner = restored(owner); }
        assertEquals(ComputationException.Code.INVALID_ARGUMENT, owner.flowTask(bad).orElseThrow().diagnostic().orElseThrow().code());
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowTask(good).orElseThrow().status());
        assertEquals(FlowRuntime.Status.SLEEPING, owner.flowSnapshot().orElseThrow().state().status());
        assertEquals(100, owner.flowSnapshot().orElseThrow().state().sleepTicks());
        assertEquals(FlowRuntime.Status.FAILED, owner.claimFlowTask(bad, LIMITS, true).status()); restored(owner);
    }
    @Test void taskAdmissionChargesOneComputerRamAndArchiveWithoutPartialPublication() {
        var owner = installed(); var before = owner.write(); var child = owner.startFlowTask(COPY, value(0), LIMITS, true);
        var saved = owner.flowSnapshot().orElseThrow(); long privateUnits = 0, pool = 0;
        for (var state : List.of(saved.state(), owner.flowTask(child).orElseThrow())) {
            var runtime = FlowRuntime.restore(saved.program(), state, LIMITS.hardware());
            pool = runtime.sharedUnits(); privateUnits += runtime.retainedUnits() - pool;
        }
        long exact = saved.initialInputs().storageUnits() + saved.control().storageUnits() + saved.tasks().metadataUnits() + privateUnits + pool;
        var tight = new ComputerHostLimits(new ResourceLimits(1000, exact - 1, 1), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), 3);
        var probe = new ComputerBlockHost(() -> {}); probe.read(before); probe.tick(tight, true); assertTrue(probe.loaded()); var raw = probe.write();
        assertEquals(ComputationException.Code.MEMORY_LIMIT, assertThrows(ComputationException.class, () -> probe.startFlowTask(COPY, value(0), tight, true)).code());
        assertEquals(raw, probe.write());
        long size = ValueNbtCodec.decode(((net.minecraft.nbt.CompoundTag)owner.write()).getCompound("Data"), LIMITS.archive()).storageUnits();
        var archive = new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(100000, 1000000, size - 1), LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), 3);
        var disk = new ComputerBlockHost(() -> {}); disk.read(before); disk.tick(archive, true); assertTrue(disk.loaded());
        assertThrows(ArchiveException.class, () -> disk.startFlowTask(COPY, value(0), archive, true)); assertEquals(before, disk.write());
    }
    @Test void nestedCallWithinATaskKeepsAPrivateRootAndCanRestoreInsideItsSleep() {
        var type = Map.<String,DataType>of("value", DataType.Scalar.INTEGER); var outer = new ModuleDefinition.Key("outer", 1);
        var steps = new ArrayList<FlowProgram.Step>(List.of(new FlowProgram.Sleep("main_wait", "value"),
            new FlowProgram.Procedure("outer", outer, type, Map.of("answer", DataType.Scalar.INTEGER), Map.of("answer", "returned")),
            new FlowProgram.Invoke("inner", COPY, new FlowCallBindings(Map.of("value", "value"), Map.of("result", "returned"))),
            new FlowProgram.EndProcedure("outer_return")));
        steps.addAll(program().steps().subList(1, program().steps().size()));
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(new FlowProgram(type, steps).withAssembly(), value(100), LIMITS, true); owner.step(LIMITS, true);
        var child = owner.startFlowTask(outer, value(2), LIMITS, true); boolean nestedSleep = false;
        for (int i = 0; i < 25; i++) {
            var state = owner.flowTask(child).orElseThrow();
            if (state.status() == FlowRuntime.Status.COMPLETE) break;
            if (state.status() == FlowRuntime.Status.SLEEPING) {
                nestedSleep = true; assertEquals(1, state.assembly().orElseThrow().calls().orElseThrow().frames().size());
            }
            owner.step(LIMITS, true); owner = restored(owner);
        }
        assertTrue(nestedSleep); assertEquals(new DataValue.RecordValue(Map.of("answer", new DataValue.IntegerValue(2))), owner.flowTask(child).orElseThrow().locals());
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowTask(child).orElseThrow().status());
        assertEquals(value(100), owner.flowSnapshot().orElseThrow().state().locals());
    }
    @Test void aForeverMainCannotStarveThePrivateTaskOrMintAnotherCpuBudget() {
        var steps = new ArrayList<FlowProgram.Step>(List.of(new FlowProgram.Forever("main"), new FlowProgram.EndForever("back")));
        steps.addAll(program().steps().subList(1, program().steps().size()));
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(new FlowProgram(Map.of(), steps).withAssembly(), new DataValue.RecordValue(Map.of()), LIMITS, true);
        var task = owner.startFlowTask(COPY, value(0), LIMITS, true); owner.setPaused(false, LIMITS, true);
        for (int tick = 1; tick <= 6; tick++) {
            owner.tick(tick, LIMITS, true, Map.of());
            assertEquals(tick, owner.flowSnapshot().orElseThrow().state().work() + owner.flowTask(task).orElseThrow().work());
        }
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowTask(task).orElseThrow().status());
        assertEquals(3, owner.flowSnapshot().orElseThrow().state().work());
    }
    @Test void retiredAssemblyTaskSlotsCannotBeDowngradedAfterClaimingTheirResults() {
        var owner = installed(); var task = owner.startFlowTask(COPY, value(0), LIMITS, true);
        for (int i = 0; i < 3; i++) owner.step(LIMITS, true);
        owner.claimFlowTask(task, LIMITS, true); var raw = (net.minecraft.nbt.CompoundTag)owner.write();
        assertEquals(27, raw.getInt(FlowBlockNbtCodec.MARKER)); raw.putInt(FlowBlockNbtCodec.MARKER, 26);
        assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(raw, LIMITS.archive()));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"owner", "entry", "image", "work", "result_type", "missing_assembly"})
    void damagedTaskRootOrResultRetainsTheOriginalOwnerWithoutRunning(String mutation) {
        var owner = installed(); owner.startFlowTask(COPY, value(0), LIMITS, true);
        for (int i = 0; i < 3; i++) owner.step(LIMITS, true);
        var raw = (net.minecraft.nbt.CompoundTag)owner.write();
        var data = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
        var tasks = (DataValue.RecordValue)data.field("tasks");
        var state = (DataValue.RecordValue)((DataValue.ListValue)tasks.field("contexts")).values().getFirst();
        var assembly = (DataValue.RecordValue)state.field("assembly");
        switch (mutation) {
            case "owner" -> raw.putInt(FlowBlockNbtCodec.MARKER, 26);
            case "entry" -> state = patch(state, "entry", DataValue.NullValue.INSTANCE);
            case "image" -> assembly = patch(assembly, "image", new DataValue.TextValue("not_this_task"));
            case "work" -> state = patch(patch(state, "work", new DataValue.IntegerValue(4)), "operation", new DataValue.IntegerValue(program().steps().size()));
            case "result_type" -> {
                var registers = (DataValue.RecordValue)assembly.field("registers");
                assembly = patch(assembly, "registers", patch(registers, registers.fields().keySet().iterator().next(), new DataValue.TextValue("wrong")));
            }
        }
        state = patch(state, "assembly", assembly);
        if (mutation.equals("missing_assembly")) { var fields = new TreeMap<>(state.fields()); fields.remove("assembly"); state = new DataValue.RecordValue(fields); }
        raw.put("Data", ValueNbtCodec.encode(patch(data, "tasks", patch(tasks, "contexts", new DataValue.ListValue(List.of(state)))), LIMITS.archive()));
        var next = new ComputerBlockHost(() -> {}); next.read(raw); next.tick(LIMITS, true);
        assertFalse(next.loaded()); assertTrue(next.diagnostic().isPresent()); assertEquals(raw, next.write());
    }
    private static DataValue.RecordValue patch(DataValue.RecordValue value, String key, DataValue replacement) {
        var fields = new TreeMap<>(value.fields()); fields.put(key, replacement); return new DataValue.RecordValue(fields);
    }
    @Test void anOppositeXBusTaskFailsWithoutReplacingTheOriginalPhysicalWait() {
        var packet = (DataType.RecordType)DataType.typeOf(value(42));
        var p = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("main_read", "x0", "received", packet),
            new FlowProgram.Procedure("send", COPY, Map.of("packet", packet), Map.of(), Map.of()),
            new FlowProgram.Write("write", "x0", "packet"), new FlowProgram.EndProcedure("return"))).withAssembly();
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(p, new DataValue.RecordValue(Map.of()), LIMITS, true); owner.step(LIMITS, true);
        var main = owner.flowSnapshot().orElseThrow().state();
        var task = owner.startFlowTask(COPY, new DataValue.RecordValue(Map.of("packet", value(42))), LIMITS, true);
        owner.step(LIMITS, true); owner = restored(owner);
        assertEquals(main, owner.flowSnapshot().orElseThrow().state());
        assertEquals(ComputationException.Code.WRITE_CONFLICT, owner.flowTask(task).orElseThrow().diagnostic().orElseThrow().code());
        assertEquals(Optional.of(COPY), owner.flowTask(task).orElseThrow().entry());
    }
}
