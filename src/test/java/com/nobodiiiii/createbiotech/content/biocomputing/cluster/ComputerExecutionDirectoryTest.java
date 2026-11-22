package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.ComputerExecutionDirectory;
import com.nobodiiiii.createbiotech.content.biocomputing.network.ComputerExecutionCycle;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import org.junit.jupiter.api.*;

class ComputerExecutionDirectoryTest {
    @BeforeAll static void setup() throws Exception { bootstrap(); }

    @Test void observingPhysicalOwnersCannotRequestAChunkOrKeepAnAbsentInstanceRegistered() throws Exception {
        var f = fixture(); var computer = f.computer();
        f.world().requestingEntityReads = 0;
        computer.initialize();
        assertEquals(List.of(computer), ComputerExecutionDirectory.snapshot(f.world()).loaded());
        assertEquals(0, f.world().requestingEntityReads, "Directory reads must not renew UNKNOWN chunk tickets");
        f.world().chunks.unavailable = true;
        assertTrue(ComputerExecutionDirectory.snapshot(f.world()).loaded().isEmpty());
        assertEquals(0, f.world().requestingEntityReads, "No fallback from absent chunk to requesting world lookup");
        f.world().chunks.unavailable = false;
        assertTrue(ComputerExecutionDirectory.snapshot(f.world()).loaded().isEmpty(), "A loaded chunk alone does not resurrect an old roster entry");
    }

    @Test void aLoadedPeerOutsideThisExecutionRosterCannotBeConsumedThroughAConnection() throws Exception {
        var f = ClusterWorldIndicatorTest.writers(); var writer = f.fixture().computer(); var reader = f.caller();
        reader.installFlow(ClusterWorldIndicatorTest.reader(), new DataValue.RecordValue(Map.of()));
        writer.step(); reader.step(); reader.setPaused(false);
        var beforeWriter = writer.flowSnapshot(); var beforeReader = reader.flowSnapshot(); var world = f.fixture().world();
        var incomplete = new ComputerExecutionCycle(world, List.of(reader), world.clock).advanceRound(0);
        assertEquals(0, incomplete.exchanges());
        assertEquals("unavailable", incomplete.diagnostics().get(reader.communicationIdentity().orElseThrow()));
        assertEquals(beforeWriter, writer.flowSnapshot()); assertEquals(beforeReader, reader.flowSnapshot());
        world.clock++;
        assertEquals(1, new ComputerExecutionCycle(world, List.of(reader, writer), world.clock).advanceRound(0).exchanges());
    }

    @Test void worldCycleOnlyUsesCurrentPhysicalTickWitnessesAndBlockTickDoesNotSpendBudget() throws Exception {
        var f = fixture(); var computer = f.computer();
        computer.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"), new FlowProgram.EndForever("end"))),
            new DataValue.RecordValue(Map.of()));
        computer.setPaused(false); computer.initialize();
        assertFalse(new ComputerExecutionCycle(f.world()).advanceRound(0).progressed());
        assertEquals(0, computer.flowSnapshot().orElseThrow().state().work());
        computer.tick();
        assertEquals(0, computer.flowSnapshot().orElseThrow().state().work(), "Physical tick registers, but does not execute Flow");
        com.nobodiiiii.createbiotech.content.biocomputing.network.ComputerExecutionTicks.endTick(
            new net.neoforged.neoforge.event.tick.LevelTickEvent.Post(() -> true, f.world()));
        var spent = computer.flowSnapshot();
        assertEquals(LIMITS.hardware().workPerSlice(), spent.orElseThrow().state().work());
        assertFalse(new ComputerExecutionCycle(f.world()).advanceRound(0).progressed(), "Do not append a second activity grant");
        assertEquals(spent, computer.flowSnapshot());
        f.world().clock++;
        assertFalse(new ComputerExecutionCycle(f.world()).advanceRound(0).progressed(), "Loaded does not mean physically ticked now");
        assertEquals(spent, computer.flowSnapshot());
    }

    @Test void actualBehaviourLifecycleRegistersInstancesWithoutRestoringOrExecutingPrograms() throws Exception {
        var f = fixture(); var computer = f.computer(); var before = computer.snapshot();
        assertFalse(ComputerExecutionDirectory.snapshot(f.world()).loaded().contains(computer));
        computer.initialize();
        var first = ComputerExecutionDirectory.snapshot(f.world());
        assertEquals(java.util.List.of(computer), first.loaded());
        assertTrue(first.ticking().isEmpty(), "Initialization is not a physical tick witness");
        assertEquals(before, computer.snapshot(), "Registry observation is not runtime synchronization or execution");
        computer.tick();
        assertEquals(java.util.List.of(computer), ComputerExecutionDirectory.snapshot(f.world()).ticking());
        f.world().clock++;
        var next = ComputerExecutionDirectory.snapshot(f.world());
        assertEquals(java.util.List.of(computer), next.loaded());
        assertTrue(next.ticking().isEmpty(), "A previous tick witness cannot run a machine in the current tick");
        computer.unload();
        assertTrue(ComputerExecutionDirectory.snapshot(f.world()).loaded().isEmpty());
    }
}
