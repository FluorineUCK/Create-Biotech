package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterSignalRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.ComputerExecutionCycle;
import com.nobodiiiii.createbiotech.content.biocomputing.network.XBusPortState;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import net.minecraft.core.BlockPos;
import java.util.*;
import org.junit.jupiter.api.*;

class ComputerExecutionCycleTest {
    @BeforeAll static void setup() throws Exception { bootstrap(); }

    @Test void waitingWriterRoundDoesNotRescanForEachAdmissionSubstep() throws Exception {
        var f = ClusterWorldIndicatorTest.writers(); var world = f.fixture().world();
        var a = f.fixture().computer(); var b = f.caller();
        var cycle = new ComputerExecutionCycle(world, List.of(a, b), world.clock);
        cycle.advanceRound(0); // Both physical pins are now waiting; the first writer is retained.
        assertEquals(FlowRuntime.Status.WAITING_WRITE, a.flowSnapshot().orElseThrow().state().status());
        assertEquals(FlowRuntime.Status.WAITING_WRITE, b.flowSnapshot().orElseThrow().state().status());
        world.blockStateReads = 0; f.fixture().controller().enclosure(); long window = world.blockStateReads;
        for (long seed : List.of(1L, 2L)) {
            world.blockStateReads = 0;
            var result = cycle.advanceRound(seed);
            System.out.println("Waiting writer round window=" + window + " reads=" + world.blockStateReads);
            assertTrue(world.blockStateReads < 10 * window, "Inspection/retirement/selection must share their callback-free admission barrier");
            assertEquals(0, result.exchanges()); assertEquals(0, result.wakes());
            assertTrue(a.flowSnapshot().orElseThrow().receipts().isEmpty());
            assertTrue(b.flowSnapshot().orElseThrow().receipts().isEmpty());
        }
    }

    @Test void everyWaitKindReportsPausedPeersWithoutConsumingDataOrAdvancingThePausedComputer() throws Exception {
        var packet = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
        for (var step : List.<FlowProgram.Step>of(new FlowProgram.Read("read", "x0", "received", packet),
                new FlowProgram.Write("write", "x0", "packet"), new FlowProgram.AwaitReadable("await", "x0"))) {
            var f = ClusterWorldIndicatorTest.writers();
            var a = f.fixture().computer(); var b = f.caller();
            boolean writing = step instanceof FlowProgram.Write;
            var inputs = new DataValue.RecordValue(writing ? Map.of("packet", new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12)))) : Map.of());
            a.installFlow(new FlowProgram(writing ? Map.of("packet", packet) : Map.of(), List.of(step)), inputs);
            a.setPaused(false); b.setPaused(true);
            var before = b.flowSnapshot();
            for (String pin : b.hardware().limits().pins().xbus())
                assertEquals(XBusPortState.Mode.PAUSED, b.xbusPort("caller", pin).orElseThrow().mode());
            var cycle = new ComputerExecutionCycle(f.fixture().world(), List.of(b, a), f.fixture().world().clock);
            var result = cycle.advanceRound(0);
            assertEquals("paused", result.diagnostics().get(a.communicationIdentity().orElseThrow()), step.sourceId());
            assertEquals(0, result.exchanges()); assertEquals(0, result.wakes());
            assertEquals(before, b.flowSnapshot());
            assertEquals(inputs, a.flowSnapshot().orElseThrow().state().locals());
            assertTrue(a.flowSnapshot().orElseThrow().receipts().isEmpty());
            a.observeXBusExecution(result.diagnostics().get(a.communicationIdentity().orElseThrow()));
            assertTrue(((DataValue.ListValue)a.recentEvents().field("entries")).values().stream().anyMatch(e ->
                ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue("execution_paused"))));
        }
    }

    @Test void everySignalInputIsSampledBeforeAnyCpuRunsRegardlessOfSeedOrRosterOrder() throws Exception {
        var write = new FlowProgram(Map.of("level", DataType.Scalar.INTEGER), List.of(new FlowProgram.SignalWrite("drive", "p0", "level")));
        var read = new FlowProgram(Map.of(), List.of(new FlowProgram.SignalRead("read", "p0", "sample")));
        for (long seed : List.of(0L, 1L, 0x100010001L, -1L)) {
            var f = configured(write, value(2), read, new DataValue.RecordValue(Map.of()));
            var a = f.fixture().computer(); var b = f.caller(); a.setPaused(false); b.setPaused(false);
            var cycle = new ComputerExecutionCycle(f.fixture().world(), List.of(b, a), f.fixture().world().clock);
            assertEquals(2, cycle.advanceRound(seed).work());
            assertEquals(new DataValue.IntegerValue(0), b.flowSnapshot().orElseThrow().state().locals().field("sample"));
            assertEquals(Map.of("p0", 2), a.signalOutputs().orElseThrow().outputs());
        }
    }

    @Test void aPausedThirdComputerDoesNotConsumeOrPreventOtherPeersRendezvousOnTheSameBus() throws Exception {
        var packet = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
        for (long seed : List.of(0L, 1L, 0x100010001L, -1L)) {
            var f = ClusterWorldIndicatorTest.writers(); var world = f.fixture().world();
            var a = f.fixture().computer(); var b = f.caller();
            b.installFlow(ClusterWorldIndicatorTest.reader(), new DataValue.RecordValue(Map.of())); b.setPaused(false);
            var thirdPosition = new BlockPos(1, 1, 2);
            var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
            var c = addComputer(world, thirdPosition, new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of()), "third");
            c.installFlow(new FlowProgram(Map.of("packet", packet), List.of(new FlowProgram.Write("paused", "x0", "packet"))),
                new DataValue.RecordValue(Map.of("packet", new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(999))))));
            var controller = f.fixture().controller(); var config = controller.routingState().orElseThrow().configuration();
            var members = new LinkedHashMap<>(config.members());
            members.put("C", new ClusterConfiguration.Member(c.communicationIdentity().orElseThrow(), thirdPosition));
            var wires = new LinkedHashMap<>(config.wires());
            wires.put("branch", new XBusWire(new XBusWire.Pin("A", "worker", "x0"), new XBusWire.Pin("C", "third", "x0"), true));
            assertEquals("configured", controller.configure(new ClusterConfiguration(members, config.ports(), config.connections(), wires), f.fixture().player()));
            c.step(); // Paused while actually waiting to write, not merely a dormant empty program.
            var before = c.flowSnapshot();
            var cycle = new ComputerExecutionCycle(world, List.of(c, b, a), world.clock);
            int exchanges = 0;
            for (int round = 0; round < 5; round++) {
                var result = cycle.advanceRound(seed + round); exchanges += result.exchanges();
                assertFalse(result.diagnostics().containsValue("paused"), "A single paused branch cannot hide effective peers");
            }
            assertEquals(1, exchanges); assertEquals(before, c.flowSnapshot());
            assertEquals(FlowRuntime.Status.COMPLETE, a.flowSnapshot().orElseThrow().state().status());
            assertEquals(FlowRuntime.Status.COMPLETE, b.flowSnapshot().orElseThrow().state().status());
            assertEquals(new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12))),
                b.flowSnapshot().orElseThrow().state().locals().field("received"));
            assertTrue(c.flowSnapshot().orElseThrow().receipts().isEmpty());
        }
    }

    @Test void awakeButUnreadyAndUnavailablePeersAreNotMislabeledAsPaused() throws Exception {
        for (String condition : List.of("awake", "closed", "broken_shell", "missing")) {
            var f = ClusterWorldIndicatorTest.writers(); var world = f.fixture().world();
            var a = f.fixture().computer(); var b = f.caller();
            switch (condition) {
                case "closed" -> { b.setPaused(true); b.disableEndpoint(); }
                case "broken_shell" -> { b.setPaused(true); world.states.remove(BlockPos.ZERO); }
                case "missing" -> { b.setPaused(true); world.entities.remove(FAR); }
            }
            var result = new ComputerExecutionCycle(world, List.of(a, b), world.clock).advanceRound(0);
            String code = result.diagnostics().get(a.communicationIdentity().orElseThrow());
            assertNotNull(code, condition); assertNotEquals("paused", code, condition);
            if (condition.equals("awake")) assertEquals("not_ready", code);
            else assertNotEquals("not_ready", code, condition);
            assertEquals(0, result.exchanges());
        }
    }

    @Test void manyInstructionRoundsDoNotAccelerateSleepOrRefreshAnotherComputersBudget() throws Exception {
        var loop = new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"), new FlowProgram.EndForever("end")));
        var f = configured(loop, new DataValue.RecordValue(Map.of()), sleeper(), sleepInput(7));
        var a = f.fixture().computer(); var b = f.caller(); a.setPaused(false); b.setPaused(false);
        var world = f.fixture().world(); var computers = List.of(b, a);
        var cycle = new ComputerExecutionCycle(world, computers, world.clock);
        long work = 0; for (int round = 0; round < 64; round++) work += cycle.advanceRound(round).work();
        assertEquals(a.hardware().limits().hardware().workPerSlice(), a.flowSnapshot().orElseThrow().state().work());
        assertEquals(a.hardware().limits().hardware().workPerSlice() + 2, work);
        assertEquals(3, b.flowSnapshot().orElseThrow().state().sleepTicks());
        var duplicate = new ComputerExecutionCycle(world, computers, world.clock);
        assertFalse(duplicate.advanceRound(99).progressed());
        assertEquals(3, b.flowSnapshot().orElseThrow().state().sleepTicks());
        b.setPaused(true); world.clock++;
        new ComputerExecutionCycle(world, computers, world.clock).advanceRound(100);
        assertEquals(3, b.flowSnapshot().orElseThrow().state().sleepTicks());
        b.setPaused(false); world.clock++;
        var resumed = new ComputerExecutionCycle(world, computers, world.clock);
        for (int round = 0; round < 64; round++) resumed.advanceRound(round);
        assertEquals(2, b.flowSnapshot().orElseThrow().state().sleepTicks());
        world.entities.remove(FAR); world.clock++;
        new ComputerExecutionCycle(world, computers, world.clock).advanceRound(200);
        assertEquals(2, b.flowSnapshot().orElseThrow().state().sleepTicks(), "An absent physical owner is not ticked");
    }

    @Test void pausedSignalOwnerKeepsItsOutputWhileControllerFailureDoesNotFreezeLocalComputing() throws Exception {
        var read = new FlowProgram(Map.of(), List.of(new FlowProgram.SignalRead("read", "p0", "sample")));
        var f = configured(read, new DataValue.RecordValue(Map.of()), sleeper(), sleepInput(7));
        var a = f.fixture().computer(); var b = f.caller(); b.step(); a.setPaused(false);
        var before = b.flowSnapshot(); var world = f.fixture().world();
        new ComputerExecutionCycle(world, List.of(a, b), world.clock).advanceRound(0);
        assertEquals(new DataValue.IntegerValue(7), a.flowSnapshot().orElseThrow().state().locals().field("sample"));
        assertEquals(before, b.flowSnapshot());
        a.reset(); a.setPaused(false); world.entities.remove(CONTROLLER); world.clock++;
        new ComputerExecutionCycle(world, List.of(a, b), world.clock).advanceRound(1);
        assertEquals(FlowRuntime.Status.FAILED, a.flowSnapshot().orElseThrow().state().status());
        assertFalse(a.flowSnapshot().orElseThrow().state().locals().fields().containsKey("sample"));
        assertEquals(before, b.flowSnapshot());
    }

    @Test void anOldWorldTickCycleCannotResumeItsUnusedBudgetLater() throws Exception {
        var loop = new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"), new FlowProgram.EndForever("end")));
        var empty = new DataValue.RecordValue(Map.of());
        var f = configured(loop, empty, loop, empty);
        var a = f.fixture().computer(); var b = f.caller(); a.setPaused(false); b.setPaused(false);
        var cycle = new ComputerExecutionCycle(f.fixture().world(), List.of(b, a), f.fixture().world().clock);
        assertEquals(2, cycle.advanceRound(0).work());
        var beforeA = a.flowSnapshot(); var beforeB = b.flowSnapshot();
        f.fixture().world().clock++;
        assertThrows(IllegalStateException.class, () -> cycle.advanceRound(1));
        assertEquals(beforeA, a.flowSnapshot()); assertEquals(beforeB, b.flowSnapshot());
    }
}
