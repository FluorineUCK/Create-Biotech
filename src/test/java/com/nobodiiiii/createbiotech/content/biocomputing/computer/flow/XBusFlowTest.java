package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;
import org.junit.jupiter.api.Test;

class XBusFlowTest {
    static final ResourceLimits LIMITS = new ResourceLimits(1000, 100_000, 1);
    static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
    static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());

    @Test void idleRecipientIsNotReadingAndOnlyExecutedReadAllowsOneTransfer() {
        var sender = writer(); var receiver = reader();
        assertEquals(1, sender.advance(1));
        assertEquals(FlowRuntime.Status.WAITING_WRITE, sender.checkpoint().status());
        var before = sender.checkpoint();
        assertEquals(XBusExchange.Status.NOT_READY, exchange(sender, receiver).status());
        assertEquals(before, sender.checkpoint());
        assertEquals(0, receiver.checkpoint().work(), "Matching must not run a recipient event");
        assertEquals(1, receiver.advance(1));
        var prepared = exchange(sender, receiver);
        assertEquals(XBusExchange.Status.PREPARED, prepared.status());
        assertEquals(before, sender.checkpoint(), "Preparing is not publication");
        assertEquals(XBusExchange.Status.EXCHANGED, prepared.commit());
        assertEquals(FlowRuntime.Status.COMPLETE, sender.checkpoint().status());
        assertEquals(FlowRuntime.Status.COMPLETE, receiver.checkpoint().status());
        assertEquals(packet(12), receiver.checkpoint().locals().field("input"));
        assertEquals(XBusExchange.Status.STALE, prepared.commit());
    }
    @Test void waitingForReadableWakesOnlyTheWaiterAndDoesNotConsumeOrFinishTheWrite() {
        var sender = writer();
        var receiver = runtime(new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("slx", "x1"),
            new FlowProgram.Read("read", "x1", "input", PACKET))), EMPTY);
        sender.advance(1); receiver.advance(1); var before = sender.checkpoint();
        var wake = exchange(sender, receiver);
        assertEquals(XBusExchange.Status.PREPARED, wake.status());
        assertEquals(XBusExchange.Status.READABLE, wake.commit());
        assertEquals(before, sender.checkpoint(), "slx does not acknowledge a pending write");
        assertEquals(FlowRuntime.Status.READY, receiver.checkpoint().status());
        assertEquals(Optional.of("read"), receiver.sourceId());
        assertEquals(EMPTY, receiver.checkpoint().locals());
        assertEquals(XBusExchange.Status.NOT_READY, exchange(sender, receiver).status());
        receiver.advance(1);
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(sender, receiver).commit());
        assertEquals(packet(12), receiver.checkpoint().locals().field("input"));
    }
    static XBusExchange.Preparation exchange(FlowRuntime a, FlowRuntime b) { return XBusExchange.prepare(a, "x0", b, "x1"); }
    @Test void readComputeWriteUsesExistingTypedGraphAndEachSuccessorWaitsForItsHandoff() {
        var caller = runtime(new FlowProgram(Map.of("out", PACKET), List.of(new FlowProgram.Write("send", "x0", "out"),
            new FlowProgram.Read("reply", "x0", "reply", PACKET))), new DataValue.RecordValue(Map.of("out", packet(5))));
        var callee = runtime(calculator(), EMPTY);
        caller.advance(1); callee.advance(1);
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(caller, callee).commit());
        assertEquals(Optional.of("calculate"), callee.sourceId());
        assertFalse(callee.checkpoint().locals().fields().containsKey("out"));
        caller.advance(1); long work = callee.checkpoint().work(); int slices = 0;
        while (callee.checkpoint().status() != FlowRuntime.Status.WAITING_WRITE) {
            assertTrue(slices++ < 50); assertEquals(1, callee.advance(1));
            assertEquals(++work, callee.checkpoint().work());
        }
        assertTrue(slices > 2, "Graph work must be sliced, not evaluated for free");
        assertEquals(packet(12), callee.checkpoint().locals().field("out"));
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(caller, callee).commit());
        assertEquals(packet(12), caller.checkpoint().locals().field("reply"));
        assertEquals(FlowRuntime.Status.COMPLETE, caller.checkpoint().status());
        assertEquals(FlowRuntime.Status.COMPLETE, callee.checkpoint().status());
    }
    static FlowProgram calculator() {
        var graph = new ComputationGraph(Map.of("input", PACKET), Map.of(
            "input", new ComputationGraph.Input("input"), "value", new ComputationGraph.Field("input", "value"),
            "seven", new ComputationGraph.Constant(new DataValue.IntegerValue(7)),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "value", "seven"),
            "record", new ComputationGraph.MakeRecord(Map.of("value", "sum"))), Map.of("out", "record"));
        var expressions = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(),
            List.of(), List.of(), Map.of("sum", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        return new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x1", "input", PACKET),
            new FlowProgram.Evaluate("calculate", "sum"), new FlowProgram.Write("return", "x1", "out")), expressions);
    }
    @Test void insufficientReceiverMemoryLeavesBothWaitsAndPayloadUnchanged() {
        var sender = writer(); var template = reader();
        var receiver = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), template.program(), EMPTY,
            new ResourceLimits(1000, template.checkpoint().controlUnits() + 2, 1));
        sender.advance(1); receiver.advance(1);
        var a = sender.checkpoint(); var b = receiver.checkpoint();
        assertEquals(XBusExchange.Status.MEMORY_LIMIT, exchange(sender, receiver).status());
        assertEquals(a, sender.checkpoint()); assertEquals(b, receiver.checkpoint());
    }
    @Test void readFirstAlsoWaitsAndDirectionIsNotPermanentlyAssignedToTheWire() {
        var sender = writer(); var receiver = reader(); receiver.advance(1);
        assertEquals(XBusExchange.Status.NOT_READY, exchange(sender, receiver).status());
        sender.advance(1);
        assertEquals(XBusExchange.Status.EXCHANGED, XBusExchange.prepare(receiver, "x1", sender, "x0").commit());
        assertEquals(packet(12), receiver.checkpoint().locals().field("input"));
    }
    @Test void sameDirectionNeverExchangesAndWrongPortDoesNotConsume() {
        var a = writer(); var b = writer(); a.advance(1); b.advance(1);
        assertEquals(XBusExchange.Status.NOT_READY, XBusExchange.prepare(a, "x0", b, "x0").status());
        var c = reader(); var d = reader(); c.advance(1); d.advance(1);
        assertEquals(XBusExchange.Status.NOT_READY, XBusExchange.prepare(c, "x1", d, "x1").status());
        assertEquals(XBusExchange.Status.PORT_MISMATCH, XBusExchange.prepare(a, "x2", c, "x1").status());
        assertEquals(FlowRuntime.Status.WAITING_WRITE, a.checkpoint().status());
    }
    @Test void pausedUnloadedAndChangedContinuationsCannotBeExchanged() {
        var sender = writer(); var receiver = reader(); sender.advance(1); receiver.advance(1);
        var prepared = exchange(sender, receiver); receiver.setPaused(true);
        assertEquals(XBusExchange.Status.PAUSED, exchange(sender, receiver).status());
        assertEquals(XBusExchange.Status.STALE, prepared.commit()); receiver.setPaused(false);
        receiver.unload(); assertEquals(XBusExchange.Status.UNAVAILABLE, exchange(sender, receiver).status());
        assertEquals(0, receiver.advance(1)); receiver.load();
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(sender, receiver).commit());
    }
    @Test void wrongPacketTypeIsNotAcknowledgedAndControlCostsProgramCapacity() {
        var sender = writer(); var receiver = runtime(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "input",
            new DataType.RecordType(Map.of("value", DataType.Scalar.TEXT))))), EMPTY);
        sender.advance(1); receiver.advance(1);
        assertEquals(XBusExchange.Status.TYPE_MISMATCH, exchange(sender, receiver).status());
        assertEquals(FlowRuntime.Status.WAITING_WRITE, sender.checkpoint().status());
        var error = assertThrows(ComputationException.class, () -> new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), reader().program(),
            EMPTY, new ResourceLimits(0, 100000, 1)));
        assertEquals(ComputationException.Code.PROGRAM_LIMIT, error.code());
    }
    @Test void unloadAndReloadInvalidatesAnUnpublishedPreparedExchange() {
        var sender = writer(); var receiver = reader(); sender.advance(1); receiver.advance(1);
        var prepared = exchange(sender, receiver); var before = receiver.checkpoint();
        receiver.unload(); receiver.load();
        assertEquals(before, receiver.checkpoint(), "Lifecycle must not change logical progress");
        assertEquals(XBusExchange.Status.STALE, prepared.commit(), "Host must revalidate after lifecycle changes");
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(sender, receiver).commit());
    }
    static FlowRuntime writer() { return runtime(new FlowProgram(Map.of("out", PACKET),
        List.of(new FlowProgram.Write("send", "x0", "out"))), new DataValue.RecordValue(Map.of("out", packet(12)))); }
    static FlowRuntime reader() { return runtime(new FlowProgram(Map.of(),
        List.of(new FlowProgram.Read("receive", "x1", "input", PACKET))), EMPTY); }
    static FlowRuntime runtime(FlowProgram program, DataValue.RecordValue inputs) {
        return new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, inputs, LIMITS);
    }
    static DataValue.RecordValue packet(long n) { return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(n))); }
}
