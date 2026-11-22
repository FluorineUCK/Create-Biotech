package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ComputerExecutionPausedResultTest {
    @BeforeAll static void setup() throws Exception { bootstrap(); }
    @Test void stalePauseObservationAndRevokedRoutesCannotFinishAnOperation() throws Exception {
        for (String edit : List.of("resume", "close", "remove_wire")) {
            var f = ClusterWorldIndicatorTest.writers(); var a = f.fixture().computer(); var b = f.caller();
            a.step(); a.setPaused(false); b.setPaused(true);
            var network = XBusConnectedNetwork.capture(f.fixture().world(), a, "x0").connected().orElseThrow().network();
            if (edit.equals("resume")) b.setPaused(false);
            else if (edit.equals("close")) b.disableEndpoint();
            else f.fixture().world().states.remove(net.minecraft.core.BlockPos.ZERO);
            var beforeA = a.flowSnapshot(); var beforeB = b.flowSnapshot();
            assertNotEquals(XBusExchange.Status.PAUSED, network.rejectPaused(new XBusNetwork.Pin(a.communicationIdentity().orElseThrow(), "x0")), edit);
            assertEquals(beforeA, a.flowSnapshot()); assertEquals(beforeB, b.flowSnapshot());
        }
    }
    @Test void everyExplicitResultLeavesTheWaitAndRunsFollowingInstructionsWithinItsOwnBudget() throws Exception {
        var packet = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
        for (var step : List.<FlowProgram.Step>of(new FlowProgram.Read("read", "x0", "result", packet, true),
                new FlowProgram.Write("write", "x0", "packet", Optional.of("result")),
                new FlowProgram.AwaitReadable("await", "x0", Optional.of("result")))) {
            var f = ClusterWorldIndicatorTest.writers(); var a = f.fixture().computer(); var b = f.caller();
            var initial = new DataValue.RecordValue(Map.of("packet", new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12))), "ticks", new DataValue.IntegerValue(0)));
            a.installFlow(new FlowProgram(Map.of("packet", packet, "ticks", DataType.Scalar.INTEGER),
                List.of(step, new FlowProgram.Sleep("after", "ticks"))), initial);
            a.setPaused(false); b.setPaused(true); var untouched = b.flowSnapshot();
            var cycle = new ComputerExecutionCycle(f.fixture().world(), List.of(a,b), f.fixture().world().clock);
            var first = cycle.advanceRound(0);
            assertEquals(FlowRuntime.Status.READY, a.flowSnapshot().orElseThrow().state().status());
            assertEquals(1, a.flowSnapshot().orElseThrow().state().cursor());
            assertEquals(0, first.exchanges()); assertEquals(0, first.wakes());
            cycle.advanceRound(1);
            var done = a.flowSnapshot().orElseThrow();
            assertEquals(FlowRuntime.Status.COMPLETE, done.state().status());
            assertEquals(2, done.state().work());
            assertEquals(new DataValue.BooleanValue(false), ((DataValue.RecordValue)done.state().locals().field("result")).field("ok"));
            assertTrue(done.receipts().isEmpty()); assertEquals(untouched, b.flowSnapshot());
            b.setPaused(false); cycle.advanceRound(2);
            assertEquals(done, a.flowSnapshot().orElseThrow(), "Resuming a peer does not retry a completed failed operation");
        }
    }
}
