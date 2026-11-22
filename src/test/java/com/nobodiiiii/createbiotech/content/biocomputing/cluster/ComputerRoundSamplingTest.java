package com.nobodiiiii.createbiotech.content.biocomputing.cluster;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.ComputerExecutionCycle;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.*;

class ComputerRoundSamplingTest {
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    @BeforeAll static void setup() throws Exception { bootstrap(); }

    @Test void ordinaryInstructionDoesNotScanWorldForAStatusReadLaterInTheProgram() throws Exception {
        var f = ClusterWorldIndicatorTest.writers();
        var a = f.fixture().computer(); var b = f.caller(); var world = f.fixture().world();
        var key = new ModuleDefinition.Key("probe", 1);
        var program = new FlowProgram(Map.of(), List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of(), Map.of("result", "code"))),
            new FlowProgram.Procedure("native", key, Map.of(), Map.of("result", DataType.Scalar.INTEGER),
                Map.of("result", "value"), FlowBlockRules.NONE,
                Optional.of(new AssemblySource("REG value INTEGER\nCONST value 7\nXBUS.STATUS value x0\nRET"))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        a.installFlow(program, EMPTY); a.setPaused(false); b.step();
        var beforeB = b.flowSnapshot();
        // The writer is already waiting; this test advances only the reader's CPU.
        var cycle = new ComputerExecutionCycle(world, List.of(a), world.clock);
        world.blockStateReads = 0;
        assertEquals(1, cycle.advanceRound(0).work());
        assertEquals(0, world.blockStateReads, "CALL must not read a device that only a later instruction uses");
        for (int round = 1; round < 32 && a.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; round++)
            cycle.advanceRound(round);
        assertEquals(FlowRuntime.Status.COMPLETE, a.flowSnapshot().orElseThrow().state().status());
        assertEquals(new DataValue.IntegerValue(15), a.flowSnapshot().orElseThrow().state().locals().field("code"));
        assertTrue(world.blockStateReads > 0, "The actual status read must still validate the physical network");
        assertEquals(beforeB, b.flowSnapshot());
    }

    @Test void statusChecksStillSeeBreakAndRepairBetweenRoundsOfTheSameTick() throws Exception {
        for (boolean assembly : List.of(false, true)) {
            var f = ClusterWorldIndicatorTest.writers(); var world = f.fixture().world();
            var a = f.fixture().computer(); var b = f.caller();
            var program = new FlowProgram(Map.of(), List.of(
                new FlowProgram.XBusStatus("first", "x0", "first"),
                new FlowProgram.XBusStatus("broken", "x0", "broken"),
                new FlowProgram.XBusStatus("repaired", "x0", "repaired")));
            a.installFlow(assembly ? program.withAssembly() : program, EMPTY); a.setPaused(false); b.step();
            var beforeB = b.flowSnapshot(); var cycle = new ComputerExecutionCycle(world, List.of(a), world.clock);
            cycle.advanceRound(0);
            var shell = world.states.remove(BlockPos.ZERO);
            cycle.advanceRound(1);
            world.states.put(BlockPos.ZERO, shell);
            cycle.advanceRound(2);
            var saved = a.flowSnapshot().orElseThrow();
            assertEquals(FlowRuntime.Status.COMPLETE, saved.state().status());
            assertEquals(new DataValue.RecordValue(Map.of("first", new DataValue.IntegerValue(15),
                "broken", new DataValue.IntegerValue(0), "repaired", new DataValue.IntegerValue(15))), saved.state().locals());
            assertTrue(saved.receipts().isEmpty()); assertEquals(beforeB, b.flowSnapshot());
        }
    }

    @Test void waitingMainStillSamplesNativeStatusInAChildCallAfterOwnerReload() throws Exception {
        var f = ClusterWorldIndicatorTest.writers(); var world = f.fixture().world(); var b = f.caller();
        var block = (Host) f.fixture().computer().blockEntity;
        var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), 3));
        var constructor = ComputerBehaviour.class.getDeclaredConstructor(com.simibubi.create.foundation.blockEntity.SmartBlockEntity.class,
            java.util.function.BooleanSupplier.class, java.util.function.Supplier.class);
        constructor.setAccessible(true);
        var a = (ComputerBehaviour) constructor.newInstance(block, (java.util.function.BooleanSupplier) () -> true,
            (java.util.function.Supplier<HardwareSelection>) () -> new HardwareSelection(HardwareSelection.Status.READY, Optional.empty(), "sampling-test", limits));
        block.computer = a;
        var child = new ModuleDefinition.Key("child", 1); var probe = new ModuleDefinition.Key("probe", 1);
        var output = Map.<String, DataType>of("result", DataType.Scalar.INTEGER);
        var program = new FlowProgram(Map.of(), List.of(
            new FlowProgram.TaskStart("launch", child, Map.of(), "handle"),
            new FlowProgram.TaskJoin("join", child, "handle", "outcome"),
            new FlowProgram.Procedure("child", child, Map.of(), output, Map.of("result", "value")),
            new FlowProgram.Invoke("call", probe, new FlowCallBindings(Map.of(), Map.of("result", "value"))),
            new FlowProgram.EndProcedure("child_return"),
            new FlowProgram.Procedure("probe", probe, Map.of(), output, Map.of("result", "value"), FlowBlockRules.NONE,
                Optional.of(new AssemblySource("REG value INTEGER\nCONST value 7\nXBUS.STATUS value x0\nRET"))),
            new FlowProgram.EndProcedure("return"))).withAssembly();
        a.installFlow(program, EMPTY); a.configureEndpoint(new EndpointConfiguration("worker", true));
        var config = f.fixture().controller().routingState().orElseThrow().configuration();
        var members = new LinkedHashMap<>(config.members());
        members.put("A", new ClusterConfiguration.Member(a.communicationIdentity().orElseThrow(), MEMBER));
        assertEquals("configured", f.fixture().controller().configure(new ClusterConfiguration(members, config.ports(), config.connections(), config.wires()), f.fixture().player()));
        a.setPaused(false); b.step(); var beforeB = b.flowSnapshot(); boolean reloaded = false;
        for (int tick = 0; tick < 8; tick++) {
            var cycle = new ComputerExecutionCycle(world, List.of(a), world.clock);
            for (int round = 0; round < 16; round++) {
                cycle.advanceRound(round);
                var saved = a.flowSnapshot().orElseThrow();
                if (!reloaded && saved.state().status() == FlowRuntime.Status.WAITING_TASK) {
                    var tag = new CompoundTag(); a.write(tag, RegistryAccess.EMPTY, false);
                    a.read(tag, RegistryAccess.EMPTY, false); reloaded = true;
                    break; // New owner must rejoin a new activity tick; do not reuse the old cycle.
                }
            }
            if (a.flowSnapshot().filter(saved -> saved.state().status() == FlowRuntime.Status.COMPLETE).isPresent()) break;
            world.clock++;
        }
        var saved = a.flowSnapshot().orElseThrow(); assertTrue(reloaded);
        assertEquals(FlowRuntime.Status.COMPLETE, saved.state().status(), saved.state().diagnostic().toString());
        var outcome = (DataValue.RecordValue) saved.state().locals().field("outcome");
        assertEquals(new DataValue.BooleanValue(true), outcome.field("ok"));
        assertEquals(new DataValue.RecordValue(Map.of("result", new DataValue.IntegerValue(15))), outcome.field("value"));
        assertTrue(saved.tasks().contexts().isEmpty()); assertTrue(saved.receipts().isEmpty()); assertEquals(beforeB, b.flowSnapshot());
    }
}
