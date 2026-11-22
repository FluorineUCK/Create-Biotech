package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.XBusPortState;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowTaskXBusTest {
    private static final ModuleDefinition.Key RECEIVE = new ModuleDefinition.Key("receive", 1);
    private static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("number", DataType.Scalar.INTEGER));
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    private static final DataValue.RecordValue PAYLOAD = new DataValue.RecordValue(Map.of("number", new DataValue.IntegerValue(42)));
    private static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, 100_000, 1),
        FlowArchiveTest.ARCHIVE, MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 3));
    private static ComputerBlockHost install(FlowProgram program, DataValue.RecordValue input) {
        return install(program, input, LIMITS);
    }
    private static ComputerBlockHost install(FlowProgram program, DataValue.RecordValue input, ComputerHostLimits limits) {
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(program, input, limits, true);
        owner.setPaused(false, limits, true); owner.step(limits, true); return owner;
    }
    @Test void rootOutputSurvivesRepeatedChildReceivesReloadAndReceiptRetirement() {
        var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), 3, true, true));
        var program = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.SignalWrite("drive", "p0", "ticks"), new FlowProgram.Sleep("main", "ticks"),
            new FlowProgram.Procedure("receiver", RECEIVE, Map.of(), Map.of("result", PACKET), Map.of("result", "received")),
            new FlowProgram.Read("read", "x1", "received", PACKET), new FlowProgram.EndProcedure("return"))).withAssembly();
        var reader = install(program, new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(1000))), limits);
        reader.step(limits, true);
        var root = reader.flowSnapshot().orElseThrow().state().flow();
        var writer = install(new FlowProgram(Map.of("packet", PACKET), List.of(
            new FlowProgram.Repeat("rounds", new FlowInput.Literal(new DataValue.IntegerValue(3))),
            new FlowProgram.Write("write", "x0", "packet"), new FlowProgram.EndRepeat("end"))).withAssembly(),
            new DataValue.RecordValue(Map.of("packet", PAYLOAD)), limits);
        for (int round = 0; round < 3; round++) {
            var child = reader.startFlowTask(RECEIVE, EMPTY, limits, true);
            for (int step = 0; step < 80; step++) {
                reader.step(limits, true); writer.step(limits, true);
                if (reader.flowTask(child).orElseThrow().status() == FlowRuntime.Status.WAITING_READ
                        && writer.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.WAITING_WRITE) break;
            }
            assertEquals(XBusExchange.Status.EXCHANGED, reader.exchange("x1", limits, true, writer, "x0", limits, true));
            assertFalse(reader.flowSnapshot().orElseThrow().receipts().isEmpty());
            reader.setPaused(true, limits, true);
            var disk = reader.write(); reader.unload(); reader = new ComputerBlockHost(() -> {});
            reader.read(disk); reader.tick(limits, true); assertEquals(disk, reader.write());
            reader.setPaused(false, limits, true);
            for (int step = 0; step < 80 && reader.flowTask(child).orElseThrow().status() != FlowRuntime.Status.COMPLETE; step++)
                reader.step(limits, true);
            assertEquals(PAYLOAD, reader.claimFlowTask(child, limits, true).locals().field("result"));
            var saved = reader.flowSnapshot().orElseThrow();
            assertEquals(root, saved.state().flow()); assertEquals(0, saved.control().generation());
            assertEquals(Map.of("p0", 15), saved.state().signals().outputs());
            assertTrue(saved.receipts().isEmpty(), "A finite child retires only its own consumed payload receipts");
            assertTrue(saved.tasks().contexts().isEmpty());
        }
        assertEquals(1, reader.flowSnapshot().orElseThrow().tasks().slots().entries().size());
        assertEquals(2, reader.flowSnapshot().orElseThrow().tasks().slots().entries().getFirst().incarnation());
    }
    @Test void childReceivesAtItsPhysicalPinWhileMainKeepsItsOwnContinuation() {
        var program = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("main", "ticks"),
            new FlowProgram.Procedure("receiver", RECEIVE, Map.of(), Map.of("result", PACKET), Map.of("result", "received")),
            new FlowProgram.Read("read", "x1", "received", PACKET), new FlowProgram.EndProcedure("return")));
        var reader = install(program, new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(1000))));
        var main = reader.flowSnapshot().orElseThrow().state();
        UUID child = reader.startFlowTask(RECEIVE, EMPTY, LIMITS, true); reader.step(LIMITS, true);
        assertEquals(FlowRuntime.Status.WAITING_READ, reader.flowTask(child).orElseThrow().status());
        var observed = XBusPortState.of(reader.flowSnapshot().orElseThrow(), LIMITS.pins(), "x1").orElseThrow();
        assertEquals(child, observed.flow()); assertEquals(XBusPortState.Mode.READ, observed.mode());
        var writer = install(new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("write", "x0", "packet"))),
            new DataValue.RecordValue(Map.of("packet", PAYLOAD)));
        assertEquals(XBusExchange.Status.EXCHANGED, reader.exchange("x1", LIMITS, true, writer, "x0", LIMITS, true));
        assertEquals(main, reader.flowSnapshot().orElseThrow().state());
        assertEquals(PAYLOAD, reader.flowTask(child).orElseThrow().locals().field("received"));
        reader.step(LIMITS, true); assertEquals(PAYLOAD, reader.claimFlowTask(child, LIMITS, true).locals().field("result"));
        assertTrue(reader.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FlowProgram.Execution.class)
    void sharedWaitCannotSilentlyFallBackToFifoOrConsumeOnlyTheFirstReader(FlowProgram.Execution execution) {
        var program = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("main", "ticks"),
            new FlowProgram.Procedure("receiver", RECEIVE, Map.of(), Map.of("result", PACKET), Map.of("result", "received")),
            new FlowProgram.Read("read", "x1", "received", PACKET), new FlowProgram.EndProcedure("return")));
        if (execution == FlowProgram.Execution.REGISTER_V1) program = program.withAssembly();
        var reader = install(program, new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(1000))));
        UUID first = reader.startFlowTask(RECEIVE, EMPTY, LIMITS, true);
        UUID second = reader.startFlowTask(RECEIVE, EMPTY, LIMITS, true);
        reader.step(LIMITS, true); reader.step(LIMITS, true);
        assertEquals(FlowRuntime.Status.WAITING_READ, reader.flowTask(first).orElseThrow().status());
        assertEquals(FlowRuntime.Status.WAITING_READ, reader.flowTask(second).orElseThrow().status());
        var writer = install(new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("write", "x0", "packet"))),
            new DataValue.RecordValue(Map.of("packet", PAYLOAD)));
        assertEquals(XBusExchange.Status.EXCHANGED, reader.exchange("x1", LIMITS, true, writer, "x0", LIMITS, true));
        assertEquals(PAYLOAD, reader.flowTask(first).orElseThrow().locals().field("received"));
        assertEquals(PAYLOAD, reader.flowTask(second).orElseThrow().locals().field("received"));
    }
}
