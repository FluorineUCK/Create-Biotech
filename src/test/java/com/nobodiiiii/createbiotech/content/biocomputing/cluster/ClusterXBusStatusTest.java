package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.*;

class ClusterXBusStatusTest {
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    @BeforeAll static void setup() throws Exception { bootstrap(); }
    private static FlowProgram probe() {
        return new FlowProgram(Map.of(), List.of(new FlowProgram.XBusStatus("probe", "x0", "code")));
    }

    @Test void roundStatusNeedsOneDiscoveryAndOneFinalPhysicalProof() throws Exception {
        var f = ClusterWorldIndicatorTest.writers(); var world = f.fixture().world();
        var reader = f.fixture().computer(); var writer = f.caller();
        reader.installFlow(probe().withAssembly(), EMPTY); reader.setPaused(false); writer.step();
        world.blockStateReads = 0;
        assertEquals(EnclosureScanner.Status.READY, f.fixture().controller().enclosure().status());
        long enclosureReads = world.blockStateReads;
        assertTrue(enclosureReads > 0);
        world.blockStateReads = 0;
        assertEquals(15, reader.sampleFlowRoundSignals().get("x0"));
        long reads = world.blockStateReads;
        assertTrue(reads >= 2 * enclosureReads && reads < 3 * enclosureReads,
            () -> "XBUS.STATUS should discover then validate once: reads=" + reads + ", enclosure=" + enclosureReads);
    }

    @Test void connectedStatusReadSeesWriterWithoutReceivingOrChangingEitherOwnerOrHistory() throws Exception {
        var f = ClusterWorldIndicatorTest.writers(); var a = f.fixture().computer(); var b = f.caller();
        a.installFlow(probe(), EMPTY); a.setPaused(false); b.step();
        assertEquals(FlowRuntime.Status.WAITING_WRITE, b.flowSnapshot().orElseThrow().state().status());
        var beforeA = a.flowSnapshot(); var beforeB = b.flowSnapshot();
        var history = XBusHistorySavedData.get(f.fixture().world());
        var beforeHistory = history.save(new CompoundTag(), null); history.setDirty(false);
        assertEquals(15, a.sampleFlowSignals().get("x0"));
        assertEquals(beforeA, a.flowSnapshot()); assertEquals(beforeB, b.flowSnapshot());
        assertEquals(beforeHistory, history.save(new CompoundTag(), null)); assertFalse(history.isDirty());
        a.step();
        var result = a.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, result.state().status());
        assertEquals(new DataValue.IntegerValue(15), result.state().locals().field("code"));
        assertTrue(result.receipts().isEmpty()); assertTrue(result.pinClaims().entries().isEmpty());
        assertEquals(SimpleIoState.EMPTY, result.state().signals()); assertEquals(beforeB, b.flowSnapshot());
        assertEquals(beforeHistory, history.save(new CompoundTag(), null)); assertFalse(history.isDirty());
    }

    @Test void failedOrPausedBindingsReadZeroAndReconnectingReadsTheSameUnconsumedWriter() throws Exception {
        for (String fault : List.of("line", "grant", "shell", "missing", "paused", "self_paused")) {
            var f = ClusterWorldIndicatorTest.writers(); var world = f.fixture().world();
            var a = f.fixture().computer(); var b = f.caller(); var controller = f.fixture().controller();
            a.installFlow(probe(), EMPTY); a.setPaused(false); b.step();
            var config = controller.routingState().orElseThrow().configuration();
            var shell = world.states.get(net.minecraft.core.BlockPos.ZERO); var entity = world.entities.get(FAR);
            switch (fault) {
                case "line" -> {
                    var wire = config.wires().get("data");
                    controller.configure(new ClusterConfiguration(config.members(), config.ports(), config.connections(),
                        Map.of("data", new XBusWire(wire.left(), wire.right(), false))), f.fixture().player());
                }
                case "grant" -> b.disableEndpoint();
                case "shell" -> world.states.remove(net.minecraft.core.BlockPos.ZERO);
                case "missing" -> world.entities.remove(FAR);
                case "paused" -> b.setPaused(true);
                case "self_paused" -> a.setPaused(true);
            }
            var before = b.flowSnapshot();
            assertEquals(0, a.sampleFlowSignals().get("x0"), fault);
            a.step(); assertEquals(new DataValue.IntegerValue(0), a.flowSnapshot().orElseThrow().state().locals().field("code"), fault);
            assertTrue(a.flowSnapshot().orElseThrow().receipts().isEmpty(), fault);
            assertEquals(before, b.flowSnapshot(), fault);
            switch (fault) {
                case "line" -> controller.configure(config, f.fixture().player());
                case "grant" -> b.configureEndpoint(new EndpointConfiguration("caller", true));
                case "shell" -> world.states.put(net.minecraft.core.BlockPos.ZERO, shell);
                case "missing" -> world.entities.put(FAR, entity);
                case "paused" -> b.setPaused(false);
                case "self_paused" -> a.setPaused(false);
            }
            assertEquals(15, a.sampleFlowSignals().get("x0"), fault);
            assertEquals(FlowRuntime.Status.WAITING_WRITE, b.flowSnapshot().orElseThrow().state().status(), fault);
        }
    }

    @Test void statusIsFrozenBeforeTheRoundAndNeverObservesSameRoundWriterEntry() throws Exception {
        for (long seed : List.of(0L, 1L, -1L, 0x100010001L)) {
            var f = ClusterWorldIndicatorTest.writers(); var a = f.fixture().computer(); var b = f.caller();
            a.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.XBusStatus("first", "x0", "first"),
                new FlowProgram.XBusStatus("second", "x0", "second"))), EMPTY); a.setPaused(false);
            var cycle = new ComputerExecutionCycle(f.fixture().world(), List.of(b, a), f.fixture().world().clock);
            assertEquals(0, cycle.advanceRound(seed).exchanges());
            assertEquals(new DataValue.IntegerValue(0), a.flowSnapshot().orElseThrow().state().locals().field("first"));
            assertEquals(0, cycle.advanceRound(seed + 1).exchanges());
            assertEquals(new DataValue.IntegerValue(15), a.flowSnapshot().orElseThrow().state().locals().field("second"));
            assertTrue(b.flowSnapshot().orElseThrow().receipts().isEmpty());
        }
    }
    @Test void waitingReadersAndReadableProbesNeverAssertSendingOccupancy() throws Exception {
        for (var step : List.<FlowProgram.Step>of(new FlowProgram.Read("read", "x0", "packet", new DataType.RecordType(Map.of())),
            new FlowProgram.AwaitReadable("wait", "x0"))) {
            var f = ClusterWorldIndicatorTest.writers(); var a = f.fixture().computer(); var b = f.caller();
            a.installFlow(probe(), EMPTY); a.setPaused(false);
            b.installFlow(new FlowProgram(Map.of(), List.of(step)), EMPTY); b.setPaused(false); b.step();
            var peer = b.flowSnapshot(); a.step();
            assertEquals(new DataValue.IntegerValue(0), a.flowSnapshot().orElseThrow().state().locals().field("code"));
            assertEquals(peer, b.flowSnapshot());
            assertTrue(a.flowSnapshot().orElseThrow().pinClaims().entries().isEmpty());
        }
    }

    @Test void nativeOnlyProbeSamplesTheInstalledStatusPinWithoutConsumingTheConnectedWriter() throws Exception {
        var f=ClusterWorldIndicatorTest.writers();var a=f.fixture().computer();var b=f.caller();
        var key=new ModuleDefinition.Key("probe",1);
        var p=new FlowProgram(Map.of(),List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of(),Map.of("result","code"))),
            new FlowProgram.Procedure("native",key,Map.of(),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","value"),FlowBlockRules.NONE,
                Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource("REG value INTEGER\nXBUS.STATUS value x0\nRET ; XBUS.STATUS value not.a.pin"))),new FlowProgram.EndProcedure("end"))).withAssembly();
        a.installFlow(p,EMPTY);a.setPaused(false);b.step();var beforeA=a.flowSnapshot();var beforeB=b.flowSnapshot();
        var history=XBusHistorySavedData.get(f.fixture().world());var disk=history.save(new CompoundTag(),null);history.setDirty(false);
        var samples=a.sampleFlowSignals();assertEquals(15,samples.get("x0"));assertFalse(samples.containsKey("not.a.pin"));
        assertEquals(beforeA,a.flowSnapshot());assertEquals(beforeB,b.flowSnapshot());assertEquals(disk,history.save(new CompoundTag(),null));assertFalse(history.isDirty());
        for(int i=0;i<12&&a.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++)a.step();
        var result=a.flowSnapshot().orElseThrow();assertEquals(new DataValue.IntegerValue(15),result.state().locals().field("code"));
        assertEquals(SimpleIoState.EMPTY,result.state().signals());assertTrue(result.receipts().isEmpty());assertTrue(result.pinClaims().entries().isEmpty());
        assertEquals(beforeB,b.flowSnapshot());assertEquals(disk,history.save(new CompoundTag(),null));assertFalse(history.isDirty());
    }
}
